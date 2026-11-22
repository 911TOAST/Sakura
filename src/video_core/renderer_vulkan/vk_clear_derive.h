// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include "common/common_types.h"

namespace Vulkan {

/// Plain-data description of what a guest clear is asking for, plus the shape
/// of the framebuffer it applies to. Everything is host-testable: no Vulkan
/// types, no engine state.
///
/// The derivation answers one question for a tile-based GPU: can (parts of)
/// this clear be expressed as render-pass LOAD_OP_CLEAR at pass begin instead
/// of begin-with-LOAD followed by an explicit clear command? On a tiler the
/// LOAD form fetches every tile of the loaded attachments from DRAM before the
/// clear discards them; the CLEAR form never touches memory for those aspects.

/// Render-target shape as the clear path sees it.
struct ClearFbShape {
    u32 num_color_buffers = 0;
    bool has_depth = false;
    bool has_stencil = false;
    /// Every attachment's subresource range is a single layer (no layered
    /// attachment may use loadOp CLEAR: the op defines ALL layers, and the
    /// guest clear only asks for one).
    bool single_layer = false;
};

/// Guest clear request, after the rasterizer has clamped the clear rect to the
/// render area.
struct ClearRequest {
    /// Requested aspects (from regs.clear_surface).
    bool use_color = false;
    bool clear_r = false;
    bool clear_g = false;
    bool clear_b = false;
    bool clear_a = false;
    bool use_depth = false;
    bool use_stencil = false;
    /// Colour attachment index the guest clear applies to (regs.clear_surface.RT).
    u32 rt = 0;
    /// Clamped clear rect (offset + extent within the render area).
    u32 rect_x = 0;
    u32 rect_y = 0;
    u32 rect_w = 0;
    u32 rect_h = 0;
    /// Render area extent.
    u32 area_w = 0;
    u32 area_h = 0;
    /// regs.clear_surface.layer == 0 && clear's layerCount == 1.
    bool layer0_single = false;
    /// regs.stencil_front_mask: a stencil clear whose write mask is neither 0xFF nor 0 goes
    /// through the masked blit path on the explicit route; a begin-time CLEAR cannot mask, so
    /// those requests must keep the explicit path (see PartialClearSafe).
    u32 stencil_write_mask = 0xFF;
};

/// Which attachments may be DEFINED at pass begin instead of loaded.
struct ClearLoadOpPlan {
    /// A clear-variant render pass should begin with these ops.
    bool use_clear_variant = false;
    /// Bit k set: colour attachment k is defined at begin (k = RT index,
    /// which equals the attachment index in render pass description order).
    u8 color_mask = 0;
    bool depth_clear = false;
    bool stencil_clear = false;
    /// Colour attachment index the clear value must be recorded at (== rt).
    u32 value_color_index = 0;
};

/// Conservativeness contract (unit-tested):
/// - Returns use_clear_variant only when every aspect selected for CLEAR is
///   present on the framebuffer, the clamped rect covers the FULL render
///   area, the clear applies to a single layer, and colour clears write all
///   four channels (loadOp CLEAR cannot write channel subsets).
/// - Aspects present on the framebuffer but not selected keep LOAD, so their
///   contents are preserved exactly as with the explicit-clear path.
constexpr ClearLoadOpPlan DeriveClearLoadOp(const ClearRequest& req, const ClearFbShape& shape) {
    ClearLoadOpPlan plan{};

    // A begin-time CLEAR defines the whole render area of the attachment for
    // every layer: only full-coverage, single-layer clears qualify. Whether
    // the guest used a scissor to get here is irrelevant once the clamped
    // rect provably covers the whole area.
    const bool full_coverage = req.rect_x == 0 && req.rect_y == 0 &&
                               req.rect_w == req.area_w && req.rect_h == req.area_h &&
                               req.area_w != 0 && req.area_h != 0;
    if (!full_coverage || !req.layer0_single || !shape.single_layer) {
        return plan;
    }

    // Colour: loadOp CLEAR writes all components, so a channel subset must
    // keep the explicit path. Depth and stencil have separate guest flags and
    // separate per-aspect ops in the attachment description.
    const bool all_channels = req.clear_r && req.clear_g && req.clear_b && req.clear_a;
    const bool color_ok = req.use_color && all_channels && req.rt < shape.num_color_buffers;
    plan.color_mask = color_ok ? static_cast<u8>(1u << req.rt) : 0u;
    plan.depth_clear = req.use_depth && shape.has_depth;
    plan.stencil_clear = req.use_stencil && shape.has_stencil;

    if (plan.color_mask == 0 && !plan.depth_clear && !plan.stencil_clear) {
        // No aspect gains anything: a begin-clear variant would clear nothing.
        return plan;
    }

    plan.use_clear_variant = true;
    plan.value_color_index = color_ok ? req.rt : 0;
    return plan;
}

/// The pre-existing (legacy) fast path: only the exact all-aspect clear
/// qualifies. A plan passing this predicate is exactly the case the legacy
/// gate would have taken, which keeps the extended path a pure superset of
/// the old behaviour when the extension setting is off.
constexpr bool PlanIsLegacyAllAspect(const ClearLoadOpPlan& plan, const ClearFbShape& shape) {
    if (!plan.use_clear_variant) {
        return false;
    }
    const u8 full_mask = shape.num_color_buffers != 0
                             ? static_cast<u8>((1u << shape.num_color_buffers) - 1u)
                             : u8{0};
    return plan.color_mask == full_mask && plan.depth_clear == shape.has_depth &&
           plan.stencil_clear == shape.has_stencil;
}

/// Extra eligibility rule for cases the widened (partial) clear extension adds on top of the
/// legacy all-aspect gate. A begin-time CLEAR is the unmasked equivalent of
/// vkCmdClearAttachments, so a request that asks for a colour CHANNEL subset (only some of
/// RGBA) or a masked stencil write must keep the explicit path, which honours both. Returns
/// false precisely when the begin-clear would silently write more than the guest asked for.
constexpr bool PartialClearSafe(const ClearRequest& req) {
    const bool whole_channels = !req.use_color ||
                                (req.clear_r && req.clear_g && req.clear_b && req.clear_a);
    const bool unmasked_stencil = !req.use_stencil || req.stencil_write_mask == 0xFF ||
                                  req.stencil_write_mask == 0;
    return whole_channels && unmasked_stencil;
}

} // namespace Vulkan
