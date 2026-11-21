// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2021 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <mutex>
#include <atomic>
#include <vector>

#include "common/common_types.h"
#include "video_core/vulkan_common/vulkan_wrapper.h"

namespace Vulkan {

class Device;

class PipelineStatistics {
public:
    explicit PipelineStatistics(const Device& device_);
    void Collect(const Device& device, VkPipeline pipeline);
    void Report() const;
    void RecordCacheHit() noexcept;
    void RecordCacheMiss() noexcept;
    void RecordBuild() noexcept;
    void RecordDroppedDraw() noexcept;
    void ReportCache() const;

private:
    struct Stats {
        u64 code_size{};
        u64 register_count{};
        u64 sgpr_count{};
        u64 vgpr_count{};
        u64 branches_count{};
        u64 basic_block_count{};
    };
    mutable std::mutex mutex;
    std::vector<Stats> collected_stats;
    std::atomic<u64> cache_hits{};
    std::atomic<u64> cache_misses{};
    std::atomic<u64> cache_builds{};
    std::atomic<u64> dropped_draws{};
};

} // namespace Vulkan
