// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.ComponentCallbacks2

/**
 * One place the app gives memory back when Android asks for it.
 *
 * The two big soft caches are the Coil icon memory cache (decoded game covers) and the library's
 * decoded wallpaper. Neither is a leak in the strict sense, but on 8-12 GB phones the framework
 * keeps the process resident and lets those caches grow to a meaningful share of RAM before it
 * reclaims anything, which shows up as stutter or a kill during a game.
 */
object MemoryPressure {

    @Volatile
    private var lastLevel = 0

    /**
     * @param level one of the `ComponentCallbacks2.TRIM_MEMORY_*` constants.
     */
    fun trim(level: Int) {
        lastLevel = level
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            GameIconUtils.trimMemory()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // The library is behind another activity (a game) or the process is in the background:
            // drop the decoded wallpaper instead of keeping ~15-26 MB of ARGB alive. Re-decoded on
            // the next resume. RUNNING_LOW is deliberately not enough here, so a trim while the
            // library is on screen does not blank it.
            LibraryBackgroundApplier.trimMemory()
        }
    }

    /** Level of the most recent callback, useful for diagnostics and the perf dashboard. */
    val lastTrimLevel: Int
        get() = lastLevel
}
