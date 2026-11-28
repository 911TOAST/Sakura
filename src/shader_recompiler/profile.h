// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2021 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include "common/common_types.h"

namespace Shader {

enum class Stage : u32;

struct Profile {
    u32 supported_spirv{0x00010000};
    bool unified_descriptor_binding{};
    bool support_descriptor_aliasing{};
    bool support_int8{};
    bool support_uniform_and_storage_buffer_8bit{};
    bool support_int16{};
    bool support_uniform_and_storage_buffer_16bit{};
    bool support_int64{};
    bool support_vertex_instance_id{};
    bool support_float_controls{};
    bool support_separate_denorm_behavior{};
    bool support_separate_rounding_mode{};
    bool support_fp16_denorm_preserve{};
    bool support_fp32_denorm_preserve{};
    bool support_fp16_denorm_flush{};
    bool support_fp32_denorm_flush{};
    bool support_fp16_signed_zero_nan_preserve{};
    bool support_fp32_signed_zero_nan_preserve{};
    bool support_fp64_signed_zero_nan_preserve{};
    bool support_explicit_workgroup_layout{};
    bool support_vote{};
    u32 supported_subgroup_stages{0x7F};
    bool support_viewport_index_layer_non_geometry{};
    bool support_viewport_mask{};
    bool support_typeless_image_loads{};
    bool support_demote_to_helper_invocation{};
    bool support_int64_atomics{};
    bool support_derivative_control{};
    bool support_geometry_shader_passthrough{};
    bool support_native_ndc{};
    bool support_gl_nv_gpu_shader_5{};
    bool support_gl_amd_gpu_shader_half_float{};
    bool support_gl_texture_shadow_lod{};
    bool support_gl_warp_intrinsics{};
    bool support_gl_variable_aoffi{};
    bool support_gl_sparse_textures{};
    bool support_gl_derivative_control{};
    bool support_scaled_attributes{};
    bool support_multi_viewport{};
    bool support_geometry_streams{};
    bool support_sampled_image_array_nonuniform_indexing{};

    // Experimental diagnostic: replace guest depth-compare (shadow map) sample results with
    // "lit" (1.0). Set from Settings::values.shadow_compare_bypass by each renderer profile.
    // With this set, the emits instead gather raw depth and compare manually in-shader; the
    // runtime must push a compare-disable sampler (pipeline_helper.h HandleWithoutDepthComparison).
    bool shadow_compare_bypass{};
    // Direction of the manual compare above: false = depth <= ref is "lit", true = depth >= ref.
    bool shadow_compare_manual_flip{};

    // Experimental workaround for drivers (MediaTek-bundled ARM Mali, r44) that mis-evaluate
    // derivatives for implicit-LOD samples inside divergent fragment control flow, returning
    // black. Forces every implicit-LOD sample in the fragment stage down to explicit LOD 0.
    // Opt-in via Settings::values.force_implicit_lod_explicit: LOD 0 disables derivative-based
    // mip selection, so leaving it forced on made textures look blurry on healthy drivers.
    bool force_implicit_lod_explicit{};
    // Workaround for drivers (ARM Mali r44) that fail texel-buffer type punning: reading an
    // integer-format VkBufferView through a float-typed sampled image returns garbage instead of
    // the raw bits. When set, texture buffers are declared as R32ui in SPIR-V and the fetch is
    // bitcast to float in-shader, so no format reinterpretation ever crosses the descriptor.
    // The runtime must bind every texture buffer view as R32_UINT (vk_buffer_cache.cpp).
    bool force_uint_texel_buffers{};

    bool warp_size_potentially_larger_than_guest{};

    bool lower_left_origin_mode{};
    /// Fragment outputs have to be declared even if they are not written to avoid undefined values.
    /// See Ori and the Blind Forest's main menu for reference.
    bool need_declared_frag_colors{};
    /// Prevents fast math optimizations that may cause inaccuracies
    bool need_fastmath_off{};
    /// Some GPU vendors use a different rounding precision when calculating texture pixel
    /// coordinates with the 16.8 format in the ImageGather instruction than the Maxwell
    /// architecture. Applying an offset does fix this mismatching rounding behaviour.
    bool need_gather_subpixel_offset{};

    /// OpFClamp is broken and OpFMax + OpFMin should be used instead
    bool has_broken_spirv_clamp{};
    /// The Position builtin needs to be wrapped in a struct when used as an input
    bool has_broken_spirv_position_input{};
    /// Offset image operands with an unsigned type do not work
    bool has_broken_unsigned_image_offsets{};
    /// Signed instructions with unsigned data types are misinterpreted
    bool has_broken_signed_operations{};
    /// Float controls break when fp16 is enabled
    bool has_broken_fp16_float_controls{};
    /// Dynamic vec4 indexing is broken on some OpenGL drivers
    bool has_gl_component_indexing_bug{};
    /// The precise type qualifier is broken in the fragment stage of some drivers
    bool has_gl_precise_bug{};
    /// Some drivers do not properly support floatBitsToUint when used on cbufs
    bool has_gl_cbuf_ftou_bug{};
    /// Some drivers poorly optimize boolean variable references
    bool has_gl_bool_ref_bug{};
    /// Ignores SPIR-V ordered vs unordered using GLSL semantics
    bool ignore_nan_fp_comparisons{};
    /// Some drivers have broken support for OpVectorExtractDynamic on subgroup mask inputs
    bool has_broken_spirv_subgroup_mask_vector_extract_dynamic{};

    u32 gl_max_compute_smem_size{};

    /// Maxwell and earlier nVidia architectures have broken robust support
    bool has_broken_robust{};

    u64 min_ssbo_alignment{};
    u32 max_user_clip_distances{};
    /// Host limit for vertex input attributes. When 0 < limit < IR::NUM_GENERICS, guest vertex
    /// input locations are compacted (see attribute_compaction.h), because a module declaring
    /// higher locations fails pipeline creation on such hosts. 0 keeps guest numbering.
    u32 max_vertex_input_attributes{};

    /// Allow the SPIR-V backend to decorate fragment FP32 arithmetic as RelaxedPrecision
    bool relax_fp32_precision{};

    bool collect_debug_info{};

    bool SupportsSubgroupStage(Stage stage) const {
        return (supported_subgroup_stages & (1u << static_cast<u32>(stage))) != 0;
    }
};

} // namespace Shader
