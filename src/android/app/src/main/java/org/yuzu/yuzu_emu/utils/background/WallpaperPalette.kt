// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils.background

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.utils.ArtworkColors

/**
 * Wallpaper-derived accent colour for the Chameleon theme and the aurora veil.
 *
 * One seed slot is shared with the cover-art theme: a selected game cover always outranks the
 * wallpaper, and the wallpaper takes over again when no cover owns the slot. Compose surfaces
 * keep collecting [ArtworkColors.seed] and follow whichever source wins.
 */
object WallpaperPalette {
    @Volatile private var wallpaperSeed: Int? = null

    @Volatile var coverActive: Boolean = false
        private set

    /**
     * Samples the applied picture off the main thread and publishes it when no cover is active.
     * Returns the wallpaper's own seed so the aurora can follow the picture even while a cover
     * owns the shared theme slot.
     */
    suspend fun applyFromWallpaper(bitmap: Bitmap?): Int? = withContext(Dispatchers.IO) {
        wallpaperSeed = ArtworkColors.seedFromArtwork(bitmap)
        publish()
        wallpaperSeed
    }

    fun onCoverSeedChanged(seed: Int?) {
        coverActive = seed != null
        if (seed != null) ArtworkColors.setSeed(seed) else publish()
    }

    fun clear() {
        wallpaperSeed = null
        publish()
    }

    private fun publish() {
        if (!coverActive) ArtworkColors.setSeed(wallpaperSeed)
    }
}
