// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Port of Lemon-Project's FrameGen (native Lemon FG path). See frame_gen.h.

#include <algorithm>

#include "common/logging/log.h"
#include "common/settings.h"
#include "video_core/renderer_vulkan/present/frame_gen.h"
#include "video_core/renderer_vulkan/vk_present_manager.h"
#include "video_core/renderer_vulkan/vk_scheduler.h"
#include "video_core/vulkan_common/vulkan_device.h"

namespace Vulkan {

namespace {

constexpr size_t LSFG_REQUIRED_FRAMES = 2;
constexpr size_t LSFG_RECURRENCE_FRAMES = 2;
constexpr u32 MIN_FRAME_GEN_MULTIPLIER = 2;
constexpr u32 MAX_FRAME_GEN_MULTIPLIER = 4;

[[nodiscard]] VkImageMemoryBarrier MakeTransitionBarrier(VkImage image, VkAccessFlags src_access,
                                                         VkAccessFlags dst_access,
                                                         VkImageLayout old_layout,
                                                         VkImageLayout new_layout) {
    return VkImageMemoryBarrier{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = src_access,
        .dstAccessMask = dst_access,
        .oldLayout = old_layout,
        .newLayout = new_layout,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange{
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0,
            .levelCount = 1,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
    };
}

[[nodiscard]] VkImageCopy MakeCopyRegion(VkExtent2D extent) {
    return VkImageCopy{
        .srcSubresource{
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .mipLevel = 0,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
        .srcOffset = {},
        .dstSubresource{
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .mipLevel = 0,
            .baseArrayLayer = 0,
            .layerCount = 1,
        },
        .dstOffset = {},
        .extent = {.width = extent.width, .height = extent.height, .depth = 1},
    };
}

} // Anonymous namespace

FrameGen::FrameGen(MemoryAllocator& memory_allocator_, Scheduler& scheduler_)
    : memory_allocator{memory_allocator_}, scheduler{scheduler_} {}

FrameGen::~FrameGen() = default;

void FrameGen::Process(const Device& device, Frame* frame, VkFormat format) {
    generated = false;

    if (unavailable || !Settings::values.frame_gen.GetValue()) {
        if (chain) {
            scheduler.Finish();
            chain.reset();
        }
        warm_streak = 0;
        return;
    }

    if (!LemonFgChain::IsSupported(device, format)) {
        unavailable = true;
        return;
    }

    if (!frame->storage_view) {
        warm_streak = 0;
        return;
    }

    const VkExtent2D extent{.width = frame->width, .height = frame->height};
    if (!built || built_extent.width != extent.width || built_extent.height != extent.height ||
        built_format != format) {
        Rebuild(device, extent, format);
        if (unavailable || !chain) {
            return;
        }
    }

    const u64 count = frame_count++;
    last_count = count;
    last_generations =
        std::clamp(Settings::values.frame_gen_multiplier.GetValue(), MIN_FRAME_GEN_MULTIPLIER,
                   MAX_FRAME_GEN_MULTIPLIER) -
        1;

    const bool warm = count + 1 >= LSFG_REQUIRED_FRAMES;
    warm_streak = warm ? warm_streak + 1 : 0;
    generated = warm && warm_streak >= LSFG_RECURRENCE_FRAMES && last_generations > 0;

    scheduler.RequestOutsideRenderPassOperationContext();
    scheduler.Record([this, source = *frame->image, extent, count,
                      dispatch = warm](vk::CommandBuffer cmdbuf) {
        CopyPresentedFrame(cmdbuf, source, chain->Input(count), extent);
        if (dispatch) {
            chain->DispatchShared(cmdbuf, count);
        }
    });
}

size_t FrameGen::GeneratedFrameCount() const {
    return generated ? last_generations : 0;
}

void FrameGen::GenerateInto(const Device& device, Frame* destination, size_t generation) {
    chain->SetTarget(device, last_generations, generation, destination->index,
                     *destination->storage_view);

    const VkExtent2D extent{.width = destination->width, .height = destination->height};

    scheduler.RequestOutsideRenderPassOperationContext();
    scheduler.Record([this, count = last_count, generation_count = last_generations, generation,
                      target = destination->index, image = *destination->image,
                      extent](vk::CommandBuffer cmdbuf) {
        chain->DispatchGeneration(cmdbuf, count, generation_count, generation, target, image,
                                  extent);
    });
}

void FrameGen::Rebuild(const Device& device, VkExtent2D extent, VkFormat format) {
    scheduler.Finish();
    chain.reset();

    try {
        chain.emplace(device, memory_allocator, extent, format);
    } catch (const vk::Exception& exception) {
        if (exception.GetResult() != VK_ERROR_OUT_OF_DEVICE_MEMORY &&
            exception.GetResult() != VK_ERROR_OUT_OF_HOST_MEMORY) {
            throw;
        }
        LOG_WARNING(Render_Vulkan, "Lemon frame generation disabled after allocation failure: {}",
                    exception.what());
        unavailable = true;
        return;
    }
    built_extent = extent;
    built_format = format;
    built = true;
    frame_count = 0;
    warm_streak = 0;
    generated = false;
}

void FrameGen::CopyPresentedFrame(vk::CommandBuffer cmdbuf, VkImage source,
                                  LemonFgImage& destination, VkExtent2D extent) {
    const auto make_barrier = MakeTransitionBarrier;

    const std::array before{
        make_barrier(source, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                     VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
        make_barrier(destination.Handle(), VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                     destination.Layout(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
    };
    cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, 0, {}, {}, before);

    cmdbuf.CopyImage(source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination.Handle(),
                     VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, MakeCopyRegion(extent));

    const std::array after{
        make_barrier(source, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                     VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
        make_barrier(destination.Handle(), VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                     VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
    };
    cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFER_BIT,
                           VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           0, {}, {}, after);

    destination.SetLayout(VK_IMAGE_LAYOUT_GENERAL);
}

} // namespace Vulkan
