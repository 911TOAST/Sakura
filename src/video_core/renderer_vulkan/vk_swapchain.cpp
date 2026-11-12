// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2019 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <array>
#include <limits>
#include <vector>

#ifdef __ANDROID__
#include <android/api-level.h>
#endif

#include "common/logging.h"
#include "common/settings.h"
#include "common/settings_enums.h"
#include "video_core/renderer_vulkan/vk_scheduler.h"
#include "video_core/renderer_vulkan/vk_swapchain.h"
#include "video_core/vulkan_common/vk_enum_string_helper.h"
#include "video_core/vulkan_common/vulkan_device.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"
#include "vulkan/vulkan_core.h"

namespace Vulkan {

namespace {

VkSurfaceFormatKHR ChooseSwapSurfaceFormat(vk::Span<VkSurfaceFormatKHR> formats) {
    if (formats.size() == 1 && formats[0].format == VK_FORMAT_UNDEFINED) {
        VkSurfaceFormatKHR format;
        format.format = VK_FORMAT_B8G8R8A8_UNORM;
        format.colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        return format;
    }
    const auto& found = std::find_if(formats.begin(), formats.end(), [](const auto& format) {
        return format.format == VK_FORMAT_B8G8R8A8_UNORM &&
               format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    });
    return found != formats.end() ? *found : formats[0];
}

static VkPresentModeKHR ChooseSwapPresentMode(bool has_imm, bool has_mailbox,
                                              bool has_fifo_relaxed, bool force_unlocked) {
    // Malikan is a throughput-first profile: FIFO/FIFO_RELAXED are treated as a request to unlock
    // the presentation path. Explicit Mailbox/Immediate selections are still honoured, and the
    // profile itself defaults to Immediate.
    if (force_unlocked) {
        switch (Settings::values.vsync_mode.GetValue()) {
        case Settings::VSyncMode::Immediate:
            if (has_imm) {
                return VK_PRESENT_MODE_IMMEDIATE_KHR;
            }
            break;
        case Settings::VSyncMode::Mailbox:
            if (has_mailbox) {
                return VK_PRESENT_MODE_MAILBOX_KHR;
            }
            break;
        default:
            break;
        }
        if (has_imm) {
            return VK_PRESENT_MODE_IMMEDIATE_KHR;
        }
        if (has_mailbox) {
            return VK_PRESENT_MODE_MAILBOX_KHR;
        }
    }
    // Mailbox doesn't lock the application like FIFO (vsync)
    // FIFO present mode locks the framerate to the monitor's refresh rate
    Settings::VSyncMode setting = [has_imm, has_mailbox]() {
        // Choose Mailbox or Immediate if unlocked and those modes are supported
        const auto mode = Settings::values.vsync_mode.GetValue();
        if (Settings::values.use_speed_limit.GetValue() &&
            Settings::values.current_speed_mode.GetValue() != Settings::SpeedMode::Turbo) {
            return mode;
        }
        switch (mode) {
        case Settings::VSyncMode::Fifo:
        case Settings::VSyncMode::FifoRelaxed:
            if (has_mailbox) {
                return Settings::VSyncMode::Mailbox;
            } else if (has_imm) {
                return Settings::VSyncMode::Immediate;
            }
            [[fallthrough]];
        default:
            return mode;
        }
    }();
    if (setting == Settings::VSyncMode::Immediate && !has_imm) {
        setting = Settings::VSyncMode::Mailbox;
    }
    if ((setting == Settings::VSyncMode::Mailbox && !has_mailbox) ||
        (setting == Settings::VSyncMode::FifoRelaxed && !has_fifo_relaxed)) {
        setting = Settings::VSyncMode::Fifo;
    }

    switch (setting) {
    case Settings::VSyncMode::Immediate:
        return VK_PRESENT_MODE_IMMEDIATE_KHR;
    case Settings::VSyncMode::Mailbox:
        return VK_PRESENT_MODE_MAILBOX_KHR;
    case Settings::VSyncMode::Fifo:
        return VK_PRESENT_MODE_FIFO_KHR;
    case Settings::VSyncMode::FifoRelaxed:
        return VK_PRESENT_MODE_FIFO_RELAXED_KHR;
    default:
        return VK_PRESENT_MODE_FIFO_KHR;
    }
}

VkExtent2D ChooseSwapExtent(const VkSurfaceCapabilitiesKHR& capabilities, u32 width, u32 height) {
    constexpr auto undefined_size{(std::numeric_limits<u32>::max)()};
    if (capabilities.currentExtent.width != undefined_size) {
        return capabilities.currentExtent;
    }
    VkExtent2D extent;
    extent.width = (std::max)(capabilities.minImageExtent.width,
                            (std::min)(capabilities.maxImageExtent.width, width));
    extent.height = (std::max)(capabilities.minImageExtent.height,
                             (std::min)(capabilities.maxImageExtent.height, height));
    return extent;
}

VkCompositeAlphaFlagBitsKHR ChooseAlphaFlags(const VkSurfaceCapabilitiesKHR& capabilities) {
    if (capabilities.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) {
        return VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    } else if (capabilities.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR) {
        return VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    } else {
        LOG_ERROR(Render_Vulkan, "Unknown composite alpha flags value {:#x}",
                  capabilities.supportedCompositeAlpha);
        return VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    }
}

} // Anonymous namespace

Swapchain::Swapchain(
    VkSurfaceKHR_T* surface_,
    const Device& device_,
    Scheduler& scheduler_,
    u32 width_,
    u32 height_)
    : surface(surface_)
    , device{device_}
    , scheduler{scheduler_}
{
    if (surface) {
        Create(surface, width_, height_);
    } else {
        width = width_;
        height = height_;
        image_view_format = VK_FORMAT_B8G8R8A8_UNORM;
        surface_format = {VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
        image_count = 1;
    }
}

Swapchain::~Swapchain() = default;

void Swapchain::Create(
    VkSurfaceKHR_T* surface_,
    u32 width_,
    u32 height_)
{
    is_outdated = false;
    is_suboptimal = false;
    width = width_;
    height = height_;
    surface = surface_;

    if (!surface) {
        return;
    }

    const auto physical_device = device.GetPhysical();
    const auto capabilities{physical_device.GetSurfaceCapabilitiesKHR(VkSurfaceKHR(surface))};
    if (capabilities.maxImageExtent.width == 0 || capabilities.maxImageExtent.height == 0) {
        return;
    }

    Destroy();

    CreateSwapchain(capabilities);
    CreateSemaphores();

    resource_ticks.clear();
    resource_ticks.resize(image_count);
}

bool Swapchain::AcquireNextImage(bool wait_previous_work) {
    const VkSemaphore acquire_semaphore{*present_semaphores[frame_index]};
    const VkResult result = device.GetLogical().AcquireNextImageKHR(
        *swapchain, (std::numeric_limits<u64>::max)(), acquire_semaphore, VK_NULL_HANDLE,
        &image_index);
    switch (result) {
    case VK_SUCCESS:
        break;
    case VK_SUBOPTIMAL_KHR:
        is_suboptimal = true;
        break;
    case VK_ERROR_OUT_OF_DATE_KHR:
        is_outdated = true;
        break;
    case VK_ERROR_SURFACE_LOST_KHR:
        vk::Check(result);
        break;
    default:
        LOG_ERROR(Render_Vulkan, "vkAcquireNextImageKHR returned {}", string_VkResult(result));
        break;
    }

    const auto wait_with_frame_pacing = [this](u64 tick) {
        switch (Settings::values.frame_pacing_mode.GetValue()) {
        case Settings::FramePacingMode::Target_Auto:
            scheduler.Wait(tick);
            break;
        case Settings::FramePacingMode::Target_30:
            scheduler.Wait(tick, 30.0);
            break;
        case Settings::FramePacingMode::Target_60:
            scheduler.Wait(tick, 60.0);
            break;
        case Settings::FramePacingMode::Target_90:
            scheduler.Wait(tick, 90.0);
            break;
        case Settings::FramePacingMode::Target_120:
            scheduler.Wait(tick, 120.0);
            break;
        }
    };

    // Target_Auto is a plain tick wait, i.e. exactly what the removed Android API>=30 bypass
    // hardcoded, so default behaviour is unchanged; the explicit 30/60/90/120 targets give the
    // user a working frame-rate cap, which on a 120 Hz panel is the main GPU-load lever.
    //
    // The tick itself is only needed when the caller cannot prove the image is idle. In the
    // zero-copy path the image is only handed back by the presentation engine after the GPU
    // finished the frame that rendered into it, so waiting on the timeline again duplicated a
    // guarantee the acquire already made and stalled the render thread for up to a full frame.
    // Passing 0 keeps the pacing sleep (which lives in Scheduler::Wait) and skips just the wait.
    const u64 previous_tick = wait_previous_work ? resource_ticks[image_index] : 0;
    wait_with_frame_pacing(previous_tick);

    resource_ticks[image_index] = scheduler.CurrentTick();

    // The render submission waits on the semaphore this acquire used. Latch it here and only
    // then advance the slot: presentation may run on its own thread and must never have to read
    // this index, and the render thread is free to acquire the next image right away.
    acquired_present_semaphore = acquire_semaphore;
    ++frame_index;
    if (frame_index >= present_semaphores.size()) {
        frame_index = 0;
    }

    return is_suboptimal || is_outdated;
}

void Swapchain::Present(VkSemaphore render_semaphore) {
    Present(render_semaphore, image_index);
}

void Swapchain::Present(VkSemaphore render_semaphore, u32 presented_image) {
    const auto present_queue{device.GetPresentQueue()};
    const VkPresentInfoKHR present_info{
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .pNext = nullptr,
        .waitSemaphoreCount = render_semaphore ? 1U : 0U,
        .pWaitSemaphores = &render_semaphore,
        .swapchainCount = 1,
        .pSwapchains = swapchain.address(),
        .pImageIndices = &presented_image,
        .pResults = nullptr,
    };
    std::scoped_lock lock{scheduler.submit_mutex};
    switch (const VkResult result = present_queue.Present(present_info)) {
    case VK_SUCCESS:
        break;
    case VK_SUBOPTIMAL_KHR:
        LOG_DEBUG(Render_Vulkan, "Suboptimal swapchain");
        break;
    case VK_ERROR_OUT_OF_DATE_KHR:
        is_outdated = true;
        break;
    case VK_ERROR_SURFACE_LOST_KHR:
        vk::Check(result);
        break;
    default:
        LOG_CRITICAL(Render_Vulkan, "Failed to present with error {}", string_VkResult(result));
        break;
    }
}

void Swapchain::CreateSwapchain(const VkSurfaceCapabilitiesKHR& capabilities) {
    const auto physical_device{device.GetPhysical()};
    const auto formats{physical_device.GetSurfaceFormatsKHR(VkSurfaceKHR(surface))};
    const auto present_modes = physical_device.GetSurfacePresentModesKHR(VkSurfaceKHR(surface));

    has_mailbox = std::find(present_modes.begin(), present_modes.end(), VK_PRESENT_MODE_MAILBOX_KHR)
                  != present_modes.end();
    has_imm = std::find(present_modes.begin(), present_modes.end(),
                        VK_PRESENT_MODE_IMMEDIATE_KHR) != present_modes.end();
    has_fifo_relaxed = std::find(present_modes.begin(), present_modes.end(),
                                 VK_PRESENT_MODE_FIFO_RELAXED_KHR) != present_modes.end();

    const VkCompositeAlphaFlagBitsKHR alpha_flags{ChooseAlphaFlags(capabilities)};
    surface_format = ChooseSwapSurfaceFormat(formats);
    present_mode =
        ChooseSwapPresentMode(has_imm, has_mailbox, has_fifo_relaxed, device.IsMalikanMode());

    LOG_INFO(Render_Vulkan,
             "[Swapchain] caps min={} max={} cur={}x{} transform={:#x} supported={:#x} alpha={:#x}",
             capabilities.minImageCount, capabilities.maxImageCount,
             capabilities.currentExtent.width, capabilities.currentExtent.height,
             static_cast<u32>(capabilities.currentTransform), capabilities.supportedTransforms,
             capabilities.supportedCompositeAlpha);

    // Triple buffering overlaps rendering and presentation without building a multi-frame backlog.
    u32 requested_image_count{capabilities.minImageCount + 1U};
    if (device.IsPanVk()) {
        // Mesa's Android WSI binds the buffers the window actually provides, so a deeper chain can
        // get the AHB import refused. Stay at three when the compositor allows it, but never ask
        // for fewer images than the surface's own minimum: minImageCount is a hard lower bound, and
        // asking below it made vkCreateSwapchainKHR fail with VK_ERROR_INVALID_EXTERNAL_HANDLE.
        // Android compositors commonly report 7 here (the stock Mali device reported min=7 too),
        // which is why an unconditional cap at 3 broke the swapchain instead of helping it.
        requested_image_count = (std::min)(requested_image_count, 3U);
        requested_image_count = (std::max)(requested_image_count, capabilities.minImageCount);
    }
    // Ensure Triple buffering if possible.
    if (capabilities.maxImageCount > 0) {
        if (requested_image_count > capabilities.maxImageCount) {
            requested_image_count = capabilities.maxImageCount;
        } else {
            requested_image_count =
                (std::max)(requested_image_count, (std::min)(3U, capabilities.maxImageCount));
        }
    } else {
        requested_image_count = (std::max)(requested_image_count, 3U);
    }
    // Mesa's AHB import needs the buffer readable as well as writable; the vendor DDK accepts a
    // write-only colour buffer, PanVK refuses it with VK_ERROR_INVALID_EXTERNAL_HANDLE.
    VkImageUsageFlags swapchain_usage =
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (device.IsPanVk()) {
        const VkImageUsageFlags panvk_usage =
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        swapchain_usage |= panvk_usage & capabilities.supportedUsageFlags;
    }
    VkSwapchainCreateInfoKHR swapchain_ci{
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .pNext = nullptr,
        .flags = 0,
        .surface = VkSurfaceKHR(surface),
        .minImageCount = requested_image_count,
        .imageFormat = surface_format.format,
        .imageColorSpace = surface_format.colorSpace,
        .imageExtent = {},
        .imageArrayLayers = 1,
        .imageUsage = swapchain_usage,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = 0,
        .pQueueFamilyIndices = nullptr,
#ifdef __ANDROID__
        // On Android, do not allow surface rotation to deviate from the frontend, except on Mesa
        // WSI which only advertises the window's current transform as supported.
        .preTransform = device.IsPanVk() ? capabilities.currentTransform
                                         : VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR,
#else
        .preTransform = capabilities.currentTransform,
#endif
        .compositeAlpha = alpha_flags,
        .presentMode = present_mode,
        // Let the presentation engine discard pixels that are not visible on the surface.
        .clipped = device.IsMalikanMode() ? VK_TRUE : VK_FALSE,
        .oldSwapchain = VkSwapchainKHR{},
    };
    const u32 graphics_family{device.GetGraphicsFamily()};
    const u32 present_family{device.GetPresentFamily()};
    const std::array<u32, 2> queue_indices{graphics_family, present_family};
    if (graphics_family != present_family) {
        swapchain_ci.imageSharingMode = VK_SHARING_MODE_CONCURRENT;
        swapchain_ci.queueFamilyIndexCount = static_cast<u32>(queue_indices.size());
        swapchain_ci.pQueueFamilyIndices = queue_indices.data();
    }
    // According to Vulkan spec, when using VK_SWAPCHAIN_CREATE_MUTABLE_FORMAT_BIT_KHR,
    // the base format (imageFormat) MUST be included in pViewFormats
    const std::array view_formats{
        swapchain_ci.imageFormat,  // Base format MUST be first
        VK_FORMAT_B8G8R8A8_UNORM,
        VK_FORMAT_B8G8R8A8_SRGB,
#ifdef __ANDROID__
        VK_FORMAT_R8G8B8A8_UNORM,  // Android may use RGBA
        VK_FORMAT_R8G8B8A8_SRGB,
#endif
    };
    VkImageFormatListCreateInfo format_list{
        .sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO_KHR,
        .pNext = nullptr,
        .viewFormatCount = static_cast<u32>(view_formats.size()),
        .pViewFormats = view_formats.data(),
    };
    // Mesa's Android WSI binds a single AHB format per image, so a multi-format view list makes
    // the import fail with VK_ERROR_INVALID_EXTERNAL_HANDLE. Vendor DDKs accept it; PanVK does
    // not, and it advertises the extension anyway.
    const bool use_mutable_format =
        device.IsKhrSwapchainMutableFormatEnabled() && !device.IsPanVk();
    if (use_mutable_format) {
        format_list.pNext = std::exchange(swapchain_ci.pNext, &format_list);
        swapchain_ci.flags |= VK_SWAPCHAIN_CREATE_MUTABLE_FORMAT_BIT_KHR;
    }
    LOG_INFO(Render_Vulkan,
             "[Swapchain] format={} space={} present={} count={} mutable={} extent={}x{}",
             static_cast<u32>(surface_format.format), static_cast<u32>(surface_format.colorSpace),
             static_cast<u32>(present_mode), requested_image_count, use_mutable_format, width,
             height);
    // Request the size again to reduce the possibility of a TOCTOU race condition.
    const auto updated_capabilities = physical_device.GetSurfaceCapabilitiesKHR(VkSurfaceKHR(surface));
    swapchain_ci.imageExtent = ChooseSwapExtent(updated_capabilities, width, height);
    // Don't add code within this and the swapchain creation.
    swapchain = device.GetLogical().CreateSwapchainKHR(swapchain_ci);

    extent = swapchain_ci.imageExtent;

    images = swapchain.GetImages();
    image_count = static_cast<u32>(images.size());
#ifdef __ANDROID__
    // Android is already ordered the same as Switch.
    image_view_format = VK_FORMAT_R8G8B8A8_UNORM;
#else
    image_view_format = VK_FORMAT_B8G8R8A8_UNORM;
#endif
}

void Swapchain::CreateSemaphores() {
    // Acquire semaphores are cycled by the render thread and can stay in flight for a frame or
    // two while a queued present waits for its signal, so keep two per swapchain image.
    present_semaphores.resize((std::max)(image_count * 2, std::size_t{2}));
    std::ranges::generate(present_semaphores,
                          [this] { return device.GetLogical().CreateSemaphore(); });
    render_semaphores.resize(image_count);
    std::ranges::generate(render_semaphores,
                          [this] { return device.GetLogical().CreateSemaphore(); });
}

void Swapchain::Destroy() {
    frame_index = 0;
    acquired_present_semaphore = VK_NULL_HANDLE;
    present_semaphores.clear();
    render_semaphores.clear();
    swapchain.reset();
}

bool Swapchain::NeedsPresentModeUpdate() const {
    const auto requested_mode =
        ChooseSwapPresentMode(has_imm, has_mailbox, has_fifo_relaxed, device.IsMalikanMode());
    return present_mode != requested_mode;
}

} // namespace Vulkan
