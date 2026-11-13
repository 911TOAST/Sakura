// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <chrono>
#include "common/adpf.h"
#include "common/logging.h"
#include "common/settings.h"
#include "common/thread.h"
#include "core/frontend/emu_window.h"
#include "video_core/renderer_vulkan/vk_present_manager.h"
#include "video_core/renderer_vulkan/vk_scheduler.h"
#include "video_core/renderer_vulkan/vk_swapchain.h"
#include "video_core/vulkan_common/vulkan_device.h"
#include "video_core/vulkan_common/vulkan_surface.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Vulkan {


namespace {

bool CanBlitToSwapchain(const vk::PhysicalDevice& physical_device, VkFormat format) {
    const VkFormatProperties props{physical_device.GetFormatProperties(format)};
    return (props.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_DST_BIT);
}

[[nodiscard]] VkImageSubresourceLayers MakeImageSubresourceLayers() {
    return VkImageSubresourceLayers{
        .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
        .mipLevel = 0,
        .baseArrayLayer = 0,
        .layerCount = 1,
    };
}

[[nodiscard]] VkImageBlit MakeImageBlit(s32 frame_width, s32 frame_height, s32 swapchain_width,
                                        s32 swapchain_height) {
    return VkImageBlit{
        .srcSubresource = MakeImageSubresourceLayers(),
        .srcOffsets =
            {
                {
                    .x = 0,
                    .y = 0,
                    .z = 0,
                },
                {
                    .x = frame_width,
                    .y = frame_height,
                    .z = 1,
                },
            },
        .dstSubresource = MakeImageSubresourceLayers(),
        .dstOffsets =
            {
                {
                    .x = 0,
                    .y = 0,
                    .z = 0,
                },
                {
                    .x = swapchain_width,
                    .y = swapchain_height,
                    .z = 1,
                },
            },
    };
}

[[nodiscard]] VkImageCopy MakeImageCopy(u32 frame_width, u32 frame_height, u32 swapchain_width,
                                        u32 swapchain_height) {
    return VkImageCopy{
        .srcSubresource = MakeImageSubresourceLayers(),
        .srcOffset =
            {
                .x = 0,
                .y = 0,
                .z = 0,
            },
        .dstSubresource = MakeImageSubresourceLayers(),
        .dstOffset =
            {
                .x = 0,
                .y = 0,
                .z = 0,
            },
        .extent =
            {
                .width = (std::min)(frame_width, swapchain_width),
                .height = (std::min)(frame_height, swapchain_height),
                .depth = 1,
            },
    };
}

} // Anonymous namespace

PresentManager::PresentManager(const vk::Instance& instance_,
                               Core::Frontend::EmuWindow& render_window_,
                               const Device& device_,
                               MemoryAllocator& memory_allocator_,
                               Scheduler& scheduler_,
                               Swapchain& swapchain_,
                               vk::SurfaceKHR& surface_)
    : instance{instance_}
    , render_window{render_window_}
    , device{device_}
    , memory_allocator{memory_allocator_}
    , scheduler{scheduler_}
    , swapchain{swapchain_}
    , surface{surface_}
    , blit_supported{CanBlitToSwapchain(device.GetPhysical(), swapchain.GetImageViewFormat())}
    // Zero-copy present is the Mali-specific endgame: the window composition pass writes directly
    // into the acquired swapchain image and ends in PRESENT_SRC_KHR. It removes the intermediate
    // full-screen image (one per swapchain buffer), the full-screen copy, two image barriers and
    // the present semaphore round trip from every frame. The fallback path below is unchanged for
    // every other driver.
    , use_direct_present{device_.IsMali() && device_.IsMalikanMode() &&
                         (swapchain.GetImageViewFormat() == swapchain.GetImageFormat() ||
                          device_.IsKhrSwapchainMutableFormatEnabled())}
    // Malikan always presents asynchronously. Waiting for the worker on every frame serialises
    // command recording with presentation and costs a full frame of GPU/CPU overlap. The direct
    // path needs no copy work, so the async win there is purely "do not drain the worker", which
    // is done inline: the Android WSI binds the present to the thread that acquired the image and
    // handing vkQueuePresentKHR to a second thread is what broke Mali.
    //
    // The copy path can still use a dedicated present thread, but only when the user asks for it:
    // forcing it on Malikan raced swapchain recreation against the render thread, and that is the
    // reason async presentation is off by default on Android in the first place.
    , use_present_thread{!use_direct_present && Settings::values.async_presentation.GetValue()}
{
    SetImageCount();

    auto& dld = device.GetLogical();
    if (use_direct_present) {
        ResetDirectFrames();
        LOG_INFO(Render_Vulkan,
                 "[Present] Zero-copy direct presentation enabled: {} swapchain images, "
                 "no intermediate frame images, no swapchain copy, {} present",
                 image_count,
                 Settings::values.async_direct_present.GetValue() ? "async submit" : "synchronous");
        return;
    }

    cmdpool = dld.CreateCommandPool({
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .pNext = nullptr,
        .flags =
            VK_COMMAND_POOL_CREATE_TRANSIENT_BIT | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = device.GetGraphicsFamily(),
    });
    auto cmdbuffers = cmdpool.Allocate(image_count);

    frames.resize(image_count);
    for (u32 i = 0; i < frames.size(); i++) {
        Frame& frame = frames[i];
        frame.cmdbuf = vk::CommandBuffer{cmdbuffers[i], device.GetDispatchLoader()};
        frame.render_ready = dld.CreateSemaphore({
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
        });
        frame.present_done = dld.CreateFence({
            .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
            .pNext = nullptr,
            .flags = VK_FENCE_CREATE_SIGNALED_BIT,
        });
        free_queue.push_back(&frame);
    }

    if (use_present_thread) {
        present_thread = std::jthread([this](std::stop_token token) { PresentThread(token); });
    }
}

PresentManager::~PresentManager() {
    // Drain anything still queued so the present thread never touches a destroyed swapchain or
    // scheduler. The jthread member stops and joins the thread afterwards.
    WaitPresent();
}

Frame* PresentManager::GetRenderFrame() {
    if (use_direct_present) {
        const Layout::FramebufferLayout& layout = render_window.GetFramebufferLayout();

        // A present that failed with VK_ERROR_SURFACE_LOST_KHR is reported here instead of being
        // rebuilt on the present thread: surface and swapchain objects belong to the render
        // thread, and rebuilding them under a queued present would tear the WSI state.
        const bool lost = direct_recreate_pending.exchange(false);

        // Resize or present-mode changes must happen before an image is acquired. This path runs
        // at most a handful of times per session, so the full idle is acceptable here.
        if (lost || swapchain.GetWidth() != layout.width ||
            swapchain.GetHeight() != layout.height ||
            frames.size() != swapchain.GetImageCount() || swapchain.NeedsRecreation()) {
            if (lost) {
                surface = CreateSurface(instance, render_window.GetWindowInfo());
            }
            RecreateSwapchainDirect(layout.width, layout.height);
        }

        while (true) {
            try {
                // The acquired image is only handed out once the presentation engine is done with
                // it, which already proves the previous frame's GPU work is finished. Waiting on
                // the timeline for the same image again only stalled the render thread.
                if (!swapchain.AcquireNextImage(false)) {
                    break;
                }
                RecreateSwapchainDirect(layout.width, layout.height);
            } catch (const vk::Exception& except) {
                if (except.GetResult() != VK_ERROR_SURFACE_LOST_KHR) {
                    throw;
                }
#ifdef __ANDROID__
                surface = CreateSurface(instance, render_window.GetWindowInfo());
                RecreateSwapchainDirect(layout.width, layout.height);
#else
                throw;
#endif
            }
        }

        Frame* frame = &frames[swapchain.GetImageIndex()];
        frame->direct_present = true;
        frame->swapchain_image_index = static_cast<u32>(swapchain.GetImageIndex());
        return frame;
    }

    // Wait for free presentation frames
    std::unique_lock lock{free_mutex};
    free_cv.wait(lock, [this] { return !free_queue.empty(); });

    // Take the frame from the queue
    Frame* frame = free_queue.front();
    free_queue.pop_front();

    // Wait for the presentation to be finished so all frame resources are free
    frame->present_done.Wait();
    frame->present_done.Reset();

    return frame;
}

VkSemaphore PresentManager::GetPresentWaitSemaphore() const {
    return use_direct_present ? swapchain.CurrentPresentSemaphore() : VK_NULL_HANDLE;
}

void PresentManager::Present(Frame* frame) {
    if (frame->direct_present) {
        // The WSI call stays on the render thread that acquired the image. "Async" here means the
        // frame is not waiting for the worker to drain the whole recorded chunk: the submission it
        // needs is published by serial, and only that serial is awaited below. Turning the setting
        // off restores the old fully synchronous drain for drivers that dislike overlap.
        if (!Settings::values.async_direct_present.GetValue()) {
            scheduler.WaitWorker();
        }

        // Latch the submission this frame waits on. Everything the render thread flushed before
        // this call is included, so the presenter knows exactly when the WSI may be touched.
        frame->present_serial = scheduler.IssuedSerial();

        PresentDirect(frame);
        return;
    }

    if (use_present_thread) {
        scheduler.Record([this, frame](vk::CommandBuffer) {
            std::unique_lock lock{queue_mutex};
            present_queue.push_back(frame);
            frame_cv.notify_one();
        });
    } else {
        scheduler.WaitWorker();
        CopyToSwapchain(frame);
        free_queue.push_back(frame);
    }
}

Frame* PresentManager::RecreateFrame(Frame* frame, u32 width, u32 height,
                                     VkFormat image_view_format, VkRenderPass rd) {
    auto& dld = device.GetLogical();

    frame->width = width;
    frame->height = height;

    if (frame->direct_present) {
        // Build one view + framebuffer for every swapchain image. The layout dimensions are the
        // surface dimensions, so no scaling is involved: the render pass covers the whole image
        // and ends with the PRESENT_SRC_KHR transition.
        // GetRenderFrame() keeps the vector sized to the swapchain before it hands out a frame
        // pointer, so never rebuild the vector here: the caller holds a pointer into it.
        for (std::size_t i = 0; i < frames.size(); ++i) {
            Frame& target = frames[i];
            target.width = width;
            target.height = height;
            target.image_view = dld.CreateImageView({
                .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .image = swapchain.GetImageIndex(i),
                .viewType = VK_IMAGE_VIEW_TYPE_2D,
                .format = image_view_format,
                .components =
                    {
                        .r = VK_COMPONENT_SWIZZLE_IDENTITY,
                        .g = VK_COMPONENT_SWIZZLE_IDENTITY,
                        .b = VK_COMPONENT_SWIZZLE_IDENTITY,
                        .a = VK_COMPONENT_SWIZZLE_IDENTITY,
                    },
                .subresourceRange =
                    {
                        .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                        .baseMipLevel = 0,
                        .levelCount = 1,
                        .baseArrayLayer = 0,
                        .layerCount = 1,
                    },
            });
            const VkImageView image_view{*target.image_view};
            target.framebuffer = dld.CreateFramebuffer({
                .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .renderPass = rd,
                .attachmentCount = 1,
                .pAttachments = &image_view,
                .width = width,
                .height = height,
                .layers = 1,
            });
        }
        return frame;
    }

    frame->image = memory_allocator.CreateImage({
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = nullptr,
        .flags = VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT | VK_IMAGE_CREATE_EXTENDED_USAGE_BIT,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = swapchain.GetImageFormat(),
        .extent =
            {
                .width = width,
                .height = height,
                .depth = 1,
            },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = 0,
        .pQueueFamilyIndices = nullptr,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    });

    frame->image_view = dld.CreateImageView({
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .image = *frame->image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = image_view_format,
        .components =
            {
                .r = VK_COMPONENT_SWIZZLE_IDENTITY,
                .g = VK_COMPONENT_SWIZZLE_IDENTITY,
                .b = VK_COMPONENT_SWIZZLE_IDENTITY,
                .a = VK_COMPONENT_SWIZZLE_IDENTITY,
            },
        .subresourceRange =
            {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0,
                .levelCount = 1,
                .baseArrayLayer = 0,
                .layerCount = 1,
            },
    });

    const VkImageView image_view{*frame->image_view};
    frame->framebuffer = dld.CreateFramebuffer({
        .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .renderPass = rd,
        .attachmentCount = 1,
        .pAttachments = &image_view,
        .width = width,
        .height = height,
        .layers = 1,
    });
    return frame;
}

void PresentManager::PresentDirect(Frame* frame) {
    // The WSI must not be asked to wait on frame->render_ready before the submission signalling
    // it has been issued to the queue. WaitSerial only waits for submission, never for GPU
    // completion, so the render thread keeps overlapping the next frame with this one instead of
    // draining the whole worker like WaitWorker does.
    scheduler.WaitSerial(frame->present_serial);

    // The render submission already waited on the acquire semaphore and signals
    // frame->render_ready when the composition pass (including the transition to
    // PRESENT_SRC_KHR) has finished. Handing that same binary semaphore to the WSI is the
    // entire presentation path.
    try {
        swapchain.Present(*frame->render_ready, frame->swapchain_image_index);
        static auto prev_t = std::chrono::steady_clock::now();
        auto cur_t = std::chrono::steady_clock::now();
        int64_t dt = std::chrono::duration_cast<std::chrono::nanoseconds>(cur_t - prev_t).count();
        prev_t = cur_t;
        Common::AdpfFrameTick(dt, 16666666LL);
    } catch (const vk::Exception& except) {
        // A lost surface is rebuilt by the render thread on its next frame: surface and swapchain
        // objects belong to that thread, and this frame has nothing left to retry with.
        if (except.GetResult() == VK_ERROR_SURFACE_LOST_KHR) {
            direct_recreate_pending = true;
            return;
        }
        throw;
    }
}

void PresentManager::WaitPresent() {
    if (!use_present_thread) {
        return;
    }

    // Wait for the present queue to be empty
    {
        std::unique_lock queue_lock{queue_mutex};
        frame_cv.wait(queue_lock, [this] { return present_queue.empty(); });
    }

    // The above condition will be satisfied when the last frame is taken from the queue.
    // To ensure that frame has been presented as well take hold of the swapchain
    // mutex.
    std::scoped_lock swapchain_lock{swapchain_mutex};
}

void PresentManager::PresentThread(std::stop_token token) {
    Common::SetCurrentThreadName("VulkanPresent");
#if defined(__unix__) || defined(__ANDROID__)
    Common::AdpfRegisterThread(static_cast<int32_t>(gettid()));
#endif
#ifdef __ANDROID__
    Common::SetCurrentThreadPriority(Common::ThreadPriority::High);
#endif
    while (!token.stop_requested()) {
        std::unique_lock lock{queue_mutex};
        // Wait for presentation frames
        frame_cv.wait(lock, token, [this] { return !present_queue.empty(); });
        if (!token.stop_requested()) {
            // Take the frame and notify anyone waiting
            Frame* frame = present_queue.front();
            present_queue.pop_front();
            frame_cv.notify_one();

            // By exchanging the lock ownership we take the swapchain lock
            // before the queue lock goes out of scope. This way the swapchain
            // lock in WaitPresent is guaranteed to occur after here.
            std::exchange(lock, std::unique_lock{swapchain_mutex});
            CopyToSwapchain(frame);

            // Free the frame for reuse
            std::scoped_lock fl{free_mutex};
            free_queue.push_back(frame);
            free_cv.notify_one();
        }
    }
}

void PresentManager::RecreateSwapchain(Frame* frame) {
    swapchain.Create(*surface, frame->width, frame->height); // Pass raw pointer
    SetImageCount();
}

void PresentManager::RecreateSwapchainDirect(u32 width, u32 height) {
    WaitPresent();
    scheduler.Finish();
    device.GetLogical().WaitIdle();
    Common::AdpfReset();
    frames.clear();
    swapchain.Create(*surface, width, height);
    SetImageCount();
    ResetDirectFrames();
}

void PresentManager::ResetDirectFrames() {
    frames.clear();
    frames.resize(image_count);
    auto& dld = device.GetLogical();
    for (Frame& frame : frames) {
        frame.render_ready = dld.CreateSemaphore({
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
        });
        // Zero means "the render target wrappers for this image do not exist yet"; the next
        // DrawToFrame rebuilds every view and framebuffer through RecreateFrame().
        frame.width = 0;
        frame.height = 0;
    }
}

void PresentManager::SetImageCount() {
    // We cannot have more than 7 images in flight at any given time.
    // FRAMES_IN_FLIGHT is 8, and the cache TICKS_TO_DESTROY is 8.
    // Mali drivers will give us 6.
    // Direct present has one frame per swapchain image no copy command buffers, so the scheduler
    // image cap does not apply.
    image_count = use_direct_present ? swapchain.GetImageCount()
                                     : std::min<size_t>(swapchain.GetImageCount(), 7);
}

void PresentManager::CopyToSwapchain(Frame* frame) {
    bool requires_recreation = false;

    while (true) {
        try {
            // Recreate surface and swapchain if needed.
            if (requires_recreation) {
#ifdef __ANDROID__
                surface = CreateSurface(instance, render_window.GetWindowInfo());
#endif
                RecreateSwapchain(frame);
            }

            // Draw to swapchain.
            return CopyToSwapchainImpl(frame);
        } catch (const vk::Exception& except) {
            if (except.GetResult() != VK_ERROR_SURFACE_LOST_KHR) {
                throw;
            }

            requires_recreation = true;
        }
    }
}

void PresentManager::CopyToSwapchainImpl(Frame* frame) {

    // If the size of the incoming frames has changed, recreate the swapchain
    // to account for that.
    const bool is_suboptimal = swapchain.NeedsRecreation();
    const bool size_changed =
        swapchain.GetWidth() != frame->width || swapchain.GetHeight() != frame->height;
    if (is_suboptimal || size_changed) {
        RecreateSwapchain(frame);
    }

    while (swapchain.AcquireNextImage()) {
        RecreateSwapchain(frame);
    }

    const vk::CommandBuffer cmdbuf{frame->cmdbuf};
    cmdbuf.Begin({
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .pNext = nullptr,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
        .pInheritanceInfo = nullptr,
    });

    const VkImage image{swapchain.CurrentImage()};
    const VkExtent2D extent = swapchain.GetExtent();
    const std::array pre_barriers{
        VkImageMemoryBarrier{
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = image,
            .subresourceRange{
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0,
                .levelCount = 1,
                .baseArrayLayer = 0,
                .layerCount = VK_REMAINING_ARRAY_LAYERS,
            },
        },
        VkImageMemoryBarrier{
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
            .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = *frame->image,
            .subresourceRange{
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0,
                .levelCount = 1,
                .baseArrayLayer = 0,
                .layerCount = VK_REMAINING_ARRAY_LAYERS,
            },
        },
    };
    const std::array post_barriers{
        VkImageMemoryBarrier{
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_MEMORY_READ_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = image,
            .subresourceRange{
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0,
                .levelCount = 1,
                .baseArrayLayer = 0,
                .layerCount = VK_REMAINING_ARRAY_LAYERS,
            },
        },
        VkImageMemoryBarrier{
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
            .dstAccessMask = VK_ACCESS_MEMORY_WRITE_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = *frame->image,
            .subresourceRange{
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0,
                .levelCount = 1,
                .baseArrayLayer = 0,
                .layerCount = VK_REMAINING_ARRAY_LAYERS,
            },
        },
    };

    cmdbuf.PipelineBarrier(vk::PIPELINE_STAGE_GRAPHICS_COMPUTE_TRANSFER, VK_PIPELINE_STAGE_TRANSFER_BIT, {},
                           {}, {}, pre_barriers);

    // A 1:1 present needs no resampling. Sending it through BlitImage puts the driver on its
    // filtered path for nothing; CopyImage is the straight copy and preserves the exact pixels.
    const bool needs_scaling = frame->width != extent.width || frame->height != extent.height;
    if (blit_supported && needs_scaling) {
        cmdbuf.BlitImage(*frame->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image,
                         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                         MakeImageBlit(frame->width, frame->height, extent.width, extent.height),
                         VK_FILTER_LINEAR);
    } else {
        cmdbuf.CopyImage(*frame->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image,
                         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                         MakeImageCopy(frame->width, frame->height, extent.width, extent.height));
    }

    // Nothing in this command buffer consumes the blit; the present waits on a semaphore at the
    // transfer stage. BOTTOM_OF_PIPE makes the layout transition available without stalling
    // graphics stages that do not exist here.
    cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, {},
                           {}, {}, post_barriers);

    cmdbuf.End();

    const VkSemaphore present_semaphore = swapchain.CurrentPresentSemaphore();
    const VkSemaphore render_semaphore = swapchain.CurrentRenderSemaphore();
    const std::array wait_semaphores = {present_semaphore, *frame->render_ready};

    static constexpr std::array<VkPipelineStageFlags, 2> wait_stage_masks{
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
    };

    const VkSubmitInfo submit_info{
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .pNext = nullptr,
        .waitSemaphoreCount = 2U,
        .pWaitSemaphores = wait_semaphores.data(),
        .pWaitDstStageMask = wait_stage_masks.data(),
        .commandBufferCount = 1,
        .pCommandBuffers = cmdbuf.address(),
        .signalSemaphoreCount = 1U,
        .pSignalSemaphores = &render_semaphore,
    };

    // Submit the image copy/blit to the swapchain
    {
        std::scoped_lock submit_lock{scheduler.submit_mutex};
        switch (const VkResult result =
                    device.GetGraphicsQueue().Submit(submit_info, *frame->present_done)) {
        case VK_SUCCESS:
            break;
        case VK_ERROR_DEVICE_LOST:
            device.ReportLoss();
            [[fallthrough]];
        default:
            vk::Check(result);
            break;
        }
    }

    swapchain.Present(render_semaphore);
    static auto prev_t2 = std::chrono::steady_clock::now();
    auto cur_t2 = std::chrono::steady_clock::now();
    int64_t dt2 = std::chrono::duration_cast<std::chrono::nanoseconds>(cur_t2 - prev_t2).count();
    prev_t2 = cur_t2;
    Common::AdpfFrameTick(dt2, 16666666LL);
}

} // namespace Vulkan
