// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Host-side unit tests for the clear -> render-pass loadOp derivation used by
// RasterizerVulkan::Clear. The derivation is a pure function over plain data,
// so every case that the on-device clear path can hit is exercised here
// without a device.

#include <catch2/catch_test_macros.hpp>

#include "video_core/renderer_vulkan/vk_clear_derive.h"

using namespace Vulkan;

namespace {

constexpr ClearFbShape Shape(u32 colors, bool depth, bool stencil, bool single_layer = true) {
    return ClearFbShape{
        .num_color_buffers = colors,
        .has_depth = depth,
        .has_stencil = stencil,
        .single_layer = single_layer,
    };
}

constexpr ClearRequest FullArea(u32 area_w = 800, u32 area_h = 600) {
    return ClearRequest{
        .rect_x = 0,
        .rect_y = 0,
        .rect_w = area_w,
        .rect_h = area_h,
        .area_w = area_w,
        .area_h = area_h,
        .layer0_single = true,
    };
}

constexpr ClearRequest LegacyCase() {
    // One colour buffer, depth+stencil, no scissor, full-coverage, all channels, all aspects.
    ClearRequest req = FullArea();
    req.use_color = true;
    req.clear_r = req.clear_g = req.clear_b = req.clear_a = true;
    req.use_depth = true;
    req.use_stencil = true;
    req.rt = 0;
    return req;
}

} // Anonymous namespace

TEST_CASE("Legacy all-aspect clear keeps taking the clear variant", "[clear_derive]") {
    const ClearFbShape shape = Shape(1, true, true);
    const ClearRequest req = LegacyCase();
    const ClearLoadOpPlan plan = DeriveClearLoadOp(req, shape);

    REQUIRE(plan.use_clear_variant);
    REQUIRE(PlanIsLegacyAllAspect(plan, shape));
    REQUIRE(plan.color_mask == 0x1);
    REQUIRE(plan.depth_clear);
    REQUIRE(plan.stencil_clear);
    REQUIRE(plan.value_color_index == 0);
}

TEST_CASE("Legacy conditions that were rejected still are (or are only widened by extension)",
          "[clear_derive]") {
    const ClearFbShape shape = Shape(1, true, true);

    // Partial rect: never eligible.
    {
        ClearRequest req = LegacyCase();
        req.rect_w = 400;
        REQUIRE_FALSE(DeriveClearLoadOp(req, shape).use_clear_variant);
    }
    // Layered attachment on the framebuffer: never eligible.
    {
        const ClearFbShape layered = Shape(1, true, true, false);
        REQUIRE_FALSE(DeriveClearLoadOp(LegacyCase(), layered).use_clear_variant);
    }
    // Layer index other than zero: never eligible.
    {
        ClearRequest req = LegacyCase();
        req.layer0_single = false;
        REQUIRE_FALSE(DeriveClearLoadOp(req, shape).use_clear_variant);
    }
    // Channel subset (guest clears only R): colour aspect must keep LOAD.
    {
        ClearRequest req = LegacyCase();
        req.clear_a = false;
        const ClearLoadOpPlan plan = DeriveClearLoadOp(req, shape);
        REQUIRE(plan.color_mask == 0);
        // Depth/stencil may still be defined at begin; colour must not.
        REQUIRE(plan.use_clear_variant || plan.depth_clear || plan.stencil_clear);
    }
    // RT index beyond the framebuffer's colour attachments: colour aspect gone.
    {
        ClearRequest req = LegacyCase();
        req.rt = 1; // shape has num_color_buffers == 1
        const ClearLoadOpPlan plan = DeriveClearLoadOp(req, shape);
        REQUIRE(plan.color_mask == 0);
        REQUIRE(plan.depth_clear);
        REQUIRE(plan.stencil_clear);
    }
    // Nothing cleared to full coverage: no clear variant at all.
    {
        ClearRequest req = FullArea();
        const ClearLoadOpPlan plan = DeriveClearLoadOp(req, shape);
        REQUIRE_FALSE(plan.use_clear_variant);
    }
}

TEST_CASE("Zero-sized render area never proposes a clear variant", "[clear_derive]") {
    ClearRequest req = LegacyCase();
    req.area_w = 0;
    req.area_h = 0;
    req.rect_w = 0;
    req.rect_h = 0;
    REQUIRE_FALSE(DeriveClearLoadOp(req, Shape(1, true, true)).use_clear_variant);
}

TEST_CASE("Extension: single RT clear of an MRT target", "[clear_derive]") {
    // 3 colour buffers + depth, guest clears RT 1 only (all channels, full area).
    ClearRequest req = FullArea();
    req.use_color = true;
    req.clear_r = req.clear_g = req.clear_b = req.clear_a = true;
    req.use_depth = false;
    req.rt = 1;
    const ClearLoadOpPlan plan = DeriveClearLoadOp(req, Shape(3, true, false));

    REQUIRE(plan.use_clear_variant);
    REQUIRE(plan.color_mask == (1 << 1));
    REQUIRE_FALSE(plan.depth_clear);
    // RT0 and RT2 keep LOAD, so their contents survive exactly as on the
    // explicit-clear path.
    REQUIRE(PlanIsLegacyAllAspect(plan, Shape(3, true, false)) == false);
}

TEST_CASE("Extension: depth clear on a depth+stencil target keeps stencil loaded", "[clear_derive]") {
    ClearRequest req = FullArea();
    req.use_color = false;
    req.use_depth = true;
    req.use_stencil = false;
    req.rt = 0;
    const ClearFbShape shape = Shape(1, true, true);
    const ClearLoadOpPlan plan = DeriveClearLoadOp(req, shape);

    REQUIRE(plan.use_clear_variant);
    REQUIRE(plan.color_mask == 0);
    REQUIRE(plan.depth_clear);
    REQUIRE_FALSE(plan.stencil_clear);
}

TEST_CASE("Extension: scissored clear whose clamped rect covers the whole area", "[clear_derive]") {
    // The legacy gate rejected use_scissor unconditionally; the clamped rect is what decides.
    ClearRequest req = LegacyCase(); // rect == area already, as if clamped from a bigger scissor
    const ClearFbShape shape = Shape(1, true, true);
    const ClearLoadOpPlan plan = DeriveClearLoadOp(req, shape);
    REQUIRE(PlanIsLegacyAllAspect(plan, shape));
}

TEST_CASE("Aspects requested but absent on the framebuffer are skipped, not fabricated", "[clear_derive]") {
    // Guest clears depth+stencil but the target is depth-only.
    ClearRequest req = FullArea();
    req.use_depth = true;
    req.use_stencil = true;
    const ClearFbShape depth_only = Shape(1, true, false);
    const ClearLoadOpPlan plan = DeriveClearLoadOp(req, depth_only);

    REQUIRE(plan.use_clear_variant);
    REQUIRE(plan.depth_clear);
    REQUIRE_FALSE(plan.stencil_clear);
}

TEST_CASE("Colour-only framebuffer full clears still pass the legacy predicate", "[clear_derive]") {
    // Legacy gate: clears_all_aspects compares against the framebuffer's aspects,
    // so a full-area colour clear on a colour-only target took the clear variant.
    ClearRequest req = LegacyCase();
    const ClearFbShape color_only = Shape(1, false, false);
    const ClearLoadOpPlan plan = DeriveClearLoadOp(req, color_only);

    REQUIRE(plan.use_clear_variant);
    REQUIRE(plan.color_mask == 0x1);
    REQUIRE_FALSE(plan.depth_clear);
    REQUIRE_FALSE(plan.stencil_clear);
    REQUIRE(PlanIsLegacyAllAspect(plan, color_only));
}

TEST_CASE("Multiple RTs with the legacy checker never over-claims supersets", "[clear_derive]") {
    // Legacy gate required NumColorBuffers == 1; on a 3-RT target the legacy path
    // never hit. PlanIsLegacyAllAspect only passes when the clear defines exactly
    // all present aspects.
    ClearRequest req = LegacyCase();
    req.rt = 2;
    const ClearLoadOpPlan plan = DeriveClearLoadOp(req, Shape(3, true, true));
    REQUIRE(plan.color_mask == (1 << 2)); // extension allows it
    REQUIRE_FALSE(PlanIsLegacyAllAspect(plan, Shape(3, true, true)));
}

TEST_CASE("PartialClearSafe rejects clear requests a begin-clear cannot express",
          "[clear_derive]") {
    // Colour channel subset with everything else full: a begin-clear would write all four
    // channels, but the guest asked for a subset, so the explicit path must run.
    {
        ClearRequest req = LegacyCase();
        req.clear_a = false;
        REQUIRE_FALSE(PartialClearSafe(req));
    }
    // Masked stencil write: a begin-clear has no write mask, so the masked blit path must run.
    {
        ClearRequest req = LegacyCase();
        req.stencil_write_mask = 0x3F;
        REQUIRE_FALSE(PartialClearSafe(req));
    }
    // Full write mask and all channels: safe.
    REQUIRE(PartialClearSafe(LegacyCase()));
    // Mask 0 is treated as unmasked on the explicit path (the explicit path special-cases both
    // 0 and 0xFF), so the begin-clear stays eligible.
    {
        ClearRequest req = LegacyCase();
        req.stencil_write_mask = 0;
        REQUIRE(PartialClearSafe(req));
    }
    // Depth-only clear: no colour, no stencil constraint.
    {
        ClearRequest req = FullArea();
        req.use_depth = true;
        REQUIRE(PartialClearSafe(req));
    }
    // A channel-subset clear of colour only (no depth/stencil) is also unsafe for the
    // begin-clear and must keep the explicit ClearAttachments/blit route.
    {
        ClearRequest req = FullArea();
        req.use_color = true;
        req.clear_r = true;
        REQUIRE_FALSE(PartialClearSafe(req));
    }
}
