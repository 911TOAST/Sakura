// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.ui.theme

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.utils.Log
import org.yuzu.yuzu_emu.utils.UiLayout
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * The one pipeline everything theming-related flows through.
 *
 * Rules of the pipeline:
 *  1. [spec] is the only copy of the decision. Any code that needs to know the palette, the dark
 *     mode or the Material You state asks for it — never for a raw setting, and never by re-deriving.
 *  2. Settings UI only pushes values into native config/shared prefs and calls [refresh]. It never
 *     applies themes itself beyond scheduling its own recreation.
 *  3. Activities are themed once, by [SakuraActivity], before their content inflates. No activity
 *     touches native settings to colour itself.
 *  4. Reading persisted theme values can only fail *into* the last known good value: a guarded
 *     native read falls back to a JSON cache in SharedPreferences, so a config hiccup degrades to
 *     "the theme you had" instead of defaults or a crash.
 */
object ThemeController {
    private const val TAG = "ThemeController"
    private const val CACHE_KEY = "theme_spec_cache"

    private val _spec = MutableStateFlow(cachedSpec())

    val spec: StateFlow<ThemeSpec> = _spec.asStateFlow()

    fun current(): ThemeSpec = _spec.value

    /** Re-read all persisted theme inputs and adopt them if anything changed. */
    fun refresh() {
        val next = readSpecOrDefault()
        if (next != _spec.value) {
            // Log.* is a JNI call; failure to log must never fail the theme update.
            runCatching { Log.info(TAG, "Theme decision updated: $next") }
            _spec.value = next
        }
        syncNightMode()
    }

    /** Propagates the resolved night mode; AppCompat recreates activities when it changes. */
    fun syncNightMode(candidate: ThemeSpec = _spec.value) {
        val resolved = candidate.resolvedNightMode()
        if (AppCompatDelegate.getDefaultNightMode() != resolved) {
            AppCompatDelegate.setDefaultNightMode(resolved)
        }
    }

    /**
     * Applies the current decision to an activity. Must run before `super.onCreate` so Android
     * inflates the activity with the right palette, then overlays go on top of that style.
     * Returns the [ThemeSpec.xmlKey] the activity was inflated with.
     */
    fun applyTheme(activity: AppCompatActivity): String {
        val s = current()
        activity.setTheme(s.xmlTheme())
        s.overlays().forEach { activity.theme.applyStyle(it, true) }
        // Theme_Yuzu_Main_MaterialYou only carries the fallback tokens; the real
        // wallpaper-derived palette arrives through this runtime overlay on 12+.
        if (s.usesDynamicColor()) {
            runCatching { DynamicColors.applyIfAvailable(activity) }
        }
        syncNightMode(s)
        return s.xmlKey
    }

    /** System bar icon contrast, derived from the single darkness decision. */
    fun applySystemBars(activity: AppCompatActivity) {
        val windowController = WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView
        )
        val dark = current().isDark(
            activity.resources.configuration.uiMode
        )
        windowController.isAppearanceLightStatusBars = !dark
        windowController.isAppearanceLightNavigationBars = !dark
    }

    // ------------------------------------------------------------------
    // Reading persisted inputs
    // ------------------------------------------------------------------

    /** Guarded read: native config first, cached last-known-good on any failure. */
    private fun readSpecOrDefault(): ThemeSpec = try {
        readSpecFromConfig().also { cacheSpec(it) }
    } catch (t: Throwable) {
        runCatching { Log.warning(TAG, "Falling back to cached theme spec: ${t.message}") }
        cachedSpec()
    }

    private fun readSpecFromConfig(): ThemeSpec {
        val context = YuzuApplication.appContext
        val layoutOverlay = UiLayout.current(context).themeOverlay
        return ThemeSpec(
            nightMode = IntSetting.THEME_MODE.getInt(needsGlobal = true),
            materialYou = IntSetting.THEME.getInt(needsGlobal = true) == MATERIAL_YOU,
            paletteIndex = IntSetting.STATIC_THEME_COLOR.getInt(needsGlobal = true)
                .coerceIn(0, ThemeSpec.PALETTE_THEMES.lastIndex),
            blackBackgrounds = BooleanSetting.BLACK_BACKGROUNDS.getBoolean(needsGlobal = true),
            layoutOverlay = layoutOverlay,
        )
    }

    /**
     * The cache exists so a failed native read degrades to "last known theme" instead of
     * defaults. It is only ever written after a successful full read, and it stays app-private.
     */
    private fun cachedSpec(): ThemeSpec = try {
        val json = PreferenceManager
            .getDefaultSharedPreferences(YuzuApplication.appContext)
            .getString(CACHE_KEY, null) ?: return ThemeSpec.DEFAULT
        ThemeSpec(
            nightMode = json.optInt("nightMode", ThemeSpec.DEFAULT.nightMode),
            materialYou = json.optBoolean("materialYou", false),
            paletteIndex = json.optInt("palette", 0),
            blackBackgrounds = json.optBoolean("blackBackgrounds", false),
            layoutOverlay = json.optInt("layoutOverlay", 0),
        )
    } catch (t: Throwable) {
        ThemeSpec.DEFAULT
    }

    private fun cacheSpec(spec: ThemeSpec) = try {
        PreferenceManager
            .getDefaultSharedPreferences(YuzuApplication.appContext)
            .edit {
                putString(
                    CACHE_KEY,
                    JSONObject()
                        .put("nightMode", spec.nightMode)
                        .put("materialYou", spec.materialYou)
                        .put("palette", spec.paletteIndex)
                        .put("blackBackgrounds", spec.blackBackgrounds)
                        .put("layoutOverlay", spec.layoutOverlay)
                        .toString()
                )
            }
    } catch (t: Throwable) {
        // A caching failure must never upset the actual theming.
    }

    private const val MATERIAL_YOU = 1
}
