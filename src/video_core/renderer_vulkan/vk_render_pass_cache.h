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
    /// Feedback variant of the optimal layout: colour attachments use
    /// ATTACHMENT_FEEDBACK_LOOP_OPTIMAL_EXT during the subpass so draws that sample their own
    /// target keep compression. Depth stays GENERAL because it may be the sampled surface too.
    /// Requires VK_EXT_attachment_feedback_loop_layout; only set together with optimal_layout.
    bool feedback_loop{};
    /// AFBC-native variant: attachments rest in COLOR_ATTACHMENT_OPTIMAL / DEPTH_STENCIL_ATTACHMENT_OPTIMAL
    /// at pass begin AND end (initialLayout == finalLayout == the optimal attachment layout) instead
    /// of round-tripping through GENERAL, so Mali keeps lossless AFBC active across pass boundaries.
    /// The texture cache must transition the attachments into these layouts before the pass begins.
    bool afbc_layout{};
    /// Per-attachment store elision: bit k set means attachment k (in description order, colour
    /// attachments first then depth) uses STORE_OP_DONT_CARE because the next pass re-clears it.
    /// loadOp/storeOp do not affect render pass compatibility, so this variant shares pipelines and
    /// framebuffers with the storing variant of the same key.
    u16 store_elide_mask{};
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
        value ^= static_cast<size_t>(key.feedback_loop) << 62;
        value ^= static_cast<size_t>(key.afbc_layout) << 4;
        value ^= static_cast<size_t>(key.store_elide_mask) * 0x9E3779B97F4A7C15ULL;
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
