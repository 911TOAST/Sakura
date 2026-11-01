// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.animation.TimeInterpolator
import android.content.Context
import android.view.View
import androidx.core.view.animation.PathInterpolatorCompat
import com.google.android.material.R
import com.google.android.material.motion.MotionUtils

/**
 * Raw Material 3 motion tokens, resolved from the theme exactly as Material
 * components resolve them. No custom springs, no bounce: entrances/exits use
 * the spec durations with the "emphasized" easing family.
 */
object SuyuMotion {
    private const val PRESSED_SCALE = 0.97f

    // M3 durations (ms) with the spec defaults as fallbacks.
    fun durationShort2(context: Context): Long =
        MotionUtils.resolveThemeDuration(context, R.attr.motionDurationShort2, 100).toLong()

    fun durationShort4(context: Context): Long =
        MotionUtils.resolveThemeDuration(context, R.attr.motionDurationShort4, 200).toLong()

    fun durationMedium2(context: Context): Long =
        MotionUtils.resolveThemeDuration(context, R.attr.motionDurationMedium2, 300).toLong()

    fun durationMedium4(context: Context): Long =
        MotionUtils.resolveThemeDuration(context, R.attr.motionDurationMedium4, 400).toLong()

    fun durationLong2(context: Context): Long =
        MotionUtils.resolveThemeDuration(context, R.attr.motionDurationLong2, 500).toLong()

    // M3 easings, with the Motion spec cubic paths as fallbacks.
    fun emphasized(context: Context): TimeInterpolator =
        resolveEasing(context, R.attr.motionEasingEmphasizedInterpolator, 0.2f, 0f, 0f, 1f)

    fun emphasizedAccelerate(context: Context): TimeInterpolator =
        resolveEasing(
            context,
            R.attr.motionEasingEmphasizedAccelerateInterpolator,
            0.3f, 0f, 0.8f, 0.15f
        )

    fun emphasizedDecelerate(context: Context): TimeInterpolator =
        resolveEasing(
            context,
            R.attr.motionEasingEmphasizedDecelerateInterpolator,
            0.05f, 0.7f, 0.1f, 1f
        )

    fun standard(context: Context): TimeInterpolator =
        resolveEasing(context, R.attr.motionEasingStandardInterpolator, 0.2f, 0f, 0f, 1f)

    /**
     * M3 press physics: quick emphasized press-in on touch down.
     */
    fun pressDown(view: View) {
        view.animate()
            .scaleX(PRESSED_SCALE)
            .scaleY(PRESSED_SCALE)
            .setDuration(durationShort2(view.context))
            .setInterpolator(emphasizedAccelerate(view.context))
            .start()
    }

    /**
     * M3 press physics: spring-free emphasized release back to rest.
     */
    fun pressUp(view: View) {
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(durationShort4(view.context))
            .setInterpolator(emphasizedDecelerate(view.context))
            .start()
    }

    private fun resolveEasing(
        context: Context,
        attrId: Int,
        a: Float,
        b: Float,
        c: Float,
        d: Float
    ): TimeInterpolator =
        MotionUtils.resolveThemeInterpolator(
            context,
            attrId,
            PathInterpolatorCompat.create(a, b, c, d)
        )
}
