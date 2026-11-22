// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>
#include <span>

#include "common/common_types.h"
#include "shader_recompiler/frontend/ir/attribute.h"
#include "shader_recompiler/runtime_info.h"
#include "shader_recompiler/varying_state.h"

namespace Shader {

/// The Switch GPU (Maxwell) exposes 32 generic vertex input attribute locations. Some hosts
/// report a lower maxVertexInputAttributes limit (observed: 16 on an ARM Mali driver), where a
/// SPIR-V module declaring locations >= the limit fails pipeline creation and the model simply
/// does not render.
///
/// Compaction renumbers the guest locations that are actually used into consecutive locations
/// starting at 0, in ascending guest-index order. It is a pure renaming: every attribute keeps
/// its type, buffer binding and offset, so the fetched values are bit-identical as long as both
/// the shader's Location decorations and the pipeline's VkVertexInputAttributeDescription come
/// from the same map. Attributes that this shader does not declare as inputs keep their guest
/// index (they are unused either way).
///
/// Returns the location to assign to each guest generic index. With limit == 0 or
/// limit >= IR::NUM_GENERICS the mapping is the identity (compaction inactive).
[[nodiscard]] inline std::array<u32, IR::NUM_GENERICS> CompactVertexInputLocations(
    const VaryingState& previous_stage_stores, const VaryingState& loads,
    std::span<const AttributeType, 32> input_types, u32 max_attributes) {
    std::array<u32, IR::NUM_GENERICS> locations{};
    const bool compact{max_attributes != 0 && max_attributes < static_cast<u32>(IR::NUM_GENERICS)};
    u32 next_location = 0;
    for (u32 index = 0; index < IR::NUM_GENERICS; ++index) {
        if (compact && previous_stage_stores.Generic(index) && loads.Generic(index) &&
            input_types[index] != AttributeType::Disabled) {
            locations[index] = next_location++;
        } else {
            locations[index] = index;
        }
    }
    return locations;
}

} // namespace Shader
