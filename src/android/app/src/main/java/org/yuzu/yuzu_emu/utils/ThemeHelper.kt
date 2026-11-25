// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.ui.theme.ThemeController

/**
 * Color helpers and constants kept beside the theme pipeline for convenience.
 *
 * Theme *decisions* no longer live here: the palette, dark mode, Material You and the black
 * background overlay are resolved in exactly one place — [ThemeController] and [ThemeSpec] — and
 * applied by `SakuraActivity`. Apply-side code elsewhere in the app asks the controller; nothing
 * re-derives a theme from raw settings any more.
 */
object ThemeHelper {
    const val SYSTEM_BAR_ALPHA = 0.9f

    @ColorInt
    fun getColorWithOpacity(@ColorInt color: Int, alphaFactor: Float): Int {
        return Color.argb(
            (alphaFactor * Color.alpha(color)).roundToInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    /**
     * The launcher's own surface. Deliberately the static Sakura token instead of
     * ?attr/colorSurface: Material You re-tints that role from the wallpaper, which turned the
     * library backdrop into a coloured wash. Accents stay dynamic, the backdrop does not.
     */
    @ColorInt
    fun getLauncherSurfaceColor(context: Context): Int =
        ContextCompat.getColor(context, R.color.suyu_surface)
}
