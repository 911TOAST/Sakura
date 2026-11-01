// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <algorithm>
#include <vector>

#include "common/alignment.h"
#include "common/common_types.h"

namespace VideoCommon {

class UsageTracker {
    static constexpr size_t BYTES_PER_BIT_SHIFT = 6;
    static constexpr size_t PAGE_SHIFT = 6 + BYTES_PER_BIT_SHIFT;
    static constexpr size_t PAGE_BYTES = 1 << PAGE_SHIFT;

public:
    explicit UsageTracker(size_t size) {
        const size_t num_pages = (size >> PAGE_SHIFT) + 1;
        pages.resize(num_pages, 0ULL);
        dirty_begin = num_pages;
    }

    /// Clears only the pages Track() could have set. Clearing the whole bitmap was O(buffer size)
    /// and ran for every cached buffer on every frame, which cost more than tracking itself.
    void Reset() noexcept {
        for (size_t i = dirty_begin; i < dirty_end; ++i) {
            pages[i] = 0ULL;
        }
        dirty_begin = pages.size();
        dirty_end = 0;
    }

    [[nodiscard]] bool HasDirtyPages() const noexcept {
        return dirty_begin < dirty_end;
    }

    void Track(u64 offset, u64 size) noexcept {
        const size_t page = offset >> PAGE_SHIFT;
        const size_t page_end = (offset + size) >> PAGE_SHIFT;
        if (page_end < page || page_end >= pages.size()) {
            return;
        }
        TrackPage(page, offset, size);
        if (page == page_end) {
            MarkDirty(page, page + 1);
            return;
        }
        for (size_t i = page + 1; i < page_end; i++) {
            pages[i] = ~u64{0};
        }
        const size_t offset_end = offset + size;
        const size_t offset_end_page_aligned = Common::AlignDown(offset_end, PAGE_BYTES);
        TrackPage(page_end, offset_end_page_aligned, offset_end - offset_end_page_aligned);
        MarkDirty(page, page_end + 1);
    }

    [[nodiscard]] bool IsUsed(u64 offset, u64 size) const noexcept {
        const size_t page = offset >> PAGE_SHIFT;
        const size_t page_end = (offset + size) >> PAGE_SHIFT;
        if (page_end < page || page_end >= pages.size()) {
            return false;
        }
        if (IsPageUsed(page, offset, size)) {
            return true;
        }
        for (size_t i = page + 1; i < page_end; i++) {
            if (pages[i] != 0) {
                return true;
            }
        }
        const size_t offset_end = offset + size;
        const size_t offset_end_page_aligned = Common::AlignDown(offset_end, PAGE_BYTES);
        return IsPageUsed(page_end, offset_end_page_aligned, offset_end - offset_end_page_aligned);
    }

private:
    void MarkDirty(size_t begin, size_t end) noexcept {
        dirty_begin = (std::min)(dirty_begin, begin);
        dirty_end = (std::max)(dirty_end, end);
    }

    void TrackPage(u64 page, u64 offset, u64 size) noexcept {
        const size_t offset_in_page = offset % PAGE_BYTES;
        const size_t first_bit = offset_in_page >> BYTES_PER_BIT_SHIFT;
        const size_t num_bits = std::min<size_t>(size, PAGE_BYTES) >> BYTES_PER_BIT_SHIFT;
        const size_t mask = ~u64{0} >> (64 - num_bits);
        pages[page] |= (~u64{0} & mask) << first_bit;
    }

    bool IsPageUsed(u64 page, u64 offset, u64 size) const noexcept {
        const size_t offset_in_page = offset % PAGE_BYTES;
        const size_t first_bit = offset_in_page >> BYTES_PER_BIT_SHIFT;
        const size_t num_bits = std::min<size_t>(size, PAGE_BYTES) >> BYTES_PER_BIT_SHIFT;
        const size_t mask = ~u64{0} >> (64 - num_bits);
        const size_t mask2 = (~u64{0} & mask) << first_bit;
        return (pages[page] & mask2) != 0;
    }

private:
    std::vector<u64> pages;
    /// Half-open range of pages that may be non-zero. Empty when dirty_begin >= dirty_end.
    size_t dirty_begin{0};
    size_t dirty_end{0};
};

} // namespace VideoCommon
