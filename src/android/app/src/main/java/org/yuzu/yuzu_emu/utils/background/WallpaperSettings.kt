// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils.background

import java.io.IOException
import kotlinx.serialization.Serializable
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.utils.NativeConfig

@Serializable
data class WallpaperSettingsSnapshot(val uri: String = "", val tuning: WallpaperTuning = WallpaperTuning())

/** Library atmosphere switches, independent of the selected picture and its tuning. */
@Serializable
data class WallpaperAppearance(val aurora: Boolean = true, val parallax: Boolean = true)

/** All wallpaper settings are global, including when an unrelated per-game config is loaded. */
object WallpaperSettings {
    fun read(): WallpaperSettingsSnapshot = synchronized(NativeConfig) {
        WallpaperSettingsSnapshot(
            StringSetting.LIBRARY_BACKGROUND_URI.getString(true),
            WallpaperTuning(
                fit = IntSetting.LIBRARY_BACKGROUND_FIT.getInt(true),
                dim = IntSetting.LIBRARY_BACKGROUND_DIM.getInt(true),
                blur = IntSetting.LIBRARY_BACKGROUND_BLUR.getInt(true),
                zoom = IntSetting.LIBRARY_BACKGROUND_ZOOM.getInt(true),
                x = IntSetting.LIBRARY_BACKGROUND_X.getInt(true),
                y = IntSetting.LIBRARY_BACKGROUND_Y.getInt(true)
            ).clamped()
        )
    }

    fun personalized(): Boolean = BooleanSetting.WALLPAPER_RECENT_GAMES.getBoolean(true)

    fun appearance(): WallpaperAppearance = synchronized(NativeConfig) {
        WallpaperAppearance(
            BooleanSetting.LIBRARY_AURORA.getBoolean(true),
            BooleanSetting.LIBRARY_PARALLAX.getBoolean(true)
        )
    }

    fun setAppearance(appearance: WallpaperAppearance) = synchronized(NativeConfig) {
        val old = appearance()
        NativeConfig.setBoolean(BooleanSetting.LIBRARY_AURORA.key, appearance.aurora)
        NativeConfig.setBoolean(BooleanSetting.LIBRARY_PARALLAX.key, appearance.parallax)
        if (!NativeConfig.saveWallpaperConfig()) {
            NativeConfig.setBoolean(BooleanSetting.LIBRARY_AURORA.key, old.aurora)
            NativeConfig.setBoolean(BooleanSetting.LIBRARY_PARALLAX.key, old.parallax)
            NativeConfig.saveWallpaperConfig()
            throw IOException("Could not save library appearance preferences")
        }
    }

    fun setPersonalized(enabled: Boolean) = synchronized(NativeConfig) {
        val key = BooleanSetting.WALLPAPER_RECENT_GAMES.key
        val old = personalized()
        NativeConfig.setBoolean(key, enabled)
        if (!NativeConfig.saveWallpaperConfig()) {
            NativeConfig.setBoolean(key, old)
            // Restore the native INI snapshot too; a failed save never replaced the disk file.
            NativeConfig.saveWallpaperConfig()
            throw IOException("Could not save wallpaper preferences")
        }
    }

    fun save(snapshot: WallpaperSettingsSnapshot) = synchronized(NativeConfig) {
        val old = read()
        writeValues(snapshot)
        if (!NativeConfig.saveWallpaperConfig()) {
            writeValues(old)
            NativeConfig.saveWallpaperConfig()
            throw IOException("Could not save wallpaper settings")
        }
    }

    private fun writeValues(snapshot: WallpaperSettingsSnapshot) {
        val t = snapshot.tuning.clamped()
        NativeConfig.setString(StringSetting.LIBRARY_BACKGROUND_URI.key, snapshot.uri)
        val values = listOf(
            IntSetting.LIBRARY_BACKGROUND_FIT to t.fit,
            IntSetting.LIBRARY_BACKGROUND_DIM to t.dim,
            IntSetting.LIBRARY_BACKGROUND_BLUR to t.blur,
            IntSetting.LIBRARY_BACKGROUND_ZOOM to t.zoom,
            IntSetting.LIBRARY_BACKGROUND_X to t.x,
            IntSetting.LIBRARY_BACKGROUND_Y to t.y
        )
        values.forEach { (setting, value) -> NativeConfig.setInt(setting.key, value) }
    }
}
