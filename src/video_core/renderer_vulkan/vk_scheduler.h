// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2019 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <functional>
#include <memory>
#include <span>
#include <thread>
#include <utility>
#include <queue>
#include <vector>

#include "common/alignment.h"
#include "common/common_types.h"
#include "common/polyfill_thread.h"
#include "common/settings.h"
#include "video_core/renderer_vulkan/vk_master_semaphore.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace VideoCommon {
template <typename Trait>
class QueryCacheBase;
}

namespace Vulkan {

class CommandPool;
class Device;
class Framebuffer;
class GraphicsPipeline;
class StateTracker;

struct QueryCacheParams;

/// The scheduler abstracts command buffer and fence management with an interface that's able to do
/// OpenGL-like operations on Vulkan command buffers.
class Scheduler {
public:
    explicit Scheduler(const Device& device, StateTracker& state_tracker);
    ~Scheduler();

    /// Sends the current execution context to the GPU.
    u64 Flush(VkSemaphore signal_semaphore = {}, VkSemaphore wait_semaphore = {});

    /// Sends the current execution context to the GPU and waits for it to complete.
    void Finish(VkSemaphore signal_semaphore = {}, VkSemaphore wait_semaphore = {});

    /// Waits for the worker thread to finish executing everything. After this function returns it's
    /// safe to touch worker resources.
    void WaitWorker();

    /// Serial of the last chunk handed to the worker. Lets another thread wait for recorded work
    /// to be executed without dispatching the render thread's in-progress chunk (what WaitWorker
    /// does, and it is not safe to call from anywhere but the render thread).
    [[nodiscard]] u64 IssuedSerial() const noexcept {
        return issued_serial.load(std::memory_order_acquire);
    }

    /// Blocks until the worker has executed every chunk queued up to \p serial. Safe to call from
    /// any thread; returns immediately when the serial has already been reached.
    void WaitSerial(u64 serial);

    /// True while the worker still has chunks it has not executed yet. Callers that only feed the
    /// worker to create overlap can skip dispatching: it already has work queued.
    [[nodiscard]] bool HasPendingWork() const noexcept {
        return issued_serial.load(std::memory_order_acquire) >
               executed_serial.load(std::memory_order_acquire);
    }

    /// Sends currently recorded work to the worker thread.
    void DispatchWork();

    /// Requests to begin a renderpass.
    void RequestRenderpass(const Framebuffer* framebuffer);

    /// Requests to begin the variant of the framebuffer's render pass whose attachments are
    /// defined at pass begin instead of loaded. Returns false when no such pass was begun, in which
    /// case the caller has to clear explicitly as usual.
    bool RequestRenderpassClear(const Framebuffer* framebuffer,
                                std::span<const VkClearValue> clear_values);

    /// Requests the current execution context to be able to execute operations only allowed outside
    /// of a renderpass.
    void RequestOutsideRenderPassOperationContext();

    /// Returns true when a render pass is currently active in the scheduler state.
    bool IsRenderPassActive() const {
        return state.renderpass != VK_NULL_HANDLE;
    }

    /// Update the pipeline to the current execution context.
    bool UpdateGraphicsPipeline(GraphicsPipeline* pipeline);

    /// Update the rescaling state. Returns true if the state has to be updated.
    bool UpdateRescaling(bool is_rescaling);

    /// Invalidates current command buffer state except for render passes
    void InvalidateState();

    /// Assigns the query cache.
    void SetQueryCache(VideoCommon::QueryCacheBase<QueryCacheParams>& query_cache_) {
        query_cache = &query_cache_;
    }

    // Registers a callback to perform on queue submission.
    void RegisterOnSubmit(std::function<void()>&& func) {
        on_submit = std::move(func);
    }

    /// Send work to a separate thread.
    template <typename T>
        requires std::is_invocable_v<T, vk::CommandBuffer, vk::CommandBuffer>
    void RecordWithUploadBuffer(T&& command) {
        if (chunk->Record(command)) {
            return;
        }
        DispatchWork();
        (void)chunk->Record(command);
    }

    template <typename T>
        requires std::is_invocable_v<T, vk::CommandBuffer, vk::CommandBuffer>
    void RecordUploadBuffer(VkBuffer destination, T&& command) {
        RecordWithUploadBuffer(
            [this, destination, command = std::forward<T>(command)](
                vk::CommandBuffer cmdbuf, vk::CommandBuffer upload_cmdbuf) {
                RegisterUploadBuffer(destination, upload_cmdbuf);
                command(cmdbuf, upload_cmdbuf);
            });
    }

    template <typename T>
        requires std::is_invocable_v<T, vk::CommandBuffer>
    void Record(T&& c) {
        this->RecordWithUploadBuffer(
            [command = std::move(c)](vk::CommandBuffer cmdbuf, vk::CommandBuffer) {
                command(cmdbuf);
            });
    }

    /// Returns the current command buffer tick.
    [[nodiscard]] u64 CurrentTick() const noexcept {
        return master_semaphore->CurrentTick();
    }

    /// Returns true when a tick has been triggered by the GPU.
    [[nodiscard]] bool IsFree(u64 tick) const noexcept {
        return master_semaphore->IsFree(tick);
    }

    /// Waits for the given GPU tick, optionally pacing frames.
    void Wait(u64 tick, double target_fps = 0.0) {
        if (tick > 0) {
            if (tick >= master_semaphore->CurrentTick()) {
                Flush();
            }
            master_semaphore->Wait(tick);
        }
        if (Settings::values.use_speed_limit.GetValue() && target_fps > 0.0) {
            auto now = std::chrono::steady_clock::now();
            if (last_target_fps != target_fps) {
                frame_interval = std::chrono::duration_cast<std::chrono::steady_clock::duration>(std::chrono::duration<double>(1.0 / target_fps));
                max_frame_count = static_cast<int>(0.1 * target_fps);
                last_target_fps = target_fps;
                frame_counter = 0;
                start_time = now;
            }
            frame_counter++;
            auto target_time = start_time + frame_interval * frame_counter;
            if (target_time >= now) {
                const auto sleep_time = target_time - now;
                constexpr auto spin_threshold = std::chrono::microseconds(250);
                if (sleep_time > std::chrono::milliseconds(1)) {
                    std::this_thread::sleep_for(sleep_time - spin_threshold);
                }
                while (std::chrono::steady_clock::now() < target_time) {
                    std::this_thread::yield();
                }
            } else if (frame_counter > max_frame_count) {
                frame_counter = 0;
                start_time = now;
            }
        }
    }

    /// Returns the master timeline semaphore.
    [[nodiscard]] MasterSemaphore& GetMasterSemaphore() const noexcept {
        return *master_semaphore;
    }

    std::mutex submit_mutex;

private:
    class Command {
    public:
        virtual ~Command() = default;

        virtual void Execute(vk::CommandBuffer cmdbuf, vk::CommandBuffer upload_cmdbuf) const = 0;

        Command* GetNext() const {
            return next;
        }

        void SetNext(Command* next_) {
            next = next_;
        }

    private:
        Command* next = nullptr;
    };

    template <typename T>
    class TypedCommand final : public Command {
    public:
        explicit TypedCommand(T&& command_) : command{std::move(command_)} {}
        ~TypedCommand() override = default;

        TypedCommand(TypedCommand&&) = delete;
        TypedCommand& operator=(TypedCommand&&) = delete;

        void Execute(vk::CommandBuffer cmdbuf, vk::CommandBuffer upload_cmdbuf) const override {

            command(cmdbuf, upload_cmdbuf);
        }

    private:
        T command;
    };

    class CommandChunk final {
    public:
        void ExecuteAll(vk::CommandBuffer cmdbuf, vk::CommandBuffer upload_cmdbuf);

        template <typename T>
        bool Record(T& command) {
            using FuncType = TypedCommand<T>;
            static_assert(sizeof(FuncType) < sizeof(data), "Lambda is too large");

            command_offset = Common::AlignUp(command_offset, alignof(FuncType));
            if (command_offset > sizeof(data) - sizeof(FuncType)) {
                return false;
            }
            Command* const current_last = last;
            last = new (data.data() + command_offset) FuncType(std::move(command));

            if (current_last) {
                current_last->SetNext(last);
            } else {
                first = last;
            }
            command_offset += sizeof(FuncType);
            return true;
        }

        void MarkSubmit() {
            submit = true;
        }

        bool Empty() const {
            return command_offset == 0;
        }

        bool HasSubmit() const {
            return submit;
        }

    private:
        Command* first = nullptr;
        Command* last = nullptr;

        size_t command_offset = 0;
        bool submit = false;
        alignas(std::max_align_t) std::array<u8, 0x8000> data{};
    };

    struct State {
        VkRenderPass renderpass{};
        VkFramebuffer framebuffer{};
        VkExtent2D render_area = {0, 0};
        GraphicsPipeline* graphics_pipeline = nullptr;
        bool is_rescaling = false;
        bool rescaling_defined = false;
        bool needs_state_enable_refresh = false;
        /// True while the open pass is the define-at-begin variant. Redraws into the same
        /// framebuffer must keep it, or the tile would be stored and loaded again for nothing.
        bool renderpass_is_clear = false;
    };

    void WorkerThread(std::stop_token stop_token);

    void AllocateWorkerCommandBuffer();

    void RegisterUploadBuffer(VkBuffer buffer, vk::CommandBuffer upload_cmdbuf);

    u64 SubmitExecution(VkSemaphore signal_semaphore, VkSemaphore wait_semaphore);

    void AllocateNewContext();

    void EndPendingOperations();

    void EndRenderPass();

    void AcquireNewChunk();

    const Device& device;
    StateTracker& state_tracker;

    std::unique_ptr<MasterSemaphore> master_semaphore;
    std::unique_ptr<CommandPool> command_pool;

    VideoCommon::QueryCacheBase<QueryCacheParams>* query_cache = nullptr;

    vk::CommandBuffer current_cmdbuf;
    vk::CommandBuffer current_upload_cmdbuf;

    std::unique_ptr<CommandChunk> chunk;
    std::function<void()> on_submit;

    State state;

    u32 num_renderpass_images = 0;
    std::array<VkImage, 9> renderpass_images{};
    std::array<VkImageSubresourceRange, 9> renderpass_image_ranges{};

    std::queue<std::unique_ptr<CommandChunk>> work_queue;
    std::vector<std::unique_ptr<CommandChunk>> chunk_reserve;
    // Written and consumed only on the scheduler worker thread. These let Mali use
    // resource-scoped barriers instead of flushing unrelated memory after each submit.
    static constexpr size_t MAX_TRACKED_UPLOAD_BUFFERS = 8;
    std::array<VkBuffer, MAX_TRACKED_UPLOAD_BUFFERS> pending_upload_buffers{};
    size_t num_pending_upload_buffers = 0;
    bool use_global_upload_barrier = false;
    std::mutex execution_mutex;
    std::mutex reserve_mutex;
    std::mutex queue_mutex;
    std::condition_variable_any event_cv;
    std::jthread worker_thread;

    /// Chunk accounting for WaitSerial. issued_serial is only advanced by the render thread,
    /// executed_serial only by the worker; both are observed by the present thread.
    std::atomic<u64> issued_serial{0};
    std::atomic<u64> executed_serial{0};
    std::mutex serial_mutex;
    std::condition_variable serial_cv;

    std::chrono::steady_clock::duration frame_interval{};
    std::chrono::steady_clock::time_point start_time{};
    double last_target_fps{};
    u64 max_frame_count{};
    u64 frame_counter{};
};

} // namespace Vulkan
