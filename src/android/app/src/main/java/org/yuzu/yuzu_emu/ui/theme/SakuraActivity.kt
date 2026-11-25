// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.ui.theme

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.utils.recreateWithThemeMotion

/**
 * Base activity for every surface that colours itself through the theme pipeline.
 *
 * Contract:
 *  - [onCreate] applies the current [ThemeSpec] *before* `super.onCreate`, so Android inflates
 *    the activity with the selected palette. Subclasses must call `super.onCreate` only after any
 *    splash-screen setup that also runs before content inflation — the base keeps doing the theme
 *    work at the exact position the old per-activity `ThemeHelper.setTheme` calls occupied, so
 *    the released ordering (splash install → palette → super) is preserved.
 *  - On resume, the activity re-checks whether its inflated views still match the current
 *    decision. A mismatch — palette, black backgrounds or layout preset changed while this
 *    activity was in the background — recreates it once, with the shared fade motion. This is the
 *    check that used to exist only in MainActivity: activities left open under a theme switch
 *    (settings, subscreens, dashboards) no longer come back stale or half-recoloured.
 *  - Emulation opts out with [recreateOnThemeChange]: a running game must never be torn down to
 *    recolour chrome.
 */
abstract class SakuraActivity : AppCompatActivity() {
    /** Set once per inflation; compared on resume against the current spec's [ThemeSpec.xmlKey]. */
    private var appliedThemeKey: String = ""

    /** Override to false for activities that cannot afford recreation (e.g. running emulation). */
    protected open val recreateOnThemeChange: Boolean = true

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(YuzuApplication.applyLanguage(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedThemeKey = ThemeController.applyTheme(this)
        super.onCreate(savedInstanceState)
        ThemeController.applySystemBars(this)
    }

    override fun onResume() {
        super.onResume()
        ThemeController.applySystemBars(this)
        // A theme change relaunches the activity; asking for that while still half-resumed is what
        // used to crash theme switches, so: only ever recreate from a fully resumed state, and
        // never for a finished or finishing activity.
        if (!recreateOnThemeChange || isFinishing || isDestroyed) return
        if (ThemeController.current().xmlKey != appliedThemeKey) {
            recreateWithThemeMotion()
        }
    }
}
