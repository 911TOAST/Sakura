// SPDX-FileCopyrightText: Copyright 2026 Sakura Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <dirent.h>
#include <dlfcn.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cctype>
#include <cstring>
#include <iterator>
#include <string>
#include <string_view>
#include <vector>

#include "common/logging.h"
#include "core/arm/recomp/arm_recomp.h"

namespace Sakura::Android {
namespace {

struct Image {
    std::string name;
    Core::RecompBlockFn (*lookup)(u64){};
    void (*set_base)(u64){};
    int (*get_index)(u64*, u64*, Core::RecompBlockFn**){};
    u64 base{};
};

std::vector<void*> g_handles;
std::vector<Image> g_records;

struct OwnerEntry {
    u64 base;
    Core::RecompLookupFn lookup;
    u64 idx_lo, idx_hi;
    Core::RecompBlockFn* idx;
    int record;
};

std::array<OwnerEntry, 16> g_owner_entries{};
int g_owner_count = 0;

void RebuildOwnerTable() {
    int n = 0;
    for (size_t k = 0; k < g_records.size() && n < static_cast<int>(g_owner_entries.size()); ++k) {
        const Image& r = g_records[k];
        if (r.base == 0 || !r.lookup) {
            continue;
        }
        u64 lo = 0, hi = 0;
        Core::RecompBlockFn* idx = nullptr;
        if (!r.get_index || !r.get_index(&lo, &hi, &idx)) {
            lo = 1;   // An empty range no pc can satisfy.
            hi = 0;
            idx = nullptr;
        }
        g_owner_entries[n++] = OwnerEntry{r.base, r.lookup, lo, hi, idx, static_cast<int>(k)};
    }
    std::sort(g_owner_entries.begin(), g_owner_entries.begin() + n,
              [](const OwnerEntry& a, const OwnerEntry& b) { return a.base < b.base; });
    g_owner_count = n;
}

std::atomic<int> g_unowned_reports{0};

// The chained dispatcher: find the image that owns an absolute PC, then ask it
// for the block at the module offset. Mirrors the desktop loader's dispatcher
// (suyu/main.cpp Chained) so dispatch stays tens of millions of calls/sec.
Core::RecompBlockFn ChainedLookup(u64 pc) {
    thread_local int hint = -1;
    const int n = g_owner_count;
    if (hint >= 0 && hint < n && g_owner_entries[hint].base <= pc) {
        const OwnerEntry& e = g_owner_entries[hint];
        if (pc >= e.idx_lo && pc <= e.idx_hi) {
            if (auto* block = e.idx[(pc - e.idx_lo) >> 2]) {
                return block;
            }
        } else if (auto* block = e.lookup(pc)) {
            return block;
        }
    }
    int owner_slot = -1;
    for (int k = 0; k < n; ++k) {
        if (g_owner_entries[k].base > pc) {
            break;
        }
        owner_slot = k;
    }
    if (owner_slot >= 0) {
        const OwnerEntry& e = g_owner_entries[owner_slot];
        Core::RecompBlockFn block = nullptr;
        if (pc >= e.idx_lo && pc <= e.idx_hi) {
            block = e.idx[(pc - e.idx_lo) >> 2];
        } else {
            block = e.lookup(pc);
        }
        if (block) {
            hint = owner_slot;
            return block;
        }
        // An ordinary uncovered-code miss; the JIT handles it.
        return nullptr;
    }
    if (g_unowned_reports.fetch_add(1, std::memory_order_relaxed) < 16) {
        LOG_DEBUG(Frontend, "recomp dispatch: no image covers pc={:#x}; using JIT", pc);
    }
    return nullptr;
}

bool CiEqual(std::string_view a, std::string_view b) {
    return a.size() == b.size() &&
           std::equal(a.begin(), a.end(), b.begin(), [](char x, char y) {
               return std::tolower(static_cast<unsigned char>(x)) ==
                      std::tolower(static_cast<unsigned char>(y));
           });
}

Image* MatchName(const std::string& name) {
    for (auto& r : g_records) {
        if (CiEqual(r.name, name)) {
            return &r;
        }
    }
    return nullptr;
}

// Mirrors the desktop base setter (suyu/main.cpp SetRecompBaseSetter): match the
// module by name first, then fall back to the NSO load order.
void SetBaseSetter(size_t index, const char* module, u64 base) {
    static const char* kByLoadOrder[] = {"rtld",    "main",    "subsdk0", "subsdk1", "subsdk2",
                                         "subsdk3", "subsdk4", "subsdk5", "subsdk6", "subsdk7",
                                         "subsdk8", "subsdk9", "sdk"};
    const std::string name = module != nullptr ? module : "";
    Image* record = MatchName(name);
    if (!record && name.rfind("nn", 0) == 0) {
        record = MatchName(name.substr(2));
    }
    if (!record && index < std::size(kByLoadOrder)) {
        record = MatchName(kByLoadOrder[index]);
    }
    if (record && record->set_base) {
        record->base = base;
        record->set_base(base);
        RebuildOwnerTable();
        LOG_INFO(Frontend, "Recompiled image for module '{}' (#{}) based at {:#x}", name, index,
                 base);
    }
}

void CollectFiles(const std::string& dir, std::vector<std::pair<std::string, std::string>>& out) {
    DIR* d = opendir(dir.c_str());
    if (d == nullptr) {
        return;
    }
    while (dirent* e = readdir(d)) {
        if (e->d_name[0] == '\0') {
            continue;
        }
        const std::string n = e->d_name;
        const std::string full = dir + "/" + n;
        DIR* sub = opendir(full.c_str());
        if (sub != nullptr) {
            closedir(sub);
            CollectFiles(full, out);
            continue;
        }
        if (n.size() > 3 && n.compare(n.size() - 3, 3, ".so") == 0) {
            out.emplace_back(dir, full);
        }
    }
    closedir(d);
}

} // anonymous namespace

int LoadRecompiledImagesForGame(const std::string& game_dir) {
    if (game_dir.empty()) {
        return 0;
    }
    std::vector<std::pair<std::string, std::string>> files;  // (dir, file)
    CollectFiles(game_dir + "/aot_cache/recompiled", files);
    CollectFiles(game_dir + "/recompiled", files);
    if (files.empty()) {
        return 0;
    }

    for (void* h : g_handles) {
        dlclose(h);
    }
    g_handles.clear();
    g_records.clear();
    g_owner_count = 0;

    for (const auto& [dir, file] : files) {
        void* h = dlopen(file.c_str(), RTLD_NOW);
        if (h == nullptr) {
            LOG_WARNING(Frontend, "Failed to load recompiled image {}: {}", file, dlerror());
            continue;
        }
        g_handles.push_back(h);
        auto* fn =
            reinterpret_cast<Core::RecompBlockFn (*)(u64)>(dlsym(h, "recomp_image_lookup"));
        if (fn == nullptr) {
            // Not a recompiled image after all.
            continue;
        }
        auto* set_base =
            reinterpret_cast<void (*)(u64)>(dlsym(h, "recomp_image_set_base"));
        auto* get_index = reinterpret_cast<int (*)(u64*, u64*, Core::RecompBlockFn**)>(
            dlsym(h, "recomp_image_index"));
        // The module name is the directory containing the image ("rtld", "main", ...),
        // mirroring the desktop loader's owner.dirName().
        const auto sep = dir.find_last_of('/');
        const std::string name = sep == std::string::npos ? dir : dir.substr(sep + 1);
        g_records.push_back(Image{name, fn, set_base, get_index, 0});
        LOG_INFO(Frontend, "Loaded recompiled module image {} (module '{}')", file, name);
    }
    if (g_records.empty()) {
        return 0;
    }

    // Register the dispatcher and the base reporter before the game starts.
    Core::SetRecompBaseSetter(&SetBaseSetter);
    Core::SetRecompLookup(&ChainedLookup);
    RebuildOwnerTable();
    LOG_INFO(Frontend, "Installed ArmRecomp dispatcher ({} image(s) in {})",
             g_records.size(), game_dir);
    return static_cast<int>(g_records.size());
}

} // namespace Sakura::Android
