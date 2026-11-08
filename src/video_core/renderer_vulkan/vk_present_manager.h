// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <boost/container/deque.hpp>

#include "common/common_types.h"
#include "common/polyfill_thread.h"
#include "video_core/vulkan_common/vulkan_memory_allocator.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Core::Frontend {
class EmuWindow;
} // namespace Core::Frontend

namespace Vulkan {

class Device;
class Scheduler;
class Swapchain;

struct Frame {
    u32 width{};
    u32 height{};
    vk::Image image;
    vk::ImageView image_view;
    vk::Framebuffer framebuffer;
    vk::CommandBuffer cmdbuf;
    vk::Semaphore render_ready;
    vk::Fence present_done;
    /// The frame is a swapchain image handed out by GetRenderFrame(). No intermediate image,
    /// no copy and no free queue are involved; Present() hands the image straight to the WSI.
    bool direct_present{};
    /// Image acquired for this frame. Presentation reads it from the frame instead of the
    /// swapchain so a queued present can never pick up the index of a newer frame.
    u32 swapchain_image_index{};
    /// Scheduler chunk serial of the submission that renders into this frame. The WSI may only be
    /// asked to wait on render_ready once that submission has been issued.
    u64 present_serial{};
};

class PresentManager {
public:
    PresentManager(const vk::Instance& instance,
                   Core::Frontend::EmuWindow& render_window,
                   const Device& device,
                   MemoryAllocator& memory_allocator,
                   Scheduler& scheduler,
                   Swapchain& swapchain,
                   vk::SurfaceKHR& surface);
    ~PresentManager();

    /// Returns the last used presentation frame
    Frame* GetRenderFrame();

    /// Pushes a frame for presentation
    void Present(Frame* frame);

    /// Recreates the present frame to match the provided parameters.
    /// Returns the frame to use. Direct-present frame recreation rebuilds every per-image
    /// view/framebuffer, so callers should use the returned pointer.
    Frame* RecreateFrame(Frame* frame, u32 width, u32 height, VkFormat image_view_format,
                         VkRenderPass rd);

    /// Waits for the present thread to finish presenting all queued frames.
    void WaitPresent();

    /// True when the renderer composites directly into the acquired swapchain image.
    [[nodiscard]] bool UsesDirectPresent() const noexcept {
        return use_direct_present;
    }

    /// Binary semaphore the render submission must wait on before touching the acquired image.
    [[nodiscard]] VkSemaphore GetPresentWaitSemaphore() const;

private:
    void PresentThread(std::stop_token token);

    /// Hands a direct-present frame to the WSI. Always runs on the render thread that acquired the
    /// image, because Android surface handles are bound to the thread that touched them.
    void PresentDirect(Frame* frame);

    void CopyToSwapchain(Frame* frame);

    void CopyToSwapchainImpl(Frame* frame);

    void RecreateSwapchain(Frame* frame);

    void RecreateSwapchainDirect(u32 width, u32 height);

    void ResetDirectFrames();

    void SetImageCount();

private:
    const vk::Instance& instance;
    Core::Frontend::EmuWindow& render_window;
    const Device& device;
    MemoryAllocator& memory_allocator;
    Scheduler& scheduler;
    Swapchain& swapchain;
    vk::SurfaceKHR& surface;
    vk::CommandPool cmdpool;
    std::vector<Frame> frames;
    boost::container::deque<Frame*> present_queue;
    boost::container::deque<Frame*> free_queue;
    std::condition_variable_any frame_cv;
    std::condition_variable free_cv;
    std::mutex swapchain_mutex;
    std::mutex queue_mutex;
    std::mutex free_mutex;
    std::jthread present_thread;
    std::atomic<bool> direct_recreate_pending{false};
    bool blit_supported;
    bool use_direct_present;
    bool use_present_thread;
    std::size_t image_count{};
};

} // namespace Vulkan
