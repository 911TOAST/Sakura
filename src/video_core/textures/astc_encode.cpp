// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <astcenc.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <filesystem>
#include <mutex>
#include <vector>

#include <fmt/format.h>

#include "common/alignment.h"
#include "common/cityhash.h"
#include "common/fs/file.h"
#include "common/fs/fs.h"
#include "common/fs/path_util.h"
#include "common/logging/log.h"
#include "video_core/textures/astc_encode.h"

namespace Tegra::Texture::ASTC {
namespace {
/// One astcenc context per (worker thread, colour profile). The texture decode workers are
/// persistent threads, so a thread_local context is built once and reused for every texture
/// instead of paying astcenc_context_alloc's large working-buffer setup on each upload. An astcenc
/// context must not compress concurrently, and thread_local gives each worker its own.
struct ThreadContext {
    astcenc_context* ldr_ctx = nullptr;
    astcenc_context* srgb_ctx = nullptr;

    ~ThreadContext() {
        if (ldr_ctx != nullptr) {
            astcenc_context_free(ldr_ctx);
        }
        if (srgb_ctx != nullptr) {
            astcenc_context_free(srgb_ctx);
        }
    }

    astcenc_context* Get(bool srgb) {
        astcenc_context*& ctx = srgb ? srgb_ctx : ldr_ctx;
        if (ctx != nullptr) {
            return ctx;
        }
        astcenc_config config{};
        const astcenc_profile profile = srgb ? ASTCENC_PRF_LDR_SRGB : ASTCENC_PRF_LDR;
        // FASTEST is the real-time preset. MEDIUM is an offline quality preset that costs tens to
        // hundreds of milliseconds per texture on a phone core; running it on the upload path was
        // the source of the BCn->ASTC stutter. At 4x4 the fastest search still tracks the source
        // closely enough for this experimental bandwidth toggle.
        if (astcenc_config_init(profile, 4, 4, 1, ASTCENC_PRE_FASTEST, 0, &config) !=
            ASTCENC_SUCCESS) {
            return nullptr;
        }
        if (astcenc_context_alloc(&config, 1, &ctx, nullptr) != ASTCENC_SUCCESS) {
            ctx = nullptr;
        }
        return ctx;
    }
};
} // Anonymous namespace

bool CompressASTC4x4(std::span<const u8> rgba8, u32 width, u32 height, u32 num_slices,
                     std::span<u8> output, bool srgb) {
    if (width == 0 || height == 0 || num_slices == 0) {
        return false;
    }
    // ASTC 4x4 stores 16 texels in a 16-byte block, i.e. one byte per texel on average.
    const size_t slice_bytes =
        static_cast<size_t>(Common::AlignUp(width, 4)) * Common::AlignUp(height, 4);
    if (output.size() < slice_bytes * num_slices) {
        LOG_WARNING(HW_GPU, "ASTC encode output buffer too small");
        return false;
    }

    thread_local ThreadContext contexts;
    astcenc_context* context = contexts.Get(srgb);
    if (context == nullptr) {
        LOG_WARNING(HW_GPU, "astcenc context unavailable");
        return false;
    }

    const astcenc_swizzle swizzle{ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B, ASTCENC_SWZ_A};
    const size_t slice_texels = static_cast<size_t>(width) * height;
    bool encoded = true;
    for (u32 z = 0; z < num_slices; ++z) {
        // astcenc takes a non-const slice pointer array but never writes to the input texels.
        void* planes[1] = {const_cast<u8*>(rgba8.data() + z * slice_texels * 4)};
        astcenc_image image{static_cast<size_t>(width), static_cast<size_t>(height), 1,
                            ASTCENC_TYPE_U8, planes, 0};
        u8* const out = output.data() + z * slice_bytes;
        if (astcenc_compress_image(context, &image, &swizzle, out, slice_bytes, 0) !=
            ASTCENC_SUCCESS) {
            LOG_WARNING(HW_GPU, "ASTC 4x4 encode failed for slice {}", z);
            // Never upload whatever the failed encode left in the staging buffer, and never let the
            // caller cache this result: fill the slice with transparent black instead.
            std::fill_n(out, slice_bytes, 0);
            encoded = false;
        }
    }
    return encoded;
}

namespace {
std::mutex g_cache_mutex;
std::filesystem::path g_cache_dir;
bool g_cache_dir_ready = false;

/// Entries are ~1 byte per texel, so a whole library of games can add up. Keep the cache bounded:
/// past the budget, delete the least recently written files down to a lower watermark (hysteresis
/// avoids rescanning on every subsequent write).
constexpr u64 MaxCacheBytes = 512ull * 1024 * 1024;
constexpr u64 TargetCacheBytes = 384ull * 1024 * 1024;
constexpr u32 WritesPerTrim = 64;
u32 g_writes_since_trim = 0;

/// A single content-keyed directory shared by all titles: the key is a hash of the decoded texels,
/// so it is globally unique and no per-title path (or title id) is needed here.
const std::filesystem::path& CacheDir() {
    if (!g_cache_dir_ready) {
        g_cache_dir = Common::FS::GetSuyuPath(Common::FS::SuyuPath::CacheDir) / "astc_bcn";
        if (!Common::FS::CreateDirs(g_cache_dir)) {
            LOG_ERROR(Common_Filesystem, "Failed to create ASTC cache directory {}",
                      g_cache_dir.string());
        }
        g_cache_dir_ready = true;
    }
    return g_cache_dir;
}

u64 CacheKey(std::span<const u8> rgba8, u32 width, u32 height, u32 num_slices, bool srgb) {
    const u32 header[4]{width, height, num_slices, srgb ? 1u : 0u};
    const u64 seed = Common::CityHash64(reinterpret_cast<const char*>(header), sizeof(header));
    return Common::CityHash64WithSeed(reinterpret_cast<const char*>(rgba8.data()), rgba8.size(),
                                      seed);
}

std::filesystem::path CachePath(u64 key) {
    return CacheDir() / fmt::format("{:016x}.astc", key);
}

/// Prefix stored in front of every cached payload so a truncated or corrupted file is detected at
/// lookup instead of being uploaded as garbage texture data. The magic doubles as the format
/// version: bump or change it and existing caches are invalidated wholesale.
constexpr std::array<u8, 8> kCacheMagic{'S', 'U', 'A', 'S', 'T', 'C', '0', '1'};

struct CacheHeader {
    std::array<u8, 8> magic{};
    u64 payload_hash{}; // CityHash64 of the ASTC payload
    u64 payload_size{}; // bytes of ASTC payload behind the header
};
static_assert(offsetof(CacheHeader, payload_size) == 16);

struct CacheEntry {
    std::filesystem::path path;
    u64 size{};
    std::filesystem::file_time_type write_time{};
};

/// Delete the oldest entries until the cache is back under TargetCacheBytes. Called with the cache
/// mutex held, so no other thread is reading or writing an entry while it is removed. Every
/// filesystem call uses an error_code so a file that disappears mid-scan can never throw.
void TrimCache() {
    std::error_code ec;
    std::vector<CacheEntry> entries;
    u64 total = 0;
    for (std::filesystem::directory_iterator it{g_cache_dir, ec};
         !ec && it != std::filesystem::directory_iterator{}; it.increment(ec)) {
        std::error_code entry_ec;
        if (!it->is_regular_file(entry_ec)) {
            continue;
        }
        const u64 size = it->file_size(entry_ec);
        if (entry_ec) {
            continue;
        }
        CacheEntry entry;
        entry.path = it->path();
        entry.size = size;
        entry.write_time = std::filesystem::last_write_time(entry.path, entry_ec);
        entries.push_back(std::move(entry));
        total += size;
    }
    if (ec || total <= MaxCacheBytes) {
        return;
    }
    std::sort(entries.begin(), entries.end(),
              [](const CacheEntry& lhs, const CacheEntry& rhs) {
                  return lhs.write_time < rhs.write_time;
              });
    for (const CacheEntry& entry : entries) {
        if (total <= TargetCacheBytes) {
            break;
        }
        std::error_code remove_ec;
        if (std::filesystem::remove(entry.path, remove_ec) && !remove_ec) {
            total -= entry.size;
        }
    }
}
} // Anonymous namespace

bool LookupAstcCache(std::span<const u8> rgba8, u32 width, u32 height, u32 num_slices, bool srgb,
                     std::span<u8> output) {
    std::scoped_lock lock{g_cache_mutex};
    const auto path = CachePath(CacheKey(rgba8, width, height, num_slices, srgb));
    // Absent entry = normal cold-cache miss; a failed check falls through so Open logs real errors.
    std::error_code ec;
    if (!std::filesystem::exists(path, ec) && !ec) {
        return false;
    }
    Common::FS::IOFile file{path, Common::FS::FileAccessMode::Read};
    if (!file.IsOpen()) {
        return false;
    }
    // Every payload is wrapped in a CacheHeader: magic + format version, the payload's CityHash
    // and its size. A truncated (crash mid-write) or bit-corrupted (disk-error) entry is detected
    // here instead of being uploaded as garbage texture data; the stale file is deleted so the
    // next encode succeeds and re-stores it.
    const u64 file_size = file.GetSize();
    if (file_size != sizeof(CacheHeader) + output.size()) {
        return false;
    }
    CacheHeader header{};
    if (file.ReadSpan(std::span<u8>(reinterpret_cast<u8*>(&header), sizeof(header))) !=
        sizeof(header)) {
        return false;
    }
    if (header.magic != kCacheMagic || header.payload_size != output.size()) {
        return false;
    }
    if (file.ReadSpan(output) != output.size()) {
        return false;
    }
    if (Common::CityHash64(reinterpret_cast<const char*>(output.data()), output.size()) !=
        header.payload_hash) {
        // Content does not match what was stored at this key: delete so a later run re-encodes.
        std::filesystem::remove(path, ec);
        LOG_ERROR(Common_Filesystem, "ASTC cache entry failed its integrity check: {}",
                  path.string());
        return false;
    }
    // A hit is a use: refresh the timestamp so TrimCache() (oldest-first by write time) ranks
    // entries by access, not by when they happened to be written.
    std::filesystem::last_write_time(path, ec);
    return true;
}

void StoreAstcCache(std::span<const u8> rgba8, u32 width, u32 height, u32 num_slices, bool srgb,
                    std::span<const u8> astc) {
    std::scoped_lock lock{g_cache_mutex};
    const auto path = CachePath(CacheKey(rgba8, width, height, num_slices, srgb));
    // A present file was written completely and verified by a previous Lookup (corrupt entries are
    // deleted there), and the key already determines the payload content: skip the rewrite.
    std::error_code ec;
    if (std::filesystem::exists(path, ec) && !ec) {
        return;
    }
    // Write to a temporary name and rename into place: readers (this process or a second
    // instance) can otherwise observe a half-written file at the final path.
    const auto tmp_path = CacheDir() / fmt::format("{}.tmp", path.filename().string());
    CacheHeader header{};
    header.magic = kCacheMagic;
    header.payload_size = astc.size();
    header.payload_hash =
        Common::CityHash64(reinterpret_cast<const char*>(astc.data()), astc.size());
    {
        Common::FS::IOFile file{tmp_path, Common::FS::FileAccessMode::Write};
        if (!file.IsOpen() ||
            file.WriteSpan(std::span<const u8>(reinterpret_cast<const u8*>(&header),
                                               sizeof(header))) != sizeof(header) ||
            file.WriteSpan(astc) != astc.size()) {
            LOG_ERROR(Common_Filesystem, "Failed to write ASTC cache entry {}", path.string());
            return;
        }
    }
    std::filesystem::rename(tmp_path, path, ec);
    if (ec) {
        // POSIX rename replaces an existing file but Windows does not; remove and retry once.
        std::filesystem::remove(path, ec);
        ec.clear();
        std::filesystem::rename(tmp_path, path, ec);
        ec.clear();
        if (std::filesystem::exists(tmp_path, ec)) {
            std::filesystem::remove(tmp_path, ec);
        }
        return;
    }
    if (++g_writes_since_trim >= WritesPerTrim) {
        g_writes_since_trim = 0;
        TrimCache();
    }
}

} // namespace Tegra::Texture::ASTC