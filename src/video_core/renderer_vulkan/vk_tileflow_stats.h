// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>

#include "common/logging/log.h"
#include "common/settings.h"

namespace Vulkan {

/// Why the render thread asked for an outside-render-pass execution context. Each value counts one
/// render pass that had to be closed before the operation could run; on a tile-based GPU that
/// usually means a tile store plus a later reload, so these are the numbers the frame-graph work
/// tries to drive down.
enum class TileFlowBreak : u8 {
    Other,               // unspecified (call sites that have not been classified yet)
    ImageUpload,         // vkCmdCopyBufferToImage / staging upload for an image's contents
    ImageDownload,       // readback of an image (unavoidable outside-render-pass)
    ImageLayout,         // image layout transition outside a pass
    ComputePass,         // the renderer's own compute passes (blit, ASTC, unswizzle, ...)
    BlitImage,           // blit/colour-clear utility passes
    BufferCopy,          // buffer-cache copies with barrier=true
    PresentFilter,       // present-time filters (FXAA/FSR/SMAA/SGSR/ASR) and composition
    ResponseCount,
};

/// Lightweight per-renderer counters for the tile-flow work. Collection is unconditional (one
/// relaxed atomic add per event, far below frame budget); the periodic log dump is gated by the
/// renderer_tileflow_counters setting so logcat stays quiet by default. Everything here is
/// diagnostic only: behaviour never depends on these values.
struct TileFlowStats {
    std::atomic<u64> pass_begins{0};               // render passes opened
    std::atomic<u64> pass_breaks{0};               // passes closed early by an outside-pass op
    std::atomic<u64> breaks_by_reason[static_cast<size_t>(TileFlowBreak::ResponseCount)] = {};
    std::atomic<u64> clear_loadop_hits{0};         // guest clears realized as loadOp=CLEAR
    std::atomic<u64> clear_loadop_misses{0};       // guest clears that fell back to a clear command
    std::atomic<u64> store_elide_passes{0};        // passes whose store ops were elided
    std::atomic<u64> store_elided_attachments{0};  // sum of elided attachment stores (popcount)

    void AddBreak(TileFlowBreak reason) {
        pass_breaks.fetch_add(1, std::memory_order_relaxed);
        breaks_by_reason[static_cast<size_t>(reason)].fetch_add(1, std::memory_order_relaxed);
    }

    void AddStoreElide(u32 attachment_count) {
        store_elide_passes.fetch_add(1, std::memory_order_relaxed);
        store_elided_attachments.fetch_add(attachment_count, std::memory_order_relaxed);
    }

    /// Call once per emulated frame. Every kFramesPerDump frames, and only when the
    /// renderer_tileflow_counters setting is on, emit one line of running totals.
    void MaybeLogTick() {
        if (++tick_frames < kFramesPerDump) {
            return;
        }
        tick_frames = 0;
        if (!Settings::values.renderer_tileflow_counters.GetValue()) {
            return;
        }
        std::array<u64, static_cast<size_t>(TileFlowBreak::ResponseCount)> reasons{};
        for (size_t i = 0; i < reasons.size(); ++i) {
            reasons[i] = breaks_by_reason[i].load(std::memory_order_relaxed);
        }
        LOG_INFO(Render_Vulkan,
                 "[TileFlow] totals: frames={} pass_begins={} pass_breaks={} "
                 "breaks[other={} upload={} download={} layout={} compute={} blit={} bufcopy={} "
                 "present={}] clear_loadop[{} hits / {} misses] store_elide[{} passes / {} atts]",
                 tick_frames, pass_begins.load(std::memory_order_relaxed),
                 pass_breaks.load(std::memory_order_relaxed), reasons[0], reasons[1], reasons[2],
                 reasons[3], reasons[4], reasons[5], reasons[6], reasons[7],
                 clear_loadop_hits.load(std::memory_order_relaxed),
                 clear_loadop_misses.load(std::memory_order_relaxed),
                 store_elide_passes.load(std::memory_order_relaxed),
                 store_elided_attachments.load(std::memory_order_relaxed));
    }

private:
    static constexpr u64 kFramesPerDump = 600;
    u64 tick_frames = 0;
};

} // namespace Vulkan
