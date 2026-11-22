// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <algorithm>

#include "common/common_types.h"

namespace Common {

/// Pure decision policy for dynamic resolution scaling (DRS). No Vulkan, no
/// settings access: the caller feeds it one present-to-present interval per
/// frame plus the thermal headroom (when the OS can provide one), and it
/// answers with an integer resolution level. The renderer maps the level to
/// its own scale list and applies it through the existing live-resolution
/// path. Deliberately written to change nothing until its thresholds are
/// actually crossed, so idle/fast configurations are a no-op.
///
/// Design notes for a tile-based GPU at fixed tile clock:
/// - Downsteps are fast (a scene that misses the target keeps missing), upsteps
///   are slow (recover less aggressively to avoid oscillating).
/// - Short spikes are ignored: the decision looks at an EMA of frame times, so
///   a single loading hitch does not drop the resolution.
/// - Thermal headroom near zero forces a downstep only, once per cooldown: the
///   GPU is about to throttle and dropping pixels now is cheaper than huge
///   frame spikes later. Unavailable headroom (reported by callers as a large
///   negative value) is ignored entirely.

struct DrsConfig {
    s64 target_frame_ns = 16666666LL;   // nominal frame pace
    s64 downscale_frame_ns = 19166666;  // target * ~1.15: sustained over-run
    s64 upscale_frame_ns = 14166666;    // target * ~0.85: sustained under-run
    int min_level = 0;                  // never step below this
    int max_level = 1;                  // never step above this (callers cap at the user's pick)
    u32 downscale_hang_frames = 0;          // extra frames the EMA must stay over before stepping down
    u32 upscale_hang_frames = 0;            // same for up
    u32 thermal_hang_frames = 0;            // same for the thermal trigger
};

constexpr DrsConfig MakeDefaultDrsConfig(int min_level, int max_level) {
    DrsConfig cfg;
    cfg.min_level = min_level;
    cfg.max_level = max_level;
    cfg.downscale_hang_frames = 30;
    cfg.upscale_hang_frames = 120;
    cfg.thermal_hang_frames = 90;
    return cfg;
}

/// Feedback value when the platform has no thermal headroom source.
constexpr float kThermalHeadroomUnavailable = -1000.0f;
/// Headroom (degrees Celsius of forecast headroom) at or below which the GPU is
/// considered about to throttle. AThermal_getThermalHeadroom returns values
/// quantized to 0.1, so this triggers on the first non-zero-low bucket.
constexpr float kThermalHeadroomLimit = 0.1f;

class DrsPolicy {
public:
    /// \p level the resolution level DRS starts at, clamped to [\p min_level,
    /// \p max_level]; it never leaves that range afterwards.
    void Reset(int level, int min_level, int max_level) {
        cfg = MakeDefaultDrsConfig(min_level, max_level);
        current = std::clamp(level, cfg.min_level, cfg.max_level);
        ema_ns = 0;
        has_sample = false;
        over_frames = 0;
        under_frames = 0;
        thermal_frames = 0;
    }

    /// Feed one frame's present interval. Returns the (possibly unchanged)
    /// current resolution level.
    int Tick(s64 frame_ns, float thermal_headroom = kThermalHeadroomUnavailable) {
        if (frame_ns <= 0) {
            return current;
        }
        ema_ns = has_sample ? (ema_ns * 3 + frame_ns) / 4 : frame_ns;
        has_sample = true;

        // Thermal pressure acts fast and only downwards: if the forecast says the part is at its
        // limit, drop one level now. Sustained cooling then resumes normal pacing. Headroom 0.0
        // counts (the forecast is quantized to 0.1, so exactly 0 means "no headroom left");
        // anything above the limit as well as the unavailable marker resets the clock.
        if (thermal_headroom >= 0.0f && thermal_headroom <= kThermalHeadroomLimit) {
            if (++thermal_frames >= cfg.thermal_hang_frames) {
                thermal_frames = 0;
                return StepDown();
            }
        } else {
            thermal_frames = 0;
        }

        // Frame-time pacing: downstep after the over-run hangs, upstep after the under-run hangs.
        if (ema_ns > cfg.downscale_frame_ns) {
            ++over_frames;
            under_frames = 0;
            if (over_frames >= cfg.downscale_hang_frames) {
                return StepDown();
            }
        } else if (ema_ns < cfg.upscale_frame_ns) {
            ++under_frames;
            over_frames = 0;
            if (under_frames >= cfg.upscale_hang_frames) {
                under_frames = 0;
                if (current < cfg.max_level) {
                    ++current;
                    over_frames = 0;
                }
            }
        } else {
            // Inside the deadband: both clocks reset, nothing happens.
            over_frames = 0;
            under_frames = 0;
        }
        return current;
    }

    [[nodiscard]] int Level() const noexcept {
        return current;
    }

private:
    int StepDown() {
        over_frames = 0;
        under_frames = 0;
        thermal_frames = 0;
        current = std::max(cfg.min_level, current - 1);
        return current;
    }

    DrsConfig cfg{};
    int current = 0;
    s64 ema_ns = 0;
    bool has_sample = false;
    u32 over_frames = 0;
    u32 under_frames = 0;
    u32 thermal_frames = 0;
};

} // namespace Common
