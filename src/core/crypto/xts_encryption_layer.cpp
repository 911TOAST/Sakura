// SPDX-FileCopyrightText: Copyright 2025 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2018 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include "common/logging.h"
#include "core/crypto/xts_encryption_layer.h"

namespace Core::Crypto {

constexpr std::size_t XTS_SECTOR_SIZE = 0x4000;

namespace {

u64 NowUs() noexcept {
    return static_cast<u64>(std::chrono::duration_cast<std::chrono::microseconds>(
                                std::chrono::steady_clock::now().time_since_epoch())
                                .count());
}

std::atomic<u64> xts_bytes{};
std::atomic<u64> xts_us{};

} // Anonymous namespace

XTSEncryptionLayer::XTSEncryptionLayer(FileSys::VirtualFile base_, Key256 key_)
    : EncryptionLayer(std::move(base_)), cipher(key_, Mode::XTS) {}

std::size_t XTSEncryptionLayer::Read(u8* data, std::size_t length, std::size_t offset) const {
    if (length == 0)
        return 0;

    const u64 t0 = NowUs();
    auto* out = data;
    std::size_t remaining = length;
    std::size_t current_offset = offset;
    std::size_t total_read = 0;

    std::array<u8, XTS_SECTOR_SIZE> sector;

    auto read_sector = [this](u8* dst, std::size_t index) {
        const std::size_t got = base->Read(dst, XTS_SECTOR_SIZE, index * XTS_SECTOR_SIZE);
        if (got != 0 && got < XTS_SECTOR_SIZE)
            std::memset(dst + got, 0, XTS_SECTOR_SIZE - got);
        return got;
    };

    while (remaining > 0) {
        const std::size_t sector_index = current_offset / XTS_SECTOR_SIZE;
        const std::size_t sector_offset = current_offset % XTS_SECTOR_SIZE;

        if (sector_offset == 0 && remaining >= XTS_SECTOR_SIZE) {
            if (read_sector(out, sector_index) == 0)
                break;
            cipher.XTSTranscode(out, XTS_SECTOR_SIZE, out, sector_index, XTS_SECTOR_SIZE,
                                Op::Decrypt);
            out += XTS_SECTOR_SIZE;
            current_offset += XTS_SECTOR_SIZE;
            remaining -= XTS_SECTOR_SIZE;
            total_read += XTS_SECTOR_SIZE;
            continue;
        }

        if (read_sector(sector.data(), sector_index) == 0)
            break;

        cipher.XTSTranscode(sector.data(), XTS_SECTOR_SIZE, sector.data(), sector_index,
                            XTS_SECTOR_SIZE, Op::Decrypt);

        const std::size_t available = XTS_SECTOR_SIZE - sector_offset;
        const std::size_t to_copy = std::min(available, remaining);
        std::memcpy(out, sector.data() + sector_offset, to_copy);

        out += to_copy;
        current_offset += to_copy;
        remaining -= to_copy;
        total_read += to_copy;
    }

    const u64 dt = NowUs() - t0;
    const u64 bytes = xts_bytes.fetch_add(total_read, std::memory_order_relaxed) + total_read;
    const u64 us = xts_us.fetch_add(dt, std::memory_order_relaxed) + dt;
    if (bytes >= (16ull << 20) && us > 0) {
        LOG_INFO(Crypto, "NCA XTS {} MB in {} us {} MB/s", bytes >> 20, us,
                 (bytes * 1000000ull) / (us << 20));
        xts_bytes.store(0, std::memory_order_relaxed);
        xts_us.store(0, std::memory_order_relaxed);
    }

    return total_read;
}
} // namespace Core::Crypto
