// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.app.Activity
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import com.google.android.material.color.MaterialColors
import org.yuzu.yuzu_emu.NativeLibrary
import kotlin.math.roundToInt

/**
 * Turns a window into a frosted glass pane over a paused game.
 *
 * The window background is made translucent and cross-window blur softens the last emulation
 * frame behind it. Only applied when a game is actually behind the window; elsewhere the window
 * keeps its opaque surface so nothing pays for an offscreen blur pass.
 */
object WindowFrosting {
    private const val SURFACE_ALPHA = 0.90f
    private const val BLUR_RADIUS_DP = 28f

    fun apply(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!NativeLibrary.isRunning() && !NativeLibrary.isPaused()) return false

        val windowManager = activity.getSystemService(WindowManager::class.java) ?: return false
        if (!windowManager.isCrossWindowBlurEnabled) return false

        val window = activity.window
        val surface = MaterialColors.getColor(
            window.decorView,
            com.google.android.material.R.attr.colorSurface
        )
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setBackgroundDrawable(
            ColorDrawable(ThemeHelper.getColorWithOpacity(surface, SURFACE_ALPHA))
        )
        window.attributes = window.attributes.apply {
            setBlurBehindRadius(
                (BLUR_RADIUS_DP * activity.resources.displayMetrics.density).roundToInt()
            )
        }
        return true
    }
}
