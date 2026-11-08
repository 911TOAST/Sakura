// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.content.SharedPreferences
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
import androidx.preference.PreferenceManager
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.features.settings.model.Settings
import java.lang.ref.WeakReference

object ThemeHelper {
    const val SYSTEM_BAR_ALPHA = 0.9f

    // Listener that detects if the theme keys are being changed from the setting menu and recreates the activity
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val preferences = PreferenceManager.getDefaultSharedPreferences(
        YuzuApplication.appContext
    )

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
        // The theme resource comes first: setThemeMode() touches activity.window, and the window
        // decor is built from whichever theme is installed at that moment. Applying the theme
        // afterwards styled the decor with the previous theme (and, with a missing resource, took
        // the activity down instead of just looking wrong).
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

        // Using a specific night mode check because this could apply incorrectly when using the
        // light app mode, dark system mode, and black backgrounds. Launching the settings activity
        // will then show light mode colors/navigation bars but with black backgrounds.
        if (isNightMode(activity) && runCatching { BooleanSetting.BLACK_BACKGROUNDS.getBoolean() }
                .getOrDefault(false)
        ) {
            activity.setTheme(R.style.ThemeOverlay_Yuzu_Dark)
        }

        setThemeMode(activity)
    }

    private fun isMaterialYou(): Boolean =
        runCatching { Theme.from(IntSetting.THEME.getInt()) == Theme.MaterialYou }
            .getOrDefault(false)

    private fun getSelectedStaticThemeColor(): Int {
        val themeIndex = runCatching { IntSetting.STATIC_THEME_COLOR.getInt(false) }.getOrDefault(0)
        return STATIC_THEMES[themeIndex.coerceIn(0, STATIC_THEMES.lastIndex)]
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

    /**
     * Re-applies the theme on resume. Views are already inflated at that point, so a real change is
     * only visible after a relaunch: recreate exactly once and only while the activity is still
     * alive, which is what used to turn "pick a colour in settings" into a crash loop.
     *
     * Night mode is deliberately not compared here: AppCompatDelegate owns that relaunch when the
     * theme mode value actually changes, and requesting a second recreate from onResume is exactly
     * the double relaunch that crashed the activity.
     */
    fun setCorrectTheme(activity: AppCompatActivity) {
        val provider = activity as? ThemeProvider
        val previousThemeId = provider?.themeId ?: 0

        setTheme(activity)

        if (previousThemeId == (provider?.themeId ?: previousThemeId)) {
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        activity.recreate()
    }

    fun setThemeMode(activity: AppCompatActivity) {
        val themeMode = runCatching { IntSetting.THEME_MODE.getInt() }
            .getOrDefault(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        activity.delegate.localNightMode = themeMode
        val windowController = WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView
        )
        when (themeMode) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> when (isNightMode(activity)) {
                false -> setLightModeSystemBars(windowController)
                true -> setDarkModeSystemBars(windowController)
            }
            AppCompatDelegate.MODE_NIGHT_NO -> setLightModeSystemBars(windowController)
            AppCompatDelegate.MODE_NIGHT_YES -> setDarkModeSystemBars(windowController)
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

    fun ThemeChangeListener(activity: AppCompatActivity) {
        // Every activity instance used to add another listener that captured it, so after a couple
        // of theme changes the old (destroyed) activities were still being recreated from the
        // preference callback. Drop the previous one first and keep only a weak reference.
        listener?.let { preferences.unregisterOnSharedPreferenceChangeListener(it) }
        val activityRef = WeakReference(activity)
        val newListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key !in listOf(
                    Settings.PREF_STATIC_THEME_COLOR,
                    Settings.PREF_THEME_MODE,
                    Settings.PREF_BLACK_BACKGROUNDS
                )
            ) {
                return@OnSharedPreferenceChangeListener
            }
            val target = activityRef.get() ?: return@OnSharedPreferenceChangeListener
            if (target.isFinishing || target.isDestroyed) {
                return@OnSharedPreferenceChangeListener
            }
            target.recreate()
        }
        listener = newListener
        preferences.registerOnSharedPreferenceChangeListener(newListener)
    }
}

enum class Theme(val int: Int) {
    Default(0),
    MaterialYou(1);

    companion object {
        fun from(int: Int): Theme = entries.firstOrNull { it.int == int } ?: Default
    }
}
