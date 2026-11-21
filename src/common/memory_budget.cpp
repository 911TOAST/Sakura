// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/memory_budget.h"

#include <algorithm>

#include "common/literals.h"
#include "common/memory_detect.h"

namespace Common::MemoryBudget {

namespace {
using namespace Common::Literals;

/// Smallest process budget worth planning for. Below this the guest alone does not fit.
[[maybe_unused]] constexpr u64 MIN_PROCESS_BUDGET = 3_GiB;

/// Smallest amount of guest DRAM the HOS kernel can boot with.
[[maybe_unused]] constexpr u64 MIN_EMULATED_DRAM = 4_GiB;
} // namespace

u64 TotalRamBytes() {
    return GetMemInfo().TotalPhysicalMemory;
}

u64 ProcessBudgetBytes() {
#if defined(__ANDROID__)
    const u64 ram = TotalRamBytes();
    if (ram == 0) {
        // No sysinfo: assume the reference device rather than leaving the caches unbounded.
        return 6_GiB;
    }
    // Leave 2 GiB for Android and the services that stay resident while a game runs.
    u64 budget = ram > 2_GiB ? ram - 2_GiB : MIN_PROCESS_BUDGET;
    budget = (std::min)(budget, 6_GiB);
    return (std::max)(budget, MIN_PROCESS_BUDGET);
#else
    return 0;
#endif
}

u64 MaxEmulatedDramBytes() {
#if defined(__ANDROID__)
    const u64 process_budget = ProcessBudgetBytes();
    const u64 cap = (std::min)(6_GiB, process_budget);
    // Never go below the 4 GiB layout: the kernel cannot boot with less.
    return (std::max)(cap, MIN_EMULATED_DRAM);
#else
    return 0;
#endif
}

u64 CacheBudgetBytes() {
#if defined(__ANDROID__)
    const u64 ram = TotalRamBytes();
    // Bigger devices keep the previous behaviour: the caches size themselves off the system-RAM
    // share and the user's slider, not off this process budget.
    if (ram > 8_GiB) {
        return 0;
    }
    // Roughly one sixth of the process budget. On the 6 GiB target that is about 1 GiB for
    // the texture cache, the buffer cache and the staging pools combined, which is what
    // leaves room for the guest's 4 GiB and the OS underneath 6 GiB.
    return ProcessBudgetBytes() / 6;
#else
    return 0;
#endif
}

u64 JitCodeCacheBytes() {
#if defined(__ANDROID__)
    // The JIT code cache is pure host RAM. The desktop 128 MiB per backend is affordable on
    // a workstation and is not on a 6 GiB phone; the LRU still keeps the hot blocks resident.
    return 64_MiB;
#else
    return 128_MiB;
#endif
}

u32 BootPipelineWorkerCount() {
#if defined(__ANDROID__)
    // Boot compiles every cached pipeline before the first frame. Cap the pool so the compile
    // spike cannot exceed the memory the game itself is about to use.
    return 4;
#else
    return 0;
#endif
}

} // namespace Common::MemoryBudget