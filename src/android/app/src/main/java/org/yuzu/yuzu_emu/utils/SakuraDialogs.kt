// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.yuzu.yuzu_emu.R

/**
 * Every MaterialAlertDialogBuilder in the app goes through here.
 *
 * Material's builder validates the context theme in its constructor and throws
 * IllegalArgumentException ("requires your app theme to be Theme.AppCompat (or a descendant)")
 * whenever the surrounding theme lost its AppCompat markers: an activity recreated while a
 * theme/night-mode switch was pending, a dialog surviving activity recreation, or an overlay
 * applied at the wrong moment (crash report issue #8, setup wizard on MIUI). Inflating the
 * builder inside an explicit Material 3 theme context keeps the dialog alive instead.
 *
 * Call sites pass any Context (activity, fragment.requireContext(), or an arbitrary context);
 * the explicit-theme wrapper is only used as a fallback, so nothing changes visually in the
 * normal case.
 */
object SakuraDialogs {
    fun builder(context: Context): MaterialAlertDialogBuilder =
        try {
            MaterialAlertDialogBuilder(context)
        } catch (_: IllegalArgumentException) {
            MaterialAlertDialogBuilder(
                ContextThemeWrapper(context, R.style.Theme_Yuzu_Main)
            )
        }
}
