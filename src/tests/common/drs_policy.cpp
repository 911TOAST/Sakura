// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Host-side unit tests for Common::DrsPolicy, the pure decision policy behind
// renderer_drs. Feeding it one present-to-present interval per frame must step
// the level only on sustained over/under-run, never outside [min, max], and
// must never move up above the caller's pick. No device required.
//
// Frame-time reference points: the default deadband is upscale at 14.17 ms and
// downscale at 19.17 ms, with 30 down-hang, 120 up-hang and 90 thermal-hang
// frames. The EMA is ema = (3*ema + frame) / 4, so a saturated sequence holds
// its value exactly and a mode change re-crosses a threshold only gradually.

#include <catch2/catch_test_macros.hpp>

#include "common/drs_policy.h"

namespace Common {

namespace {

constexpr s64 kOkNs = 16000000;   // 16.0 ms: inside the deadband once the EMA settles there.
constexpr s64 kFastNs = 13000000; // 13.0 ms: below the up deadband, drives upscale.
constexpr s64 kSlowNs = 21000000; // 21.0 ms: above the down deadband, drives downscale.

} // namespace

TEST_CASE("DrsPolicy stays at the reset level inside the deadband", "[common:drs]") {
    DrsPolicy policy;
    policy.Reset(3, 1, 5);
    for (int i = 0; i < 1000; ++i) {
        REQUIRE(policy.Tick(16000000) == 3);
    }
}

TEST_CASE("DrsPolicy steps down only after sustained over-run", "[common:drs]") {
    DrsPolicy policy;
    policy.Reset(3, 1, 5);
    // 29 slow frames must not step (downscale_hang_frames = 30); the EMA is already saturated
    // high after the first of them.
    for (int i = 0; i < 29; ++i) {
        REQUIRE(policy.Tick(kSlowNs) == 3);
    }
    // The 30th slow frame steps exactly one level.
    REQUIRE(policy.Tick(kSlowNs) == 2);
    // Return to the deadband for long enough for the EMA to settle (200 frames is far beyond
    // the ~10 needed to converge from 21 ms to 16 ms) and confirm the DOWN clock is disarmed.
    for (int i = 0; i < 200; ++i) {
        REQUIRE(policy.Tick(kOkNs) == 2);
    }
    // A fresh over-run: the first slow frames re-cross the threshold only through the EMA
    // (16 -> 17.25 -> 18.19 -> 18.89 -> 19.42 ms), so their over-count restarts late. 29 slow
    // frames land at most 26 counted over-frames: still no step.
    for (int i = 0; i < 29; ++i) {
        REQUIRE(policy.Tick(kSlowNs) == 2);
    }
    // Sixty more slow frames certainly crosses the hang; the level gets to exactly 1 (two
    // more steps need 30 counted frames each, and the second step is also the floor here).
    for (int i = 0; i < 60; ++i) {
        policy.Tick(kSlowNs);
    }
    REQUIRE(policy.Level() == 1);
}

TEST_CASE("DrsPolicy never steps below min_level", "[common:drs]") {
    DrsPolicy policy;
    policy.Reset(3, 1, 5);
    for (int i = 0; i < 100 * 30; ++i) {
        REQUIRE(policy.Tick(kSlowNs) >= 1);
    }
    REQUIRE(policy.Level() == 1);
}

TEST_CASE("DrsPolicy steps up only after sustained under-run and never above max", "[common:drs]") {
    DrsPolicy policy;
    policy.Reset(4, 1, 5);
    // upscale_hang_frames = 120: 119 fast frames must stay. The EMA is saturated low from the
    // first fast frame, so the count is exact.
    for (int i = 0; i < 119; ++i) {
        REQUIRE(policy.Tick(kFastNs) == 4);
    }
    REQUIRE(policy.Tick(kFastNs) == 5);
    // At the cap: more fast frames must not go further.
    for (int i = 0; i < 10 * 120; ++i) {
        REQUIRE(policy.Tick(kFastNs) <= 5);
    }
    REQUIRE(policy.Level() == 5);
}

TEST_CASE("DrsPolicy EMA ignores a single spike", "[common:drs]") {
    DrsPolicy policy;
    policy.Reset(3, 1, 5);
    // Steady state inside the deadband, then one huge hitch: the EMA jumps to 62 ms, the over
    // clock counts 1 of 30, and the following 16 ms frames only count up while the EMA decays
    // back under 19.17 ms (about ten more counts). Every step must be pre-empted.
    for (int i = 0; i < 200; ++i) {
        REQUIRE(policy.Tick(16000000) == 3);
    }
    REQUIRE(policy.Tick(200000000) == 3); // 200 ms hitch alone must not step down.
    for (int i = 0; i < 200; ++i) {
        REQUIRE(policy.Tick(16000000) == 3);
    }
}

TEST_CASE("DrsPolicy thermal headroom forces a single downstep after its hang", "[common:drs]") {
    DrsPolicy policy;
    policy.Reset(3, 1, 5);
    // Headroom at the limit (0.0 <= h <= 0.1) for 89 frames: no step, frame time is fine.
    for (int i = 0; i < 89; ++i) {
        REQUIRE(policy.Tick(kOkNs, 0.0f) == 3);
    }
    REQUIRE(policy.Tick(kOkNs, 0.0f) == 2);
    // Headroom clears: the clock resets and nothing steps further.
    for (int i = 0; i < 200; ++i) {
        REQUIRE(policy.Tick(kOkNs, 12.0f) == 2);
    }
    // Unavailable headroom (the large negative marker) must be ignored entirely.
    for (int i = 0; i < 200; ++i) {
        REQUIRE(policy.Tick(kOkNs, kThermalHeadroomUnavailable) == 2);
    }
    // Headroom can never push the level up, and the floor also holds.
    for (int i = 0; i < 400; ++i) {
        REQUIRE(policy.Tick(kOkNs, 0.0f) >= 1);
    }
    REQUIRE(policy.Level() == 1);
}

TEST_CASE("DrsPolicy Reset clamps its starting level into range", "[common:drs]") {
    DrsPolicy policy;
    policy.Reset(10, 1, 5);
    REQUIRE(policy.Level() == 5);
    policy.Reset(0, 2, 5);
    REQUIRE(policy.Level() == 2);
    // Tick with a nonsense interval changes nothing.
    REQUIRE(policy.Tick(0) == 2);
    REQUIRE(policy.Tick(-5) == 2);
}

} // namespace Common
