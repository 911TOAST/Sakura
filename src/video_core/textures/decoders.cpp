// SPDX-FileCopyrightText: Copyright 2025 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2018 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <array>
#include <cmath>
#include <cstring>
#include <span>

#include "common/alignment.h"
#include "common/assert.h"
#include "common/bit_util.h"
#include "common/div_ceil.h"
#include "video_core/gpu.h"
#include "video_core/textures/decoders.h"

namespace Tegra::Texture {
namespace {
template <u32 mask>
constexpr u32 pdep(u32 value) {
    u32 result = 0;
    u32 m = mask;
    for (u32 bit = 1; m; bit += bit) {
        if (value & bit)
            result |= m & (~m + 1);
        m &= m - 1;
    }
    return result;
}

template <u32 mask, u32 incr_amount>
void incrpdep(u32& value) {
    static constexpr u32 swizzled_incr = pdep<mask>(incr_amount);
    value = ((value | ~mask) + swizzled_incr) & mask;
}

// A GOB is 64 bytes wide and the block linear swizzle keeps the four low bits of the byte offset
// inside a GOB contiguous (SWIZZLE_X_BITS starts with 0b1111). Every 16-byte aligned chunk is
// therefore stored as a single contiguous run in the tiled image, no matter how the rest of the
// address is swizzled. This lets us copy whole segments with wide loads/stores instead of moving
// one pixel at a time.
constexpr u32 GOB_SEGMENT_SHIFT = 4;
constexpr u32 GOB_SEGMENT_SIZE = 1U << GOB_SEGMENT_SHIFT;

/// Returns the swizzled byte offset of a 16-byte aligned offset 'x' inside a GOB. Only bits 4 and
/// 5 of the offset contribute here, higher bits select the GOB and are applied by the caller.
[[nodiscard]] constexpr u32 SegmentSwizzledX(u32 x) {
    return ((x & 0x10) << 1) | ((x & 0x20) << 3);
}

template <bool TO_LINEAR>
inline void CopySegment(u8* output, const u8* input, u32 swizzled_offset, u32 linear_offset) {
    if constexpr (TO_LINEAR) {
        std::memcpy(output + swizzled_offset, input + linear_offset, GOB_SEGMENT_SIZE);
    } else {
        std::memcpy(output + linear_offset, input + swizzled_offset, GOB_SEGMENT_SIZE);
    }
}

template <bool TO_LINEAR, u32 BYTES_PER_PIXEL>
void SwizzleImpl(std::span<u8> output, std::span<const u8> input, u32 width, u32 height, u32 depth,
                 u32 block_height, u32 block_depth, u32 stride) {
    // The origin of the transformation can be configured here, leave it as zero as the current API
    // doesn't expose it.
    static constexpr u32 origin_x = 0;
    static constexpr u32 origin_y = 0;
    static constexpr u32 origin_z = 0;

    // The segment copy below assumes that the X origin is zero.
    static_assert(origin_x == 0, "Segment copies assume a zero X origin");

    // We can configure here a custom pitch
    // As it's not exposed 'width * BYTES_PER_PIXEL' will be the expected pitch.
    const u32 pitch = width * BYTES_PER_PIXEL;

    const u32 gobs_in_x = Common::DivCeilLog2(stride, GOB_SIZE_X_SHIFT);
    const u32 block_size = gobs_in_x << (GOB_SIZE_SHIFT + block_height + block_depth);
    const u32 slice_size =
        Common::DivCeilLog2(height, block_height + GOB_SIZE_Y_SHIFT) * block_size;

    const u32 block_height_mask = (1U << block_height) - 1;
    const u32 block_depth_mask = (1U << block_depth) - 1;
    const u32 x_shift = GOB_SIZE_SHIFT + block_height + block_depth;

    // Segments are only valid when they cover a whole number of pixels.
    constexpr bool can_copy_segments = (GOB_SEGMENT_SIZE % BYTES_PER_PIXEL) == 0;
    const u32 segment_bytes = can_copy_segments ? (pitch & ~(GOB_SEGMENT_SIZE - 1)) : 0;
    const u32 segment_columns = segment_bytes / BYTES_PER_PIXEL;

    u8* const output_data = output.data();
    const u8* const input_data = input.data();

    for (u32 slice = 0; slice < depth; ++slice) {
        const u32 z = slice + origin_z;
        const u32 offset_z = (z >> block_depth) * slice_size +
                             ((z & block_depth_mask) << (GOB_SIZE_SHIFT + block_height));
        const u32 linear_slice_base = slice * pitch * height;
        for (u32 line = 0; line < height; ++line) {
            const u32 y = line + origin_y;
            const u32 swizzled_y = pdep<SWIZZLE_Y_BITS>(y);

            const u32 block_y = y >> GOB_SIZE_Y_SHIFT;
            const u32 offset_y = (block_y >> block_height) * block_size +
                                 ((block_y & block_height_mask) << GOB_SIZE_SHIFT);

            const u32 swizzled_row_base = offset_z + offset_y + swizzled_y;
            const u32 linear_row_base = linear_slice_base + line * pitch;

            if constexpr (can_copy_segments) {
                const u32 gobs = segment_bytes >> GOB_SIZE_X_SHIFT;
                u32 x = 0;
                // Each full GOB of a row is four 16-byte segments. Their swizzled offsets inside
                // the GOB are fixed because only bits 4 and 5 of the byte offset vary.
                for (u32 gob = 0; gob < gobs; ++gob, x += GOB_SIZE_X) {
                    const u32 swizzled_base = swizzled_row_base + (gob << x_shift);
                    const u32 linear_base = linear_row_base + x;

                    CopySegment<TO_LINEAR>(output_data, input_data, swizzled_base + 0,
                                           linear_base + 0);
                    CopySegment<TO_LINEAR>(output_data, input_data, swizzled_base + 32,
                                           linear_base + 16);
                    CopySegment<TO_LINEAR>(output_data, input_data, swizzled_base + 256,
                                           linear_base + 32);
                    CopySegment<TO_LINEAR>(output_data, input_data, swizzled_base + 288,
                                           linear_base + 48);
                }
                // Segments of a partial trailing GOB, for widths that are not a multiple of 64
                // bytes.
                for (; x < segment_bytes; x += GOB_SEGMENT_SIZE) {
                    const u32 swizzled_offset = swizzled_row_base + SegmentSwizzledX(x) +
                                                ((x >> GOB_SIZE_X_SHIFT) << x_shift);
                    CopySegment<TO_LINEAR>(output_data, input_data, swizzled_offset,
                                           linear_row_base + x);
                }
            }

            // Remainder of the row, at most 15 bytes (or the whole row for bytes-per-pixel values
            // that cannot be copied in 16-byte segments).
            u32 swizzled_x = pdep<SWIZZLE_X_BITS>(segment_bytes);
            for (u32 column = segment_columns; column < width;
                 ++column, incrpdep<SWIZZLE_X_BITS, BYTES_PER_PIXEL>(swizzled_x)) {
                const u32 x = column * BYTES_PER_PIXEL;
                const u32 offset_x = (x >> GOB_SIZE_X_SHIFT) << x_shift;

                const u32 swizzled_offset = swizzled_row_base + offset_x + swizzled_x;
                const u32 unswizzled_offset = linear_row_base + x;

                u8* const dst = output_data + (TO_LINEAR ? swizzled_offset : unswizzled_offset);
                const u8* const src = input_data + (TO_LINEAR ? unswizzled_offset : swizzled_offset);

                std::memcpy(dst, src, BYTES_PER_PIXEL);
            }
        }
    }
}

template <bool TO_LINEAR, u32 BYTES_PER_PIXEL>
void SwizzleSubrectImpl(std::span<u8> output, std::span<const u8> input, u32 width, u32 height,
                        u32 depth, u32 origin_x, u32 origin_y, u32 extent_x, u32 num_lines,
                        u32 block_height, u32 block_depth, u32 pitch_linear) {
    // The origin of the transformation can be configured here, leave it as zero as the current API
    // doesn't expose it.
    static constexpr u32 origin_z = 0;

    // We can configure here a custom pitch
    // As it's not exposed 'width * BYTES_PER_PIXEL' will be the expected pitch.
    const u32 pitch = pitch_linear;
    const u32 stride = Common::AlignUpLog2(width * BYTES_PER_PIXEL, GOB_SIZE_X_SHIFT);

    const u32 gobs_in_x = Common::DivCeilLog2(stride, GOB_SIZE_X_SHIFT);
    const u32 block_size = gobs_in_x << (GOB_SIZE_SHIFT + block_height + block_depth);
    const u32 slice_size =
        Common::DivCeilLog2(height, block_height + GOB_SIZE_Y_SHIFT) * block_size;

    const u32 block_height_mask = (1U << block_height) - 1;
    const u32 block_depth_mask = (1U << block_depth) - 1;
    const u32 x_shift = GOB_SIZE_SHIFT + block_height + block_depth;

    constexpr bool can_copy_segments = (GOB_SEGMENT_SIZE % BYTES_PER_PIXEL) == 0;
    constexpr u32 segment_columns = can_copy_segments ? GOB_SEGMENT_SIZE / BYTES_PER_PIXEL : 0;

    u32 unprocessed_lines = num_lines;
    u32 extent_y = (std::min)(num_lines, height - origin_y);

    u8* const output_data = output.data();
    const u8* const input_data = input.data();

    for (u32 slice = 0; slice < depth; ++slice) {
        const u32 z = slice + origin_z;
        const u32 offset_z = (z >> block_depth) * slice_size +
                             ((z & block_depth_mask) << (GOB_SIZE_SHIFT + block_height));
        const u32 lines_in_y = (std::min)(unprocessed_lines, extent_y);
        const u32 linear_slice_base = slice * pitch * height;
        for (u32 line = 0; line < lines_in_y; ++line) {
            const u32 y = line + origin_y;
            const u32 swizzled_y = pdep<SWIZZLE_Y_BITS>(y);

            const u32 block_y = y >> GOB_SIZE_Y_SHIFT;
            const u32 offset_y = (block_y >> block_height) * block_size +
                                 ((block_y & block_height_mask) << GOB_SIZE_SHIFT);

            const u32 swizzled_row_base = offset_z + offset_y + swizzled_y;
            const u32 linear_row_base = linear_slice_base + line * pitch;

            u32 column = 0;
            u32 x = origin_x * BYTES_PER_PIXEL;
            u32 swizzled_x = pdep<SWIZZLE_X_BITS>(x);

            auto copy_pixel = [&] {
                const u32 offset_x = (x >> GOB_SIZE_X_SHIFT) << x_shift;
                const u32 swizzled_offset = swizzled_row_base + offset_x + swizzled_x;
                const u32 unswizzled_offset = linear_row_base + column * BYTES_PER_PIXEL;

                u8* const dst = output_data + (TO_LINEAR ? swizzled_offset : unswizzled_offset);
                const u8* const src = input_data + (TO_LINEAR ? unswizzled_offset : swizzled_offset);

                std::memcpy(dst, src, BYTES_PER_PIXEL);
            };

            if constexpr (can_copy_segments) {
                // Skip pixels until the byte offset is 16-byte aligned so the rest of the row can
                // be copied with wide loads/stores.
                while (column < extent_x && (x & (GOB_SEGMENT_SIZE - 1)) != 0) {
                    copy_pixel();
                    ++column;
                    x += BYTES_PER_PIXEL;
                    incrpdep<SWIZZLE_X_BITS, BYTES_PER_PIXEL>(swizzled_x);
                }
                while (column + segment_columns <= extent_x) {
                    const u32 offset_x = (x >> GOB_SIZE_X_SHIFT) << x_shift;
                    CopySegment<TO_LINEAR>(output_data, input_data,
                                           swizzled_row_base + offset_x + swizzled_x,
                                           linear_row_base + column * BYTES_PER_PIXEL);
                    column += segment_columns;
                    x += GOB_SEGMENT_SIZE;
                    swizzled_x = pdep<SWIZZLE_X_BITS>(x);
                }
            }
            for (; column < extent_x;
                 ++column, x += BYTES_PER_PIXEL,
                 incrpdep<SWIZZLE_X_BITS, BYTES_PER_PIXEL>(swizzled_x)) {
                copy_pixel();
            }
        }
        unprocessed_lines -= lines_in_y;
        if (unprocessed_lines == 0) {
            return;
        }
    }
}

template <bool TO_LINEAR>
void Swizzle(std::span<u8> output, std::span<const u8> input, u32 bytes_per_pixel, u32 width,
             u32 height, u32 depth, u32 block_height, u32 block_depth, u32 stride_alignment) {
    switch (bytes_per_pixel) {
#define BPP_CASE(x)                                                                                \
    case x:                                                                                        \
        return SwizzleImpl<TO_LINEAR, x>(output, input, width, height, depth, block_height,        \
                                         block_depth, stride_alignment);
        BPP_CASE(1)
        BPP_CASE(2)
        BPP_CASE(3)
        BPP_CASE(4)
        BPP_CASE(6)
        BPP_CASE(8)
        BPP_CASE(12)
        BPP_CASE(16)
#undef BPP_CASE
    default:
        ASSERT_MSG(false, "Invalid bytes_per_pixel={}", bytes_per_pixel);
        break;
    }
}

} // Anonymous namespace

void UnswizzleTexture(std::span<u8> output, std::span<const u8> input, u32 bytes_per_pixel,
                      u32 width, u32 height, u32 depth, u32 block_height, u32 block_depth,
                      u32 stride_alignment) {
    const u32 stride = Common::AlignUpLog2(width, stride_alignment) * bytes_per_pixel;
    const u32 new_bpp = (std::min)(4U, static_cast<u32>(std::countr_zero(width * bytes_per_pixel)));
    width = (width * bytes_per_pixel) >> new_bpp;
    bytes_per_pixel = 1U << new_bpp;
    Swizzle<false>(output, input, bytes_per_pixel, width, height, depth, block_height, block_depth,
                   stride);
}

void SwizzleTexture(std::span<u8> output, std::span<const u8> input, u32 bytes_per_pixel, u32 width,
                    u32 height, u32 depth, u32 block_height, u32 block_depth,
                    u32 stride_alignment) {
    const u32 stride = Common::AlignUpLog2(width, stride_alignment) * bytes_per_pixel;
    const u32 new_bpp = (std::min)(4U, static_cast<u32>(std::countr_zero(width * bytes_per_pixel)));
    width = (width * bytes_per_pixel) >> new_bpp;
    bytes_per_pixel = 1U << new_bpp;
    Swizzle<true>(output, input, bytes_per_pixel, width, height, depth, block_height, block_depth,
                  stride);
}

void SwizzleSubrect(std::span<u8> output, std::span<const u8> input, u32 bytes_per_pixel, u32 width,
                    u32 height, u32 depth, u32 origin_x, u32 origin_y, u32 extent_x, u32 extent_y,
                    u32 block_height, u32 block_depth, u32 pitch_linear) {
    switch (bytes_per_pixel) {
#define BPP_CASE(x)                                                                                \
    case x:                                                                                        \
        return SwizzleSubrectImpl<true, x>(output, input, width, height, depth, origin_x,          \
                                           origin_y, extent_x, extent_y, block_height,             \
                                           block_depth, pitch_linear);
        BPP_CASE(1)
        BPP_CASE(2)
        BPP_CASE(3)
        BPP_CASE(4)
        BPP_CASE(6)
        BPP_CASE(8)
        BPP_CASE(12)
        BPP_CASE(16)
#undef BPP_CASE
    default:
        ASSERT_MSG(false, "Invalid bytes_per_pixel={}", bytes_per_pixel);
        break;
    }
}

void UnswizzleSubrect(std::span<u8> output, std::span<const u8> input, u32 bytes_per_pixel,
                      u32 width, u32 height, u32 depth, u32 origin_x, u32 origin_y, u32 extent_x,
                      u32 extent_y, u32 block_height, u32 block_depth, u32 pitch_linear) {
    switch (bytes_per_pixel) {
#define BPP_CASE(x)                                                                                \
    case x:                                                                                        \
        return SwizzleSubrectImpl<false, x>(output, input, width, height, depth, origin_x,         \
                                            origin_y, extent_x, extent_y, block_height,            \
                                            block_depth, pitch_linear);
        BPP_CASE(1)
        BPP_CASE(2)
        BPP_CASE(3)
        BPP_CASE(4)
        BPP_CASE(6)
        BPP_CASE(8)
        BPP_CASE(12)
        BPP_CASE(16)
#undef BPP_CASE
    default:
        ASSERT_MSG(false, "Invalid bytes_per_pixel={}", bytes_per_pixel);
        break;
    }
}

std::size_t CalculateSize(bool tiled, u32 bytes_per_pixel, u32 width, u32 height, u32 depth,
                          u32 block_height, u32 block_depth) {
    if (tiled) {
        const u32 aligned_width = Common::AlignUpLog2(width * bytes_per_pixel, GOB_SIZE_X_SHIFT);
        const u32 aligned_height = Common::AlignUpLog2(height, GOB_SIZE_Y_SHIFT + block_height);
        const u32 aligned_depth = Common::AlignUpLog2(depth, GOB_SIZE_Z_SHIFT + block_depth);
        return aligned_width * aligned_height * aligned_depth;
    } else {
        return width * height * depth * bytes_per_pixel;
    }
}

u64 GetGOBOffset(u32 width, u32 height, u32 dst_x, u32 dst_y, u32 block_height,
                 u32 bytes_per_pixel) {
    auto div_ceil = [](const u32 x, const u32 y) { return ((x + y - 1) / y); };
    const u32 gobs_in_block = 1 << block_height;
    const u32 y_blocks = GOB_SIZE_Y << block_height;
    const u32 x_per_gob = GOB_SIZE_X / bytes_per_pixel;
    const u32 x_blocks = div_ceil(width, x_per_gob);
    const u32 block_size = GOB_SIZE * gobs_in_block;
    const u32 stride = block_size * x_blocks;
    const u32 base = (dst_y / y_blocks) * stride + (dst_x / x_per_gob) * block_size;
    const u32 relative_y = dst_y % y_blocks;
    return base + (relative_y / GOB_SIZE_Y) * GOB_SIZE;
}

} // namespace Tegra::Texture
