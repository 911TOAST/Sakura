// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <array>
#include <bitset>
#include <cstring>
#include <map>
#include <span>
#include <type_traits>
#include <vector>

#include "shader_recompiler/shader_info.h"

namespace Shader::Serialize {

/// Bump whenever a field is added to, removed from, or reordered in AppendInfo/ReadInfo.
constexpr u32 InfoSerializationVersion = 1;

/// Binary writer/reader for Shader::Info. The format is only used by the Vulkan
/// precompiled shader cache and is versioned by the caller.
class Reader {
public:
    explicit Reader(std::span<const u8> data_) : data{data_} {}

    [[nodiscard]] bool Ok() const noexcept {
        return ok;
    }

    [[nodiscard]] size_t Remaining() const noexcept {
        return data.size() - offset;
    }

    template <typename T>
        requires std::is_trivially_copyable_v<T>
    bool ReadRaw(T& value) {
        if (!ok || offset + sizeof(T) > data.size()) {
            ok = false;
            return false;
        }
        std::memcpy(&value, data.data() + offset, sizeof(T));
        offset += sizeof(T);
        return true;
    }

    bool ReadU8(u8& value) {
        return ReadRaw(value);
    }

    bool ReadU32(u32& value) {
        return ReadRaw(value);
    }

    bool ReadU64(u64& value) {
        return ReadRaw(value);
    }

    bool ReadBool(bool& value) {
        u8 raw{};
        if (!ReadU8(raw)) {
            return false;
        }
        value = raw != 0;
        return true;
    }

    bool ReadBytes(std::span<u8> output) {
        if (!ok || offset + output.size() > data.size()) {
            ok = false;
            return false;
        }
        std::memcpy(output.data(), data.data() + offset, output.size());
        offset += output.size();
        return true;
    }

    template <size_t N>
    bool ReadBitset(std::bitset<N>& value) {
        constexpr size_t bytes{(N + 7) / 8};
        std::array<u8, bytes> raw{};
        for (u8& byte : raw) {
            if (!ReadU8(byte)) {
                return false;
            }
        }
        value.reset();
        for (size_t i = 0; i < N; ++i) {
            value[i] = (raw[i / 8] & (1u << (i % 8))) != 0;
        }
        return true;
    }

private:
    std::span<const u8> data;
    size_t offset{};
    bool ok{true};
};

template <typename T>
    requires std::is_trivially_copyable_v<T>
void AppendRaw(std::vector<u8>& out, const T& value) {
    const auto* const bytes = reinterpret_cast<const u8*>(&value);
    out.insert(out.end(), bytes, bytes + sizeof(T));
}

inline void AppendU32(std::vector<u8>& out, u32 value) {
    AppendRaw(out, value);
}

inline void AppendU64(std::vector<u8>& out, u64 value) {
    AppendRaw(out, value);
}

inline void AppendBool(std::vector<u8>& out, bool value) {
    out.push_back(value ? u8{1} : u8{0});
}

template <size_t N>
void AppendBitset(std::vector<u8>& out, const std::bitset<N>& value) {
    constexpr size_t bytes{(N + 7) / 8};
    for (size_t i = 0; i < bytes; ++i) {
        u8 byte{};
        for (size_t j = 0; j < 8; ++j) {
            const size_t bit{i * 8 + j};
            if (bit < N && value[bit]) {
                byte |= static_cast<u8>(1u << j);
            }
        }
        out.push_back(byte);
    }
}

template <typename Container>
void AppendDescriptors(std::vector<u8>& out, const Container& values) {
    AppendU32(out, static_cast<u32>(values.size()));
    for (const auto& value : values) {
        AppendRaw(out, value);
    }
}

inline void AppendAttributeMap(std::vector<u8>& out,
                               const std::map<IR::Attribute, IR::Attribute>& map) {
    AppendU32(out, static_cast<u32>(map.size()));
    for (const auto& [key, value] : map) {
        AppendU32(out, static_cast<u32>(key));
        AppendU32(out, static_cast<u32>(value));
    }
}

inline void AppendInfo(std::vector<u8>& out, const Info& info) {
#define APPEND_BOOL(name) AppendBool(out, info.name)
#define APPEND_U32_CAST(name) AppendU32(out, static_cast<u32>(info.name))

    APPEND_BOOL(uses_workgroup_id);
    APPEND_BOOL(uses_local_invocation_id);
    APPEND_BOOL(uses_invocation_id);
    APPEND_BOOL(uses_invocation_info);
    APPEND_BOOL(uses_sample_id);
    APPEND_BOOL(uses_is_helper_invocation);
    APPEND_BOOL(uses_subgroup_invocation_id);
    APPEND_BOOL(uses_subgroup_shuffles);

    for (const bool value : info.uses_patches) {
        AppendBool(out, value);
    }

    for (const Interpolation value : info.interpolation) {
        AppendU32(out, static_cast<u32>(value));
    }

    AppendBitset(out, info.loads.mask);
    AppendBitset(out, info.stores.mask);
    AppendBitset(out, info.passthrough.mask);
    AppendAttributeMap(out, info.legacy_stores_mapping);

    APPEND_BOOL(loads_indexed_attributes);

    for (const bool value : info.stores_frag_color) {
        AppendBool(out, value);
    }

    APPEND_BOOL(stores_sample_mask);
    APPEND_BOOL(stores_frag_depth);
    APPEND_BOOL(stores_tess_level_outer);
    APPEND_BOOL(stores_tess_level_inner);
    APPEND_BOOL(stores_indexed_attributes);
    APPEND_BOOL(stores_global_memory);
    APPEND_BOOL(uses_local_memory);

    APPEND_BOOL(uses_fp16);
    APPEND_BOOL(uses_fp64);
    APPEND_BOOL(uses_fp16_denorms_flush);
    APPEND_BOOL(uses_fp16_denorms_preserve);
    APPEND_BOOL(uses_fp32_denorms_flush);
    APPEND_BOOL(uses_fp32_denorms_preserve);
    APPEND_BOOL(uses_int8);
    APPEND_BOOL(uses_int16);
    APPEND_BOOL(uses_int64);
    APPEND_BOOL(uses_image_1d);
    APPEND_BOOL(uses_sampled_1d);
    APPEND_BOOL(uses_sparse_residency);
    APPEND_BOOL(uses_demote_to_helper_invocation);
    APPEND_BOOL(uses_subgroup_vote);
    APPEND_BOOL(uses_subgroup_mask);
    APPEND_BOOL(uses_fswzadd);
    APPEND_BOOL(uses_derivatives);
    APPEND_BOOL(uses_typeless_image_reads);
    APPEND_BOOL(uses_typeless_image_writes);
    APPEND_BOOL(uses_image_buffers);
    APPEND_BOOL(uses_shared_increment);
    APPEND_BOOL(uses_shared_decrement);
    APPEND_BOOL(uses_global_increment);
    APPEND_BOOL(uses_global_decrement);
    APPEND_BOOL(uses_atomic_f32_add);
    APPEND_BOOL(uses_atomic_f16x2_add);
    APPEND_BOOL(uses_atomic_f16x2_min);
    APPEND_BOOL(uses_atomic_f16x2_max);
    APPEND_BOOL(uses_atomic_f32x2_add);
    APPEND_BOOL(uses_atomic_f32x2_min);
    APPEND_BOOL(uses_atomic_f32x2_max);
    APPEND_BOOL(uses_atomic_s32_min);
    APPEND_BOOL(uses_atomic_s32_max);
    APPEND_BOOL(uses_int64_bit_atomics);
    APPEND_BOOL(uses_global_memory);
    APPEND_BOOL(uses_atomic_image_u32);
    APPEND_BOOL(uses_shadow_lod);
    APPEND_BOOL(uses_rescaling_uniform);
    APPEND_BOOL(uses_cbuf_indirect);
    APPEND_BOOL(uses_render_area);

    APPEND_U32_CAST(used_constant_buffer_types);
    APPEND_U32_CAST(used_storage_buffer_types);
    APPEND_U32_CAST(used_indirect_cbuf_types);

    AppendU32(out, info.constant_buffer_mask);
    for (const u32 value : info.constant_buffer_used_sizes) {
        AppendU32(out, value);
    }
    AppendU32(out, info.nvn_buffer_base);
    AppendBitset(out, info.nvn_buffer_used);

    APPEND_BOOL(requires_layer_emulation);
    APPEND_U32_CAST(emulated_layer);
    AppendU32(out, info.used_clip_distances);

    AppendDescriptors(out, info.constant_buffer_descriptors);
    AppendDescriptors(out, info.storage_buffers_descriptors);
    AppendDescriptors(out, info.texture_buffer_descriptors);
    AppendDescriptors(out, info.image_buffer_descriptors);
    AppendDescriptors(out, info.texture_descriptors);
    AppendDescriptors(out, info.image_descriptors);

#undef APPEND_BOOL
#undef APPEND_U32_CAST
}

template <typename Container>
bool ReadDescriptors(Reader& reader, Container& values, u32 max_count) {
    u32 count{};
    if (!reader.ReadU32(count) || count > max_count) {
        return false;
    }
    for (u32 i = 0; i < count; ++i) {
        typename Container::value_type value{};
        if (!reader.ReadRaw(value)) {
            return false;
        }
        values.push_back(value);
    }
    return true;
}

inline bool ReadInfo(Reader& reader, Info& info) {
#define READ_BOOL(name)                                                                           \
    do {                                                                                          \
        if (!reader.ReadBool(info.name)) {                                                        \
            return false;                                                                         \
        }                                                                                         \
    } while (false)
#define READ_U32_CAST(name)                                                                       \
    do {                                                                                          \
        u32 value{};                                                                              \
        if (!reader.ReadU32(value)) {                                                             \
            return false;                                                                         \
        }                                                                                         \
        info.name = static_cast<decltype(info.name)>(value);                                      \
    } while (false)

    READ_BOOL(uses_workgroup_id);
    READ_BOOL(uses_local_invocation_id);
    READ_BOOL(uses_invocation_id);
    READ_BOOL(uses_invocation_info);
    READ_BOOL(uses_sample_id);
    READ_BOOL(uses_is_helper_invocation);
    READ_BOOL(uses_subgroup_invocation_id);
    READ_BOOL(uses_subgroup_shuffles);

    for (bool& value : info.uses_patches) {
        if (!reader.ReadBool(value)) {
            return false;
        }
    }

    for (Interpolation& value : info.interpolation) {
        u32 raw{};
        if (!reader.ReadU32(raw)) {
            return false;
        }
        value = static_cast<Interpolation>(raw);
    }

    if (!reader.ReadBitset(info.loads.mask) || !reader.ReadBitset(info.stores.mask) ||
        !reader.ReadBitset(info.passthrough.mask)) {
        return false;
    }

    u32 map_size{};
    if (!reader.ReadU32(map_size) || map_size > 512) {
        return false;
    }
    info.legacy_stores_mapping.clear();
    for (u32 i = 0; i < map_size; ++i) {
        u32 key{};
        u32 value{};
        if (!reader.ReadU32(key) || !reader.ReadU32(value)) {
            return false;
        }
        info.legacy_stores_mapping.emplace(static_cast<IR::Attribute>(key),
                                           static_cast<IR::Attribute>(value));
    }

    READ_BOOL(loads_indexed_attributes);

    for (bool& value : info.stores_frag_color) {
        if (!reader.ReadBool(value)) {
            return false;
        }
    }

    READ_BOOL(stores_sample_mask);
    READ_BOOL(stores_frag_depth);
    READ_BOOL(stores_tess_level_outer);
    READ_BOOL(stores_tess_level_inner);
    READ_BOOL(stores_indexed_attributes);
    READ_BOOL(stores_global_memory);
    READ_BOOL(uses_local_memory);

    READ_BOOL(uses_fp16);
    READ_BOOL(uses_fp64);
    READ_BOOL(uses_fp16_denorms_flush);
    READ_BOOL(uses_fp16_denorms_preserve);
    READ_BOOL(uses_fp32_denorms_flush);
    READ_BOOL(uses_fp32_denorms_preserve);
    READ_BOOL(uses_int8);
    READ_BOOL(uses_int16);
    READ_BOOL(uses_int64);
    READ_BOOL(uses_image_1d);
    READ_BOOL(uses_sampled_1d);
    READ_BOOL(uses_sparse_residency);
    READ_BOOL(uses_demote_to_helper_invocation);
    READ_BOOL(uses_subgroup_vote);
    READ_BOOL(uses_subgroup_mask);
    READ_BOOL(uses_fswzadd);
    READ_BOOL(uses_derivatives);
    READ_BOOL(uses_typeless_image_reads);
    READ_BOOL(uses_typeless_image_writes);
    READ_BOOL(uses_image_buffers);
    READ_BOOL(uses_shared_increment);
    READ_BOOL(uses_shared_decrement);
    READ_BOOL(uses_global_increment);
    READ_BOOL(uses_global_decrement);
    READ_BOOL(uses_atomic_f32_add);
    READ_BOOL(uses_atomic_f16x2_add);
    READ_BOOL(uses_atomic_f16x2_min);
    READ_BOOL(uses_atomic_f16x2_max);
    READ_BOOL(uses_atomic_f32x2_add);
    READ_BOOL(uses_atomic_f32x2_min);
    READ_BOOL(uses_atomic_f32x2_max);
    READ_BOOL(uses_atomic_s32_min);
    READ_BOOL(uses_atomic_s32_max);
    READ_BOOL(uses_int64_bit_atomics);
    READ_BOOL(uses_global_memory);
    READ_BOOL(uses_atomic_image_u32);
    READ_BOOL(uses_shadow_lod);
    READ_BOOL(uses_rescaling_uniform);
    READ_BOOL(uses_cbuf_indirect);
    READ_BOOL(uses_render_area);

    READ_U32_CAST(used_constant_buffer_types);
    READ_U32_CAST(used_storage_buffer_types);
    READ_U32_CAST(used_indirect_cbuf_types);

    if (!reader.ReadU32(info.constant_buffer_mask)) {
        return false;
    }
    for (u32& value : info.constant_buffer_used_sizes) {
        if (!reader.ReadU32(value)) {
            return false;
        }
    }
    if (!reader.ReadU32(info.nvn_buffer_base) || !reader.ReadBitset(info.nvn_buffer_used)) {
        return false;
    }

    READ_BOOL(requires_layer_emulation);
    READ_U32_CAST(emulated_layer);
    if (!reader.ReadU32(info.used_clip_distances)) {
        return false;
    }

    if (!ReadDescriptors(reader, info.constant_buffer_descriptors, Info::MAX_CBUFS) ||
        !ReadDescriptors(reader, info.storage_buffers_descriptors, Info::MAX_SSBOS) ||
        !ReadDescriptors(reader, info.texture_buffer_descriptors, 6) ||
        !ReadDescriptors(reader, info.image_buffer_descriptors, 2) ||
        !ReadDescriptors(reader, info.texture_descriptors, 12) ||
        !ReadDescriptors(reader, info.image_descriptors, 4)) {
        return false;
    }

#undef READ_BOOL
#undef READ_U32_CAST
    return reader.Ok();
}

} // namespace Shader::Serialize
