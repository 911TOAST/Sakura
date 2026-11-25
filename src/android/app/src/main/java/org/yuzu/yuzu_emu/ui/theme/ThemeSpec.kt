// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.ui.theme

import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import org.yuzu.yuzu_emu.R

/**
 * The complete theming decision for the app, as one immutable value.
 *
 * Everything that can change how windows, surfaces and system bars are coloured is folded into
 * this object and *resolved here*, in pure code, before any activity or Compose tree consumes it.
 * There is exactly one resolution pass, so the View windows and the Compose screens can never
 * answer the questions "which palette", "dynamic or static" and "dark or light" independently —
 * which is what used to leave a light window behind a dark Compose tree (or a fully unpainted,
 * black one)[bug: the old code replaced the activity theme with a bare overlay when black
 * backgrounds were on, wiping the palette at inflation time].
 */
data class ThemeSpec(
    /** One of the @see androidx.appcompat.app.AppCompatDelegate MODE_NIGHT_* constants. */
    val nightMode: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
    /** Follow the system wallpaper (Material You). Honoured only on Android 12+. */
    val materialYou: Boolean = false,
    /** Index into [PALETTE_THEMES], clamped everywhere it is used. */
    val paletteIndex: Int = 0,
    /** AMOLED-style pure black surfaces on top of dark mode. */
    val blackBackgrounds: Boolean = false,
    /** Shape-language overlay style resource from the layout preset, or 0 for none. */
    val layoutOverlay: Int = 0,
) {
    /** Material You settings exist from 12 up; below that the flag is simply inert. */
    fun usesDynamicColor(): Boolean =
        materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * The single style the activity is inflated with. Overlays are applied on top of this — never
     * passed to setTheme again (a `parent=""` overlay handed to setTheme discards the whole
     * palette and everything inherits platform defaults).
     */
    fun xmlTheme(): Int = when {
        usesDynamicColor() -> R.style.Theme_Yuzu_Main_MaterialYou
        else -> PALETTE_THEMES[paletteIndex.coerceIn(0, PALETTE_THEMES.lastIndex)]
    }

    /** Styles layered over [xmlTheme] with applyStyle. Order matters: black surfaces last so the
     *  layout overlay cannot re-lighten them mid-choice. */
    fun overlays(): List<Int> = buildList {
        if (layoutOverlay != 0) add(layoutOverlay)
        if (blackBackgrounds) add(R.style.ThemeOverlay_Yuzu_Dark)
    }

    /** Resolved night mode, with hostile persisted values folded back to follow-system. */
    fun resolvedNightMode(): Int = when (nightMode) {
        AppCompatDelegate.MODE_NIGHT_NO,
        AppCompatDelegate.MODE_NIGHT_YES,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> nightMode
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /**
     * The one darkness decision. `uiModeNightMask` comes from an activity's resolved
     * configuration — which already accounts for AppCompatDelegate's forced mode — so every
     * consumer (XML overlays and Compose) branches on the same bit.
     */
    fun isDark(uiModeNightMask: Int): Boolean =
        uiModeNightMask and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * Identity of everything an activity must be recreated to pick up. Two equal keys mean the
     * activity's inflated views already match the current decision — no recreation, ever.
     */
    val xmlKey: String = "$materialYou|$paletteIndex|$blackBackgrounds|$layoutOverlay"

    companion object {
        /**
         * Static palettes, in the exact order of res/values/arrays.xml staticThemeValues. Older
         * builds (or per-game configs) could persist past-the-end indexes, so the family-wide
         * contract is: never index into PALETTE_THEMES raw.
         */
        val PALETTE_THEMES = intArrayOf(
            R.style.Theme_suyu_Main,
            R.style.Theme_Yuzu_Main_Violet,
            R.style.Theme_Yuzu_Main_Blue,
            R.style.Theme_Yuzu_Main_Cyan,
            R.style.Theme_Yuzu_Main_Red,
            R.style.Theme_Yuzu_Main_Green,
            R.style.Theme_Yuzu_Main_Yellow,
            R.style.Theme_Yuzu_Main_Orange,
            R.style.Theme_Yuzu_Main_Pink,
            R.style.Theme_Yuzu_Main_Gray
        )

        val DEFAULT = ThemeSpec()
    }
}
