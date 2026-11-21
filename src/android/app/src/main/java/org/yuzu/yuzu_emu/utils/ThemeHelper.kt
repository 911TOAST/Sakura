// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.DynamicColors
import kotlin.math.roundToInt
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.ui.main.ThemeProvider

object ThemeHelper {
    const val SYSTEM_BAR_ALPHA = 0.9f

    /**
     * Static Sakura palettes, in the order of res/values/arrays.xml staticThemeValues. Kept as a
     * list so the persisted index is always clamped: a value written by an older build (or a
     * per-game config) used to index past the end and crash the whole app on the next start.
     */
    private val STATIC_THEMES = intArrayOf(
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

    fun setTheme(activity: AppCompatActivity) {
        // This method is called before super.onCreate so Android inflates the activity with the
        // selected palette. Keep night-mode changes app-wide; setting a local mode per activity
        // creates independent recreation loops when multiple activities are alive.
        if (isMaterialYou()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.setTheme(R.style.Theme_Yuzu_Main_MaterialYou)
                // Apply the real wallpaper-derived Material You palette on top of
                // the static Sakura theme so colors follow the system, day/night aware.
                runCatching { DynamicColors.applyIfAvailable(activity) }
            } else {
                activity.setTheme(getSelectedStaticThemeColor())
            }
        } else {
            activity.setTheme(getSelectedStaticThemeColor())
        }

        if (isNightMode(activity) && runCatching {
                BooleanSetting.BLACK_BACKGROUNDS.getBoolean(needsGlobal = true)
            }.getOrDefault(false)
        ) {
            activity.setTheme(R.style.ThemeOverlay_Yuzu_Dark)
        }

        // The layout preset only touches shape tokens, so it can be layered on top of whichever
        // palette was selected above instead of replacing the theme.
        val layoutOverlay = UiLayout.current(activity).themeOverlay
        if (layoutOverlay != 0) {
            activity.theme.applyStyle(layoutOverlay, true)
        }
        setThemeMode(activity)
        (activity as? ThemeProvider)?.themeSignature = getThemeSignature(activity)
    }

    private fun isMaterialYou(): Boolean =
        runCatching { Theme.from(IntSetting.THEME.getInt(needsGlobal = true)) == Theme.MaterialYou }
            .getOrDefault(false)

    private fun getSelectedStaticThemeColor(): Int {
        val themeIndex = runCatching {
            IntSetting.STATIC_THEME_COLOR.getInt(needsGlobal = true)
        }.getOrDefault(0)
        return STATIC_THEMES[themeIndex.coerceIn(0, STATIC_THEMES.lastIndex)]
    }

    private fun getThemeSignature(activity: AppCompatActivity): String {
        val materialYou = isMaterialYou() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val staticTheme = if (materialYou) 0 else getSelectedStaticThemeColor()
        val blackBackgrounds = runCatching {
            BooleanSetting.BLACK_BACKGROUNDS.getBoolean(needsGlobal = true)
        }.getOrDefault(false)
        return "$materialYou:$staticTheme:$blackBackgrounds:${UiLayout.current(activity).themeOverlay}"
    }

    private fun readNightMode(): Int {
        val mode = runCatching { IntSetting.THEME_MODE.getInt(needsGlobal = true) }
            .getOrDefault(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        return when (mode) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES -> mode
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    /** Call once after global config loads and again when the user changes the app theme mode. */
    fun applyDefaultNightMode() = setDefaultNightMode(readNightMode())

    fun setDefaultNightMode(mode: Int) {
        val safeMode = when (mode) {
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES -> mode
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != safeMode) {
            AppCompatDelegate.setDefaultNightMode(safeMode)
        }
    }

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

    /** Recreate only when a non-night-mode theme selection changed while this activity was away. */
    fun setCorrectTheme(activity: AppCompatActivity) {
        val provider = activity as? ThemeProvider
        setThemeMode(activity)
        if (provider == null || provider.themeSignature == getThemeSignature(activity)) {
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        activity.recreateWithThemeMotion()
    }

    fun setThemeMode(activity: AppCompatActivity) {
        val windowController = WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView
        )
        when (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> setDarkModeSystemBars(windowController)
            else -> setLightModeSystemBars(windowController)
        }
    }

    private fun isNightMode(activity: AppCompatActivity): Boolean {
        return when (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_NO -> false
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }

    private fun setLightModeSystemBars(windowController: WindowInsetsControllerCompat) {
        windowController.isAppearanceLightStatusBars = true
        windowController.isAppearanceLightNavigationBars = true
    }

    private fun setDarkModeSystemBars(windowController: WindowInsetsControllerCompat) {
        windowController.isAppearanceLightStatusBars = false
        windowController.isAppearanceLightNavigationBars = false
    }
}

enum class Theme(val int: Int) {
    Default(0),
    MaterialYou(1);

    companion object {
        fun from(int: Int): Theme = entries.firstOrNull { it.int == int } ?: Default
    }
}
