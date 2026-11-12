// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <span>

#include "common/common_types.h"

namespace Tegra::Texture::ASTC {

/// Compress tightly-packed RGBA8 texels into ASTC 4x4 LDR blocks (8 bpp). \p num_slices independent
/// width x height images are encoded back to back into \p output, each occupying
/// AlignUp(width,4)*AlignUp(height,4) bytes. Used to recompress guest BCn colour textures to a
/// format Mali samples natively instead of inflating them to RGBA8. \p srgb selects the sRGB
/// encoding profile. Returns true only if every slice was encoded successfully; on failure the
/// affected slices are zero-filled so no uninitialised memory reaches the upload, and the result
/// must not be cached.
[[nodiscard]] bool CompressASTC4x4(std::span<const u8> rgba8, u32 width, u32 height, u32 num_slices,
                                   std::span<u8> output, bool srgb);

/// On-disk cache of CompressASTC4x4 results, keyed by the decoded RGBA8 texels plus dimensions and
/// sRGB profile. Because those fully determine the ASTC output, a hit is bit-identical to a fresh
/// encode and lets the upload skip the expensive astcenc pass entirely (the BCn decode still runs).
/// Lookup returns true and fills \p output on a hit; both are no-ops if the cache dir is unavailable.
/// The cache is bounded to a fixed on-disk budget and evicts the oldest entries when it is exceeded.
/// Intended for the texture decode worker, not the GPU thread.
bool LookupAstcCache(std::span<const u8> rgba8, u32 width, u32 height, u32 num_slices, bool srgb,
                     std::span<u8> output);
void StoreAstcCache(std::span<const u8> rgba8, u32 width, u32 height, u32 num_slices, bool srgb,
                    std::span<const u8> astc);

} // namespace Tegra::Texture::ASTC