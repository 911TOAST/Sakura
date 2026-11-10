// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2020 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <algorithm>
#include <cstring>
#include <vector>

#include "common/alignment.h"
#include "common/common_types.h"
#include "common/div_ceil.h"
#include "common/assert.h"
#include "video_core/memory_manager.h"
#include "video_core/rasterizer_interface.h"

namespace VideoCommon {

template <typename T>
class DescriptorTable {
public:
    [[nodiscard]] bool Synchronize(GPUVAddr gpu_addr, u32 limit) noexcept {
        bool ret = !(current_gpu_addr == gpu_addr && current_limit == limit);
        if (ret) {
            Refresh(gpu_addr, limit);
        }
        return ret;
    }

    void Invalidate() noexcept {
        std::ranges::fill(read_descriptors, 0);
        // The cached span is a host pointer into the mapping that existed when it was resolved.
        // Invalidation is what the rasterizer calls when that mapping may have moved (image or
        // address space teardown), so drop it and let the next read resolve again.
        host_span = nullptr;
        host_span_resolved = false;
    }

    [[nodiscard]] std::pair<T, bool> Read(Tegra::MemoryManager const& gpu_memory, u32 index) noexcept {
        DEBUG_ASSERT(index <= current_limit);
        std::pair<T, bool> result;
        if (ResolveHostSpan(gpu_memory)) {
            // The whole live part of the table sits in one host mapping. Index it directly
            // instead of walking the guest page table for every binding of every draw.
            std::memcpy(std::addressof(result.first), host_span + static_cast<size_t>(index) * sizeof(T),
                        sizeof(T));
        } else {
            const GPUVAddr gpu_addr = current_gpu_addr + index * sizeof(T);
            gpu_memory.ReadBlockUnsafe(gpu_addr, std::addressof(result.first), sizeof(T));
        }
        if ((read_descriptors[index / 64] & (1ULL << (index % 64))) != 0) {
            result.second = result.first != descriptors[index];
        } else {
            read_descriptors[index / 64] |= 1ULL << (index % 64);
            result.second = true;
        }
        if (result.second) {
            descriptors[index] = result.first;
        }
        return result;
    }

    void Refresh(GPUVAddr gpu_addr, u32 limit) noexcept {
        current_gpu_addr = gpu_addr;
        current_limit = limit;
        host_span = nullptr;
        host_span_resolved = false;
        // Mario Brothership reallocates a lot of times, so use aggressive pre-alloc sizes
        // std::vector<T> by default uses quadratic growth, but that isn't even enough to satisfy brothership
        const size_t num_descriptors = ((limit + 0x80000) & (~0x7ffff)) + 1;
        size_t old_size = read_descriptors.size();
        read_descriptors.resize(Common::DivCeil(num_descriptors, 64U));
        old_size = (std::min)(old_size, read_descriptors.size());
        std::fill(read_descriptors.begin(), read_descriptors.begin() + old_size, 0);
        //
        descriptors.resize(num_descriptors);
    }

    std::vector<u64> read_descriptors;
    std::vector<T> descriptors;
    GPUVAddr current_gpu_addr{};
    u32 current_limit{};

private:
    /// Resolve, once per table definition, a host pointer covering the entries that Read can be
    /// asked for. Returns false when the table is not one contiguous, fully mapped host span, in
    /// which case Read falls back to the page-walking path.
    [[nodiscard]] bool ResolveHostSpan(Tegra::MemoryManager const& gpu_memory) noexcept {
        if (host_span_resolved) {
            return host_span != nullptr;
        }
        host_span_resolved = true;
        if (current_gpu_addr == 0) {
            return false;
        }
        const size_t span_bytes = (static_cast<size_t>(current_limit) + 1) * sizeof(T);
        host_span = gpu_memory.GetSpan(current_gpu_addr, span_bytes);
        return host_span != nullptr;
    }

    const u8* host_span{};
    bool host_span_resolved{};
};

} // namespace VideoCommon
