// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2021 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <mutex>
#include <ankerl/unordered_dense.h>

#include "video_core/surface.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Vulkan {

struct RenderPassKey {
    bool operator==(const RenderPassKey&) const noexcept = default;

    std::array<VideoCore::Surface::PixelFormat, 8> color_formats;
    VideoCore::Surface::PixelFormat depth_format;
    VkSampleCountFlagBits samples;
    /// Define the attachment instead of loading it at pass begin. The clear variant is used when
    /// the guest's first use of a render target is a clear that covers the whole surface: on a
    /// tiler the load would fetch every tile from DRAM and the clear would immediately discard it.
    bool color_clear{};
    bool depth_clear{};
    bool stencil_clear{};
    /// Use COLOR_ATTACHMENT_OPTIMAL / DEPTH_STENCIL_ATTACHMENT_OPTIMAL attachment references for
    /// the subpass while keeping the attachments' initial and final layout at GENERAL. The images
    /// still rest in GENERAL, so the texture cache does not need to know about this. Draws that
    /// sample the render target they write to need the GENERAL variant instead.
    bool optimal_layout{};
};

} // namespace Vulkan

namespace std {
template <>
struct hash<Vulkan::RenderPassKey> {
    [[nodiscard]] size_t operator()(const Vulkan::RenderPassKey& key) const noexcept {
        size_t value = static_cast<size_t>(key.depth_format) << 48;
        value ^= static_cast<size_t>(key.samples) << 52;
        for (size_t i = 0; i < key.color_formats.size(); ++i) {
            value ^= static_cast<size_t>(key.color_formats[i]) << (i * 6);
        }
        value ^= static_cast<size_t>(key.color_clear) << 58;
        value ^= static_cast<size_t>(key.depth_clear) << 59;
        value ^= static_cast<size_t>(key.stencil_clear) << 60;
        value ^= static_cast<size_t>(key.optimal_layout) << 61;
        return value;
    }
};
} // namespace std

namespace Vulkan {

class Device;

class RenderPassCache {
public:
    explicit RenderPassCache(const Device& device_);

    VkRenderPass Get(const RenderPassKey& key);

private:
    const Device* device{};
    ankerl::unordered_dense::map<RenderPassKey, vk::RenderPass> cache;
    std::mutex mutex;
};

} // namespace Vulkan
