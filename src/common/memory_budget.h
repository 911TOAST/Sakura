// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include "common/common_types.h"

namespace Common::MemoryBudget {

/// Total physical RAM of the device, 0 when the platform cannot report it.
[[nodiscard]] u64 TotalRamBytes();

/// Resident-set ceiling for this process.
///
/// The guest DRAM, the video caches, the JIT code caches and the UI all live inside this
/// number, so it is deliberately below the device RAM: Android, SurfaceFlinger, the GPU
/// driver and the audio service still need room. On an 8 GiB phone the target is 6 GiB;
/// smaller devices are scaled down. Returns 0 on platforms with no artificial cap.
[[nodiscard]] u64 ProcessBudgetBytes();

/// Ceiling for the emulated Switch DRAM backing.
///
/// The layout picker offers 4/6/8 GiB. Selecting 8 GiB reserved 8 GiB of guest DRAM on a
/// device that cannot hold it, which by itself accounts for the "8 GB" working set. This
/// caps the backing at the 6 GiB layout without changing the default 4 GiB layout. Returns
/// 0 when there is no cap.
[[nodiscard]] u64 MaxEmulatedDramBytes();

/// Ceiling shared by the texture cache, the buffer cache and the staging/stream pools.
/// Returns 0 when there is no extra cap.
[[nodiscard]] u64 CacheBudgetBytes();

/// Host JIT code-cache size. Never 0.
[[nodiscard]] u64 JitCodeCacheBytes();

/// Number of boot-time pipeline workers. 0 means "use the backend default".
[[nodiscard]] u32 BootPipelineWorkerCount();

} // namespace Common::MemoryBudget