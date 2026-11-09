// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.app.Activity
import org.yuzu.yuzu_emu.R

/**
 * Slide-over window motion shared by the settings and performance screens.
 *
 * Only the incoming window is animated while opening and only the outgoing one while closing:
 * sliding both sides would drag the static window away and expose the launcher behind the app.
 * That is also why these activities can no longer open with `(0, 0)`.
 */
@Suppress("DEPRECATION")
fun Activity.applyPanelWindowMotion() {
    if (!SuyuMotion.transitionsEnabled()) {
        overridePendingTransition(R.anim.sakura_no_animation, 0)
        return
    }
    overridePendingTransition(R.anim.settings_panel_in, 0)
}

/** Arms the rightwards slide that matches [applyPanelWindowMotion]; call right before finish(). */
@Suppress("DEPRECATION")
fun Activity.applyPanelCloseMotion() {
    if (!SuyuMotion.transitionsEnabled()) {
        overridePendingTransition(0, R.anim.sakura_no_animation)
        return
    }
    overridePendingTransition(0, R.anim.settings_panel_pop_out)
}

/** Closes the activity with the matching rightwards slide. */
fun Activity.finishWithPanelMotion() {
    applyPanelCloseMotion()
    finish()
}
