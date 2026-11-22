// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Host-side unit tests for Shader::CompactVertexInputLocations. Compaction must be a pure
// renaming of guest vertex input locations: same order, same set of used attributes, identity
// when inactive. The SPIR-V backend and the Vulkan pipeline vertex input both call this, so any
// divergence here would silently break vertex fetch.

#include <catch2/catch_test_macros.hpp>

#include "shader_recompiler/attribute_compaction.h"

using Shader::AttributeType;
using Shader::CompactVertexInputLocations;
using Shader::VaryingState;

namespace {

constexpr std::size_t kNumAttributes = 32;

std::array<AttributeType, kNumAttributes> Types(std::initializer_list<std::size_t> used) {
    std::array<AttributeType, kNumAttributes> types{};
    types.fill(AttributeType::Disabled);
    for (const std::size_t index : used) {
        types[index] = AttributeType::Float;
    }
    return types;
}

VaryingState AllGenerics() {
    VaryingState state;
    for (std::size_t index = 0; index < kNumAttributes; ++index) {
        state.Set(static_cast<Shader::IR::Attribute>(
            static_cast<u32>(Shader::IR::Attribute::Generic0X) + index * 4), true);
    }
    return state;
}

} // namespace

TEST_CASE("Compaction inactive keeps guest locations", "[video_core]") {
    const auto types = Types({1, 17, 31});
    const auto map = CompactVertexInputLocations(AllGenerics(), AllGenerics(), types, 0u);
    REQUIRE(map[1] == 1);
    REQUIRE(map[17] == 17);
    REQUIRE(map[31] == 31);

    // Limit >= guest count is also inactive.
    const auto map32 = CompactVertexInputLocations(AllGenerics(), AllGenerics(), types, 32u);
    REQUIRE(map32[31] == 31);
}

TEST_CASE("Compaction renumbers used locations in ascending order", "[video_core]") {
    const auto types = Types({1, 17, 31});
    const auto map = CompactVertexInputLocations(AllGenerics(), AllGenerics(), types, 16u);
    REQUIRE(map[1] == 0);
    REQUIRE(map[17] == 1);
    REQUIRE(map[31] == 2);
}

TEST_CASE("Compaction skips attributes the shader does not load", "[video_core]") {
    const auto types = Types({1, 17, 31});
    VaryingState loads;
    loads.Set(static_cast<Shader::IR::Attribute>(
        static_cast<u32>(Shader::IR::Attribute::Generic0X) + 1 * 4), true);
    loads.Set(static_cast<Shader::IR::Attribute>(
        static_cast<u32>(Shader::IR::Attribute::Generic0X) + 31 * 4), true);
    const auto map = CompactVertexInputLocations(AllGenerics(), loads, types, 16u);
    REQUIRE(map[1] == 0);
    REQUIRE(map[31] == 1);
    // Unused attribute keeps its guest index; it is never declared.
    REQUIRE(map[17] == 17);
}

TEST_CASE("Compaction skips disabled attribute types", "[video_core]") {
    const auto types = Types({1, 17, 31});
    types[17] = AttributeType::Disabled; // enabled-looking slot with no type
    const auto map = CompactVertexInputLocations(AllGenerics(), AllGenerics(), types, 16u);
    REQUIRE(map[1] == 0);
    REQUIRE(map[17] == 17);
    REQUIRE(map[31] == 1);
}

TEST_CASE("Compaction is injective for used attributes", "[video_core]") {
    const auto types = Types({0, 3, 7, 15, 16, 30});
    const auto map = CompactVertexInputLocations(AllGenerics(), AllGenerics(), types, 16u);
    for (std::size_t a = 0; a < kNumAttributes; ++a) {
        if (map[a] >= 6) { // 6 used attributes got locations 0..5
            continue;
        }
        for (std::size_t b = a + 1; b < kNumAttributes; ++b) {
            REQUIRE(map[a] != map[b]);
        }
    }
}
