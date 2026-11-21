// SPDX-FileCopyrightText: Copyright 2026 Lemon Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Port of Lemon-Project's FrameGen, restricted to Lemon's native frame generation (Lemon FG).
#pragma once

#include <optional>

#include "common/common_types.h"
#include "video_core/renderer_vulkan/present/lemon_fg.h"
#include "video_core/vulkan_common/vulkan_memory_allocator.h"

namespace Vulkan {

class Device;
class Scheduler;
struct Frame;

/// Drives LemonFgChain from the presentation path: copies each presented frame into the chain
/// input, analyses it, and generates interpolated frames for the caller to present.
class FrameGen {
public:
    explicit FrameGen(MemoryAllocator& memory_allocator, Scheduler& scheduler);
    ~FrameGen();

    /// Analyse the freshly presented frame (copy + shared passes); the next
    /// [GeneratedFrameCount] Present calls should present generated frames first.
    void Process(const Device& device, Frame* frame, VkFormat format);

    /// How many generated frames to present before the real frame of this cycle.
    [[nodiscard]] size_t GeneratedFrameCount() const;

    /// Dispatches the generate pass into a presentation frame for the given generation.
    void GenerateInto(const Device& device, Frame* destination, size_t generation);

private:
    void Rebuild(const Device& device, VkExtent2D extent, VkFormat format);
    void CopyPresentedFrame(vk::CommandBuffer cmdbuf, VkImage source, LemonFgImage& destination,
                            VkExtent2D extent);

    MemoryAllocator& memory_allocator;
    Scheduler& scheduler;

    std::optional<LemonFgChain> chain;
    u64 frame_count{};
    u64 last_count{};
    size_t last_generations{};
    size_t warm_streak{};
    bool generated{};
    bool unavailable{};
    bool built{};
    VkExtent2D built_extent{};
    VkFormat built_format{VK_FORMAT_UNDEFINED};
};

} // namespace Vulkan
