// SPDX-FileCopyrightText: Copyright 2020 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <optional>

#include "video_core/renderer_vulkan/vk_master_semaphore.h"
#include "video_core/renderer_vulkan/vk_resource_pool.h"

namespace Vulkan {

ResourcePool::ResourcePool(MasterSemaphore& master_semaphore_, size_t grow_step_)
    : master_semaphore{&master_semaphore_}, grow_step{grow_step_} {}

size_t ResourcePool::CommitResource() {
    // Completed ticks never go backwards. Reuse known-free resources without a driver query.
    u64 gpu_tick = master_semaphore->KnownGpuTick();
    const auto search = [this, &gpu_tick](size_t begin, size_t end) -> std::optional<size_t> {
        for (size_t iterator = begin; iterator < end; ++iterator) {
            if (gpu_tick >= ticks[iterator]) {
                ticks[iterator] = master_semaphore->CurrentTick();
                return iterator;
            }
        }
        return std::nullopt;
    };
    const auto find_free = [&]() -> std::optional<size_t> {
        if (const auto found = search(hint_iterator, ticks.size())) {
            return found;
        }
        return search(0, hint_iterator);
    };
    std::optional<size_t> found = find_free();
    if (!found && !ticks.empty()) {
        // Refresh only under pressure, then retry before allocating another pool page.
        master_semaphore->Refresh();
        const u64 refreshed_tick = master_semaphore->KnownGpuTick();
        if (refreshed_tick > gpu_tick) {
            gpu_tick = refreshed_tick;
            found = find_free();
        }
    }
    if (!found) {
        const size_t free_resource = ManageOverflow();
        ticks[free_resource] = master_semaphore->CurrentTick();
        found = free_resource;
    }
    // Free iterator is hinted to the resource after the one that's been committed.
    hint_iterator = (*found + 1) % ticks.size();
    return *found;
}

size_t ResourcePool::ManageOverflow() {
    const size_t old_capacity = ticks.size();
    Grow();

    // The last entry is guaranteed to be free, since it's the first element of the freshly
    // allocated resources.
    return old_capacity;
}

void ResourcePool::Grow() {
    const size_t old_capacity = ticks.size();
    ticks.resize(old_capacity + grow_step);
    Allocate(old_capacity, old_capacity + grow_step);
}

} // namespace Vulkan
