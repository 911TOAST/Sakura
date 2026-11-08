// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2019 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <vector>

#include "common/common_types.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Layout {
struct FramebufferLayout;
}

namespace Vulkan {

class Device;
class Scheduler;

class Swapchain {
public:
    explicit Swapchain(
        VkSurfaceKHR_T* surface,
        const Device& device,
        Scheduler& scheduler,
        u32 width,
        u32 height);
    ~Swapchain();

    /// Creates (or recreates) the swapchain with a given size.
    void Create(
        VkSurfaceKHR_T* surface,
        u32 width,
        u32 height);

    /// Acquires the next image in the swapchain, waits as needed.
    ///
    /// \param wait_previous_work When false, the GPU timeline wait for this image's previous
    ///        frame is skipped. Only callers whose acquire already proves the image is idle
    ///        (the zero-copy path presents through the WSI, which pins the image until the GPU
    ///        has finished with it) may pass false. Frame pacing still applies.
    bool AcquireNextImage(bool wait_previous_work = true);

    /// Presents the rendered image to the swapchain.
    void Present(VkSemaphore render_semaphore);

    /// Presents a specific acquired image. Used when presentation runs on its own thread: the
    /// render thread may already have acquired the next image, so reading image_index here would
    /// present the wrong one.
    void Present(VkSemaphore render_semaphore, u32 presented_image);

    /// Returns true when the swapchain needs to be recreated.
    bool NeedsRecreation() const {
        return IsSubOptimal() || NeedsPresentModeUpdate();
    }

    /// Returns true when the swapchain is outdated.
    bool IsOutDated() const {
        return is_outdated;
    }

    /// Returns true when the swapchain is suboptimal.
    bool IsSubOptimal() const {
        return is_suboptimal;
    }

    VkExtent2D GetSize() const {
        return extent;
    }

    std::size_t GetImageCount() const {
        return image_count;
    }

    std::size_t GetImageIndex() const {
        return image_index;
    }

    std::size_t GetFrameIndex() const {
        return frame_index;
    }

    VkImage GetImageIndex(std::size_t index) const {
        return images[index];
    }

    VkImage CurrentImage() const {
        return images[image_index];
    }

    VkFormat GetImageViewFormat() const {
        return image_view_format;
    }

    VkFormat GetImageFormat() const {
        return surface_format.format;
    }

    VkSemaphore CurrentPresentSemaphore() const {
        return acquired_present_semaphore;
    }

    VkSemaphore CurrentRenderSemaphore() const {
        return *render_semaphores[image_index];
    }

    u32 GetWidth() const {
        return width;
    }

    u32 GetHeight() const {
        return height;
    }

    VkExtent2D GetExtent() const {
        return extent;
    }

private:
    void CreateSwapchain(const VkSurfaceCapabilitiesKHR& capabilities);
    void CreateSemaphores();
    void CreateImageViews();

    void Destroy();

    bool NeedsPresentModeUpdate() const;

    VkSurfaceKHR_T* surface;

    const Device& device;
    Scheduler& scheduler;

    vk::SwapchainKHR swapchain;

    std::size_t image_count{};
    std::vector<VkImage> images;
    std::vector<u64> resource_ticks;
    std::vector<vk::Semaphore> present_semaphores;
    std::vector<vk::Semaphore> render_semaphores;

    u32 width;
    u32 height;

    u32 image_index{};
    u32 frame_index{};
    /// Semaphore handed to vkAcquireNextImageKHR by the last successful acquire. The render
    /// submission waits on it, so it must not be derived from frame_index after the fact.
    VkSemaphore acquired_present_semaphore{};

    VkFormat image_view_format{};
    VkExtent2D extent{};
    VkPresentModeKHR present_mode{};
    VkSurfaceFormatKHR surface_format{};
    bool has_imm{false};
    bool has_mailbox{false};
    bool has_fifo_relaxed{false};

    bool is_outdated{};
    bool is_suboptimal{};
};

} // namespace Vulkan
