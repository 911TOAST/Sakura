// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.animation.TimeInterpolator
import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.R
import com.google.android.material.motion.MotionUtils

/**
 * Motion tokens, resolved from the theme exactly as Material components resolve them.
 *
 * Screen entrances/exits use the M3 Expressive durations and the "emphasized" easing family.
 * Surfaces that appear over a running scene (the boot/loading card of a launched game) use the
 * expressive spring instead, because an overshoot of a couple of percent is what makes the
 * launch read as alive rather than as a cut.
 */
object SuyuMotion {
    private const val PRESSED_SCALE = 0.97f

    /** Spring damping for launch surfaces: a small overshoot, never a bounce. */
    private const val SPRING_DAMPING = 0.78f

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

    /**
     * Attaches the same press-in/press-out feedback used by the game cards to any tappable
     * surface. Returns false from the touch listener so the view's own click and long-click
     * handlers still run untouched.
     */
    fun installPressFeedback(view: View, haptic: Boolean = false) {
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (haptic) {
                        touched.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    pressDown(touched)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressUp(touched)
            }
            false
        }
    }

    /**
     * Emphasized fade-in used when content is swapped in place (search results, empty states,
     * dialogs and newly bound rows).
     */
    fun fadeIn(view: View, fromAlpha: Float = 0f) {
        view.animate().cancel()
        view.alpha = fromAlpha
        view.animate()
            .alpha(1f)
            .setDuration(durationMedium2(view.context))
            .setInterpolator(emphasizedDecelerate(view.context))
            .start()
    }

    /**
     * Quick settle for content that is being re-filtered: the old frame stays visible, the new
     * one fades up on the emphasized curve instead of snapping.
     */
    fun settleIn(view: View) {
        view.animate().cancel()
        view.alpha = 0.55f
        view.animate()
            .alpha(1f)
            .setDuration(durationShort4(view.context))
            .setInterpolator(emphasizedDecelerate(view.context))
            .start()
    }

    /**
     * Expressive entrance for a surface that appears above a scene, e.g. the card shown while a
     * game boots: emphasized fade up plus a scale spring from [fromScale] to rest. Callers keep
     * ownership of visibility, so a card that was deliberately hidden stays hidden.
     */
    fun springIn(view: View, fromScale: Float = 0.92f) {
        view.animate().cancel()
        view.alpha = 0f
        view.scaleX = fromScale
        view.scaleY = fromScale
        view.animate()
            .alpha(1f)
            .setDuration(durationMedium2(view.context))
            .setInterpolator(emphasizedDecelerate(view.context))
            .start()
        springScale(view, DynamicAnimation.SCALE_X)
        springScale(view, DynamicAnimation.SCALE_Y)
    }

    /**
     * Mirrors [springIn] with the accelerate curve: the surface shrinks away instead of popping
     * out of existence when the scene behind it is ready.
     */
    fun springOut(view: View, toScale: Float = 0.92f, onEnd: (() -> Unit)? = null) {
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .scaleX(toScale)
            .scaleY(toScale)
            .setDuration(durationShort4(view.context))
            .setInterpolator(emphasizedAccelerate(view.context))
            .withEndAction {
                view.visibility = View.INVISIBLE
                view.scaleX = 1f
                view.scaleY = 1f
                onEnd?.invoke()
            }
            .start()
    }

    private fun springScale(view: View, property: DynamicAnimation.ViewProperty) {
        SpringAnimation(view, property, 1f).apply {
            spring = SpringForce(1f).apply {
                stiffness = SpringForce.STIFFNESS_LOW
                dampingRatio = SPRING_DAMPING
            }
            start()
        }
    }

    /**
     * Window transition for launching a game: the emulation window fades through from a slightly
     * smaller scale while the launcher it replaces pushes back. Activity-level rather than a
     * shared-element transition because the destination is a new activity whose surface is not
     * laid out yet.
     */
    fun applyEnterTransition(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                org.yuzu.yuzu_emu.R.anim.launch_open_in,
                org.yuzu.yuzu_emu.R.anim.launch_open_out
            )
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                org.yuzu.yuzu_emu.R.anim.launch_open_in,
                org.yuzu.yuzu_emu.R.anim.launch_open_out
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(
                org.yuzu.yuzu_emu.R.anim.launch_open_in,
                org.yuzu.yuzu_emu.R.anim.launch_open_out
            )
        }
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
