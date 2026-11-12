// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2019 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <memory>
#include <mutex>
#include <span>
#include <thread>
#include <utility>

#include <fmt/format.h>

#include "video_core/renderer_vulkan/vk_query_cache.h"

#include "common/settings.h"
#include "common/thread.h"
#include "video_core/gpu_logging/gpu_logging.h"
#include "video_core/renderer_vulkan/vk_command_pool.h"
#include "video_core/renderer_vulkan/vk_graphics_pipeline.h"
#include "video_core/renderer_vulkan/vk_master_semaphore.h"
#include "video_core/renderer_vulkan/vk_scheduler.h"
#include "video_core/renderer_vulkan/vk_state_tracker.h"
#include "video_core/renderer_vulkan/vk_texture_cache.h"
#include "video_core/vulkan_common/vulkan_device.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Vulkan {
namespace {
/// Attachments of \p current whose writeback the next pass discards. \p next is a define-at-begin
/// (clear) pass, so every attachment it carries is fully cleared. An attachment of the current
/// pass is dead only when the next pass attaches the same image in the same role (colour vs
/// depth/stencil) and clears it; matching by role keeps a depth write from being elided just
/// because the image reappears as a colour target. Bit k follows the render pass description
/// order: colour attachments first, then depth at index NumColorBuffers().
u16 StoreElideMask(const Framebuffer& current, const Framebuffer& next) {
    u16 mask = 0;
    const auto& cur_images = current.Images();
    const auto& next_images = next.Images();
    const u32 cur_colors = current.NumColorBuffers();
    const u32 next_colors = next.NumColorBuffers();
    for (u32 k = 0; k < cur_colors; ++k) {
        if (cur_images[k] == VK_NULL_HANDLE) {
            continue;
        }
        for (u32 j = 0; j < next_colors; ++j) {
            if (cur_images[k] == next_images[j]) {
                mask |= static_cast<u16>(1u << k);
                break;
            }
        }
    }
    if (current.HasAspectDepthBit() && next.HasAspectDepthBit() &&
        cur_images[cur_colors] != VK_NULL_HANDLE &&
        cur_images[cur_colors] == next_images[next_colors]) {
        mask |= static_cast<u16>(1u << cur_colors);
    }
    return mask;
}
} // Anonymous namespace

void Scheduler::CommandChunk::ExecuteAll(vk::CommandBuffer cmdbuf,
                                         vk::CommandBuffer upload_cmdbuf) {
    auto command = first;
    while (command != nullptr) {
        auto next = command->GetNext();
        command->Execute(cmdbuf, upload_cmdbuf);
        command->~Command();
        command = next;
    }
    submit = false;
    command_offset = 0;
    first = nullptr;
    last = nullptr;
}

Scheduler::Scheduler(const Device& device_, StateTracker& state_tracker_)
    : device{device_}, state_tracker{state_tracker_},
      master_semaphore{std::make_unique<MasterSemaphore>(device)},
      command_pool{std::make_unique<CommandPool>(*master_semaphore, device)} {

    AcquireNewChunk();
    AllocateWorkerCommandBuffer();
    worker_thread = std::jthread([this](std::stop_token token) { WorkerThread(token); });
}

Scheduler::~Scheduler() = default;

u64 Scheduler::Flush(VkSemaphore signal_semaphore, VkSemaphore wait_semaphore) {
    // When flushing, we only send data to the worker thread; no waiting is necessary.
    const u64 signal_value = SubmitExecution(signal_semaphore, wait_semaphore);
    AllocateNewContext();
    return signal_value;
}

void Scheduler::Finish(VkSemaphore signal_semaphore, VkSemaphore wait_semaphore) {
    // When finishing, we need to wait for the submission to have executed on the device.
    const u64 presubmit_tick = CurrentTick();
    SubmitExecution(signal_semaphore, wait_semaphore);
    Wait(presubmit_tick);
    AllocateNewContext();
}

void Scheduler::WaitWorker() {
    DispatchWork();

    // Ensure the queue is drained.
    {
        std::unique_lock ql{queue_mutex};
        event_cv.wait(ql, [this] { return work_queue.empty(); });
    }

    // Now wait for execution to finish.
    std::scoped_lock el{execution_mutex};
}

void Scheduler::WaitSerial(u64 serial) {
    if (serial == 0) {
        return;
    }

    std::unique_lock lock{serial_mutex};
    serial_cv.wait(lock, [this, serial] {
        return executed_serial.load(std::memory_order_acquire) >= serial;
    });
}

void Scheduler::DispatchWork() {
    if (chunk && !chunk->Empty()) {
        {
            std::scoped_lock ql{queue_mutex};
            work_queue.push(std::move(chunk));
            // Publish the chunk before the worker can pop it, so a presenter waiting for this
            // serial never observes an execution it has not been handed yet.
            issued_serial.fetch_add(1, std::memory_order_release);
        }
        event_cv.notify_all();
        AcquireNewChunk();
    }
}

void Scheduler::RequestRenderpass(const Framebuffer* framebuffer, bool general_layout,
                                  bool feedback_layout) {
    const VkRenderPass renderpass = feedback_layout ? framebuffer->FeedbackRenderPass()
                                                    : framebuffer->RenderPass(general_layout);
    const VkFramebuffer framebuffer_handle = framebuffer->Handle();
    const VkExtent2D render_area = framebuffer->RenderArea();
    if (renderpass == state.renderpass && framebuffer_handle == state.framebuffer &&
        render_area.width == state.render_area.width &&
        render_area.height == state.render_area.height) {
        return;
    }
    // The pass that is already open for this framebuffer defines its attachments at begin. Ending it
    // to reopen the loading variant would store the tile and read it straight back. Only keep it
    // when it is the layout variant this draw needs.
    const VkRenderPass open_clear_variant = feedback_layout
                                                ? framebuffer->FeedbackClearRenderPass()
                                                : framebuffer->ClearRenderPass(general_layout);
    if (state.renderpass_is_clear && framebuffer_handle == state.framebuffer &&
        state.renderpass == open_clear_variant &&
        render_area.width == state.render_area.width &&
        render_area.height == state.render_area.height) {
        return;
    }
    EndRenderPass();
    state.renderpass = renderpass;
    state.framebuffer = framebuffer_handle;
    state.render_area = render_area;
    state.renderpass_is_clear = false;
    state.framebuffer_ptr = framebuffer;
    state.general_layout = general_layout;
    state.feedback_layout = feedback_layout;
    current_pass_slot = std::make_shared<std::atomic<VkRenderPass>>(renderpass);
    ++render_pass_serial;

    // Log render pass begin
    if (GPU::Logging::IsActive() &&
        Settings::values.gpu_log_vulkan_calls.GetValue()) {
        const std::string render_pass_info = fmt::format(
            "renderArea={}x{}, numImages={}",
            render_area.width, render_area.height, framebuffer->NumImages());
        GPU::Logging::GPULogger::GetInstance().LogRenderPassBegin(render_pass_info);
    }

    Record([slot = current_pass_slot, framebuffer_handle, render_area](vk::CommandBuffer cmdbuf) {
        const VkRenderPassBeginInfo renderpass_bi{
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .pNext = nullptr,
            .renderPass = slot->load(std::memory_order_acquire),
            .framebuffer = framebuffer_handle,
            .renderArea =
                {
                    .offset = {.x = 0, .y = 0},
                    .extent = render_area,
                },
            .clearValueCount = 0,
            .pClearValues = nullptr,
        };
        cmdbuf.BeginRenderPass(renderpass_bi, VK_SUBPASS_CONTENTS_INLINE);
    });
}

bool Scheduler::RequestRenderpassClear(const Framebuffer* framebuffer,
                                      std::span<const VkClearValue> clear_values) {
    const VkRenderPass renderpass = framebuffer->ClearRenderPass();
    if (renderpass == VK_NULL_HANDLE || renderpass == framebuffer->RenderPass()) {
        return false;
    }
    const VkFramebuffer framebuffer_handle = framebuffer->Handle();
    const VkExtent2D render_area = framebuffer->RenderArea();
    if (renderpass == state.renderpass && framebuffer_handle == state.framebuffer) {
        // Already open: the clear-values of a begin that happened before this call do not describe
        // what the guest is asking for now, so the caller has to clear explicitly.
        return false;
    }
    EndRenderPass(framebuffer, true);
    state.renderpass = renderpass;
    state.framebuffer = framebuffer_handle;
    state.render_area = render_area;
    state.renderpass_is_clear = true;
    state.framebuffer_ptr = framebuffer;
    state.general_layout = false;
    state.feedback_layout = false;
    current_pass_slot = std::make_shared<std::atomic<VkRenderPass>>(renderpass);
    ++render_pass_serial;

    std::array<VkClearValue, 9> values{};
    const size_t count = std::min<size_t>(clear_values.size(), values.size());
    for (size_t index = 0; index < count; ++index) {
        values[index] = clear_values[index];
    }

    Record([slot = current_pass_slot, framebuffer_handle, render_area, values,
            count](vk::CommandBuffer cmdbuf) {
        const VkRenderPassBeginInfo renderpass_bi{
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .pNext = nullptr,
            .renderPass = slot->load(std::memory_order_acquire),
            .framebuffer = framebuffer_handle,
            .renderArea =
                {
                    .offset = {.x = 0, .y = 0},
                    .extent = render_area,
                },
            .clearValueCount = static_cast<u32>(count),
            .pClearValues = count != 0 ? values.data() : nullptr,
        };
        cmdbuf.BeginRenderPass(renderpass_bi, VK_SUBPASS_CONTENTS_INLINE);
    });
    return true;
}

void Scheduler::RequestOutsideRenderPassOperationContext() {
    EndRenderPass();
}

bool Scheduler::UpdateGraphicsPipeline(GraphicsPipeline* pipeline) {
    if (state.graphics_pipeline == pipeline) {
        if (pipeline && pipeline->UsesExtendedDynamicState() &&
            state.needs_state_enable_refresh) {
            state_tracker.InvalidateStateEnableFlag();
            state.needs_state_enable_refresh = false;
        }
        return false;
    }

    state.graphics_pipeline = pipeline;

    if (!pipeline) {
        return true;
    }

    if (!pipeline->UsesExtendedDynamicState()) {
        state.needs_state_enable_refresh = true;
    } else if (state.needs_state_enable_refresh) {
        state_tracker.InvalidateStateEnableFlag();
        state.needs_state_enable_refresh = false;
    }

    return true;
}

bool Scheduler::UpdateRescaling(bool is_rescaling) {
    if (state.rescaling_defined && is_rescaling == state.is_rescaling) {
        return false;
    }
    state.rescaling_defined = true;
    state.is_rescaling = is_rescaling;
    return true;
}

void Scheduler::WorkerThread(std::stop_token stop_token) {
    Common::SetCurrentThreadName("VulkanWorker");
#ifdef __ANDROID__
    Common::SetCurrentThreadPriority(Common::ThreadPriority::Critical);
#endif

    const auto TryPopQueue{[this](auto& work) -> bool {
        if (work_queue.empty()) {
            return false;
        }

        work = std::move(work_queue.front());
        work_queue.pop();
        event_cv.notify_all();
        return true;
    }};

    while (!stop_token.stop_requested()) {
        std::unique_ptr<CommandChunk> work;

        {
            std::unique_lock lk{queue_mutex};

            // Wait for work.
            event_cv.wait(lk, stop_token, [&] { return TryPopQueue(work); });

            // If we've been asked to stop, we're done.
            if (stop_token.stop_requested()) {
                return;
            }

            // Exchange lock ownership so that we take the execution lock before
            // the queue lock goes out of scope. This allows us to force execution
            // to complete in the next step.
            std::exchange(lk, std::unique_lock{execution_mutex});

            // Perform the work, tracking whether the chunk was a submission
            // before executing.
            const bool has_submit = work->HasSubmit();
            work->ExecuteAll(current_cmdbuf, current_upload_cmdbuf);

            // If the chunk was a submission, reallocate the command buffer.
            if (has_submit) {
                AllocateWorkerCommandBuffer();
            }
        }

        // The chunk (and any queue submission inside it) is now visible to the WSI. Publish the
        // serial so a presenter waiting on it can hand the frame to the display.
        {
            std::scoped_lock serial_lock{serial_mutex};
            executed_serial.fetch_add(1, std::memory_order_release);
        }
        serial_cv.notify_all();

        {
            std::scoped_lock rl{reserve_mutex};

            // Recycle the chunk back to the reserve.
            chunk_reserve.emplace_back(std::move(work));
        }
    }
}

void Scheduler::AllocateWorkerCommandBuffer() {
    current_cmdbuf = vk::CommandBuffer(command_pool->Commit(), device.GetDispatchLoader());
    current_cmdbuf.Begin({
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .pNext = nullptr,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
        .pInheritanceInfo = nullptr,
    });
    current_upload_cmdbuf = vk::CommandBuffer(command_pool->Commit(), device.GetDispatchLoader());
    current_upload_cmdbuf.Begin({
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .pNext = nullptr,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
        .pInheritanceInfo = nullptr,
    });
}

void Scheduler::RegisterUploadBuffer(VkBuffer buffer, vk::CommandBuffer upload_cmdbuf) {
    if (num_pending_upload_buffers == 0 && !use_global_upload_barrier) {
        // Queue submission order alone is not an execution dependency. Finish earlier submissions'
        // reads/writes before reusing their buffers, once per upload batch and outside render passes.
        static constexpr VkMemoryBarrier READ_BARRIER{
            .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
            .pNext = nullptr,
            .srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT,
            .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        };
        upload_cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                                     VK_PIPELINE_STAGE_TRANSFER_BIT, 0, READ_BARRIER);
    }
    if (use_global_upload_barrier) {
        return;
    }
    if (!device.IsMali()) {
        use_global_upload_barrier = true;
        return;
    }
    const std::span buffers{pending_upload_buffers.data(), num_pending_upload_buffers};
    if (std::ranges::find(buffers, buffer) != buffers.end()) {
        return;
    }
    if (num_pending_upload_buffers == pending_upload_buffers.size()) {
        // Further identities do not matter once submission uses the global fallback.
        use_global_upload_barrier = true;
        return;
    }
    pending_upload_buffers[num_pending_upload_buffers++] = buffer;
}

u64 Scheduler::SubmitExecution(VkSemaphore signal_semaphore, VkSemaphore wait_semaphore) {
    EndPendingOperations();
    InvalidateState();

    const u64 signal_value = master_semaphore->NextTick();
    RecordWithUploadBuffer([signal_semaphore, wait_semaphore, signal_value,
                            this](vk::CommandBuffer cmdbuf, vk::CommandBuffer upload_cmdbuf) {
        if (num_pending_upload_buffers != 0 || use_global_upload_barrier) {
            if (!use_global_upload_barrier) {
                std::array<VkBufferMemoryBarrier, MAX_TRACKED_UPLOAD_BUFFERS> barriers;
                for (size_t i = 0; i < num_pending_upload_buffers; ++i) {
                    barriers[i] = VkBufferMemoryBarrier{
                        .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
                        .pNext = nullptr,
                        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                        .dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                        .buffer = pending_upload_buffers[i],
                        .offset = 0,
                        .size = VK_WHOLE_SIZE,
                    };
                }
                upload_cmdbuf.PipelineBarrier(
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    vk::PIPELINE_STAGE_GRAPHICS_COMPUTE_TRANSFER |
                        VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                    0, vk::Span<VkMemoryBarrier>{},
                    vk::Span(barriers.data(), num_pending_upload_buffers),
                    vk::Span<VkImageMemoryBarrier>{});
            } else {
                static constexpr VkMemoryBarrier WRITE_BARRIER{
                    .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                    .pNext = nullptr,
                    .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                    .dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                };
                upload_cmdbuf.PipelineBarrier(
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    device.IsMali() ? (vk::PIPELINE_STAGE_GRAPHICS_COMPUTE_TRANSFER |
                                       VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT)
                                    : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0, WRITE_BARRIER);
            }
            num_pending_upload_buffers = 0;
            use_global_upload_barrier = false;
        }
        upload_cmdbuf.End();
        cmdbuf.End();

        if (on_submit) {
            on_submit();
        }

        std::scoped_lock lock{submit_mutex};
        switch (const VkResult result = master_semaphore->SubmitQueue(
                    cmdbuf, upload_cmdbuf, signal_semaphore, wait_semaphore, signal_value)) {
        case VK_SUCCESS:
            // Log successful queue submission
            if (GPU::Logging::IsActive() &&
                Settings::values.gpu_log_vulkan_calls.GetValue()) {
                GPU::Logging::GPULogger::GetInstance().LogVulkanCall(
                    "vkQueueSubmit", "", VK_SUCCESS);
            }
            break;
        case VK_ERROR_DEVICE_LOST:
            device.ReportLoss();
            [[fallthrough]];
        default:
            vk::Check(result);
            break;
        }
    });
    chunk->MarkSubmit();
    DispatchWork();
    return signal_value;
}

void Scheduler::AllocateNewContext() {
    // Enable counters once again. These are disabled when a command buffer is finished.
}

void Scheduler::InvalidateState() {
    // Everything bound into the current command stream is gone: a new command buffer was started,
    // or a blit rebound the dynamic state behind the renderer's back. Either way a cached
    // "still bound" answer is no longer true.
    ++render_pass_serial;
    state.graphics_pipeline = nullptr;
    state.rescaling_defined = false;
    state_tracker.InvalidateCommandBufferState();
}

void Scheduler::EndPendingOperations() {
    query_cache->CounterReset(VideoCommon::QueryType::ZPassPixelCount64);
    EndRenderPass();
}

void Scheduler::EndRenderPass(const Framebuffer* next_framebuffer, bool next_is_clear) {
        if (!state.renderpass) {
            return;
        }
        ++render_pass_serial;

        // The pass's next use is now known. When the next pass clears a target this pass wrote,
        // that target's writeback is dead: the clear discards it without reading. Choose the
        // DONT_CARE-store variant for exactly those attachments. Anything else (read, present,
        // load, or an unknown next use) keeps the storing variant the slot already holds.
        if (current_pass_slot && next_is_clear && next_framebuffer && state.framebuffer_ptr &&
            Settings::values.renderer_store_elision.GetValue()) {
            const u16 mask = StoreElideMask(*state.framebuffer_ptr, *next_framebuffer);
            if (mask != 0) {
                current_pass_slot->store(
                    state.framebuffer_ptr->RenderPassStoreElide(mask, state.general_layout,
                                                                state.feedback_layout,
                                                                state.renderpass_is_clear),
                    std::memory_order_release);
            }
        }

        query_cache->CounterClose(VideoCommon::QueryType::StreamingByteCount);

        // Log render pass end
        if (GPU::Logging::IsActive() &&
            Settings::values.gpu_log_vulkan_calls.GetValue()) {
            GPU::Logging::GPULogger::GetInstance().LogRenderPassEnd();
        }

        query_cache->CounterEnable(VideoCommon::QueryType::ZPassPixelCount64, false);
        query_cache->NotifySegment(false);

        Record([has_transform_feedback = device.IsExtTransformFeedbackSupported()](
                  vk::CommandBuffer cmdbuf) {
            // The render pass declares its own exit dependency (subpass 0 -> VK_SUBPASS_EXTERNAL),
            // which already makes every attachment write visible to all later commands. The
            // explicit GENERAL -> GENERAL barrier that used to follow EndRenderPass duplicated
            // that dependency, and on a tiler it forced a resolve/writeback of every attachment at
            // every pass end (up to 25 MiB at 1220x2712 for colour plus depth) for no pixels.
            cmdbuf.EndRenderPass();
            if (has_transform_feedback) {
                static constexpr VkMemoryBarrier XFB_OUTPUT_BARRIER{
                    .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                    .pNext = nullptr,
                    .srcAccessMask = VK_ACCESS_TRANSFORM_FEEDBACK_WRITE_BIT_EXT,
                    .dstAccessMask = VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT,
                };
                cmdbuf.PipelineBarrier(VK_PIPELINE_STAGE_TRANSFORM_FEEDBACK_BIT_EXT,
                                       VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                                       0, XFB_OUTPUT_BARRIER);
            }
        });

        state.renderpass = VkRenderPass{};
        state.renderpass_is_clear = false;
        state.framebuffer_ptr = nullptr;
        current_pass_slot.reset();
    }


void Scheduler::AcquireNewChunk() {
    std::scoped_lock rl{reserve_mutex};

    if (chunk_reserve.empty()) {
        // If we don't have anything reserved, we need to make a new chunk.
        chunk = std::make_unique<CommandChunk>();
    } else {
        // Otherwise, we can just take from the reserve.
        chunk = std::move(chunk_reserve.back());
        chunk_reserve.pop_back();
    }
}

} // namespace Vulkan
