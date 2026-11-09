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
import android.view.animation.OvershootInterpolator
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.R
import com.google.android.material.motion.MotionUtils
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting

/**
 * Motion tokens, resolved from the theme exactly as Material components resolve them.
 *
 * The user can switch between three interface styles in Interface > Motion:
 * [STYLE_TWEEN] glides with fixed curves, [STYLE_KINETIC] uses spring physics, and
 * [STYLE_INSTANT] removes animation. All durations pass through [scaled], so the animation speed
 * slider applies to every screen that uses these tokens.
 */
object SuyuMotion {
    const val STYLE_TWEEN = 0
    const val STYLE_KINETIC = 1
    const val STYLE_INSTANT = 2

    /** Live springs per view/property so a new gesture cancels the animation it replaces. */
    private val springAnimations:
        MutableMap<View, MutableMap<DynamicAnimation.ViewProperty, SpringAnimation>> =
        java.util.WeakHashMap()

    /** Current interface animation style. Falls back to tween if the setting is unavailable. */
    fun style(): Int = runCatching {
        IntSetting.UI_MOTION_STYLE.getInt()
    }.getOrDefault(STYLE_TWEEN).coerceIn(STYLE_TWEEN, STYLE_INSTANT)

    fun isKinetic(): Boolean = style() == STYLE_KINETIC

    fun isInstant(): Boolean = style() == STYLE_INSTANT

    fun animationsEnabled(): Boolean = !isInstant()

    fun pressFeedbackEnabled(): Boolean = runCatching {
        BooleanSetting.UI_PRESS_FEEDBACK.getBoolean()
    }.getOrDefault(true)

    fun hapticFeedbackEnabled(): Boolean = runCatching {
        BooleanSetting.UI_HAPTIC_FEEDBACK.getBoolean()
    }.getOrDefault(true)

    fun entrancesEnabled(): Boolean = animationsEnabled() && runCatching {
        BooleanSetting.UI_ENTRANCE_ANIMATION.getBoolean()
    }.getOrDefault(true)

    fun transitionsEnabled(): Boolean = animationsEnabled() && runCatching {
        BooleanSetting.UI_TRANSITION_ANIMATION.getBoolean()
    }.getOrDefault(true)

    private fun motionScale(): Float = runCatching {
        IntSetting.UI_MOTION_SCALE.getInt()
    }.getOrDefault(100).coerceIn(50, 200) / 100f

    private fun pressedScale(): Float = runCatching {
        IntSetting.UI_PRESS_SCALE.getInt()
    }.getOrDefault(97).coerceIn(90, 100) / 100f

    private fun springDamping(): Float = 1f - runCatching {
        IntSetting.UI_MOTION_BOUNCE.getInt()
    }.getOrDefault(22).coerceIn(0, 50) / 100f

    private fun scaled(context: Context, base: Long): Long {
        if (!animationsEnabled()) {
            return 0L
        }
        return (base * motionScale()).toLong().coerceAtLeast(1L)
    }

    // M3 durations (ms) with the spec defaults as fallbacks.
    fun durationShort2(context: Context): Long =
        scaled(
            context,
            MotionUtils.resolveThemeDuration(context, R.attr.motionDurationShort2, 100).toLong()
        )

    fun durationShort4(context: Context): Long =
        scaled(
            context,
            MotionUtils.resolveThemeDuration(context, R.attr.motionDurationShort4, 200).toLong()
        )

    fun durationMedium2(context: Context): Long =
        scaled(
            context,
            MotionUtils.resolveThemeDuration(context, R.attr.motionDurationMedium2, 300).toLong()
        )

    fun durationMedium4(context: Context): Long =
        scaled(
            context,
            MotionUtils.resolveThemeDuration(context, R.attr.motionDurationMedium4, 400).toLong()
        )

    fun durationLong2(context: Context): Long =
        scaled(
            context,
            MotionUtils.resolveThemeDuration(context, R.attr.motionDurationLong2, 500).toLong()
        )

    /** Stagger between successive list rows, in the user's animation speed. */
    fun staggerDelay(context: Context, position: Int): Long =
        scaled(context, (position.coerceAtMost(8) * 28).toLong())

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
     * Press feedback: tween shrink in tween mode, spring to the configured press depth in kinetic
     * mode. Instant mode and the press-feedback switch both leave the view untouched.
     */
    fun pressDown(view: View) {
        if (!pressFeedbackEnabled() || isInstant()) {
            return
        }

        val target = pressedScale()
        if (isKinetic()) {
            animateSpring(
                view,
                DynamicAnimation.SCALE_X,
                target,
                SpringForce.STIFFNESS_MEDIUM
            )
            animateSpring(
                view,
                DynamicAnimation.SCALE_Y,
                target,
                SpringForce.STIFFNESS_MEDIUM
            )
            return
        }

        view.animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(durationShort2(view.context))
            .setInterpolator(emphasizedAccelerate(view.context))
            .start()
    }

    /**
     * Releases a pressed view back to rest, using the same style as [pressDown].
     */
    fun pressUp(view: View) {
        if (!pressFeedbackEnabled() || isInstant()) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }

        if (isKinetic()) {
            animateSpring(view, DynamicAnimation.SCALE_X, 1f, SpringForce.STIFFNESS_LOW)
            animateSpring(view, DynamicAnimation.SCALE_Y, 1f, SpringForce.STIFFNESS_LOW)
            return
        }

        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(durationShort4(view.context))
            .setInterpolator(emphasizedDecelerate(view.context))
            .start()
    }

    /** Plays the configured UI haptic, if the user has enabled it. */
    fun performHaptic(view: View, requested: Boolean = true) {
        if (requested && hapticFeedbackEnabled()) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    /**
     * Attaches press-in/press-out feedback to any tappable surface. Returns false from the touch
     * listener so the view's own click and long-click handlers still run untouched.
     */
    fun installPressFeedback(view: View, haptic: Boolean = false) {
        if (!pressFeedbackEnabled() || isInstant()) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }

        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performHaptic(touched, haptic)
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
        cancelSprings(view)
        view.alpha = fromAlpha
        if (isInstant()) {
            view.alpha = 1f
            return
        }
        if (isKinetic()) {
            animateSpring(view, DynamicAnimation.ALPHA, 1f, SpringForce.STIFFNESS_LOW)
            return
        }
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
        cancelSprings(view)
        view.alpha = 0.55f
        if (isInstant()) {
            view.alpha = 1f
            return
        }
        if (isKinetic()) {
            animateSpring(view, DynamicAnimation.ALPHA, 1f, SpringForce.STIFFNESS_MEDIUM)
            return
        }
        view.animate()
            .alpha(1f)
            .setDuration(durationShort4(view.context))
            .setInterpolator(emphasizedDecelerate(view.context))
            .start()
    }

    /**
     * Expressive entrance for a surface that appears above a scene, e.g. the card shown while a
     * game boots. Callers keep ownership of visibility, so a card that was deliberately hidden
     * stays hidden.
     */
    fun springIn(view: View, fromScale: Float = 0.92f) {
        view.animate().cancel()
        cancelSprings(view)
        view.alpha = 0f
        view.scaleX = fromScale
        view.scaleY = fromScale

        if (isInstant()) {
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }

        if (isKinetic()) {
            animateSpring(view, DynamicAnimation.ALPHA, 1f, SpringForce.STIFFNESS_LOW)
            animateSpring(view, DynamicAnimation.SCALE_X, 1f, SpringForce.STIFFNESS_LOW)
            animateSpring(view, DynamicAnimation.SCALE_Y, 1f, SpringForce.STIFFNESS_LOW)
            return
        }

        view.animate()
            .alpha(1f)
            .setDuration(durationMedium2(view.context))
            .setInterpolator(emphasizedDecelerate(view.context))
            .start()
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(durationMedium4(view.context))
            .setInterpolator(OvershootInterpolator(0.7f))
            .start()
    }

    /**
     * Mirrors [springIn] with the accelerate curve: the surface shrinks away instead of popping
     * out of existence when the scene behind it is ready.
     */
    fun springOut(view: View, toScale: Float = 0.92f, onEnd: (() -> Unit)? = null) {
        view.animate().cancel()
        cancelSprings(view)

        if (isInstant()) {
            view.alpha = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.visibility = View.INVISIBLE
            onEnd?.invoke()
            return
        }

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

    private fun cancelSprings(view: View) {
        springAnimations.remove(view)?.values?.forEach { it.cancel() }
    }

    private fun animateSpring(
        view: View,
        property: DynamicAnimation.ViewProperty,
        target: Float,
        stiffness: Float
    ) {
        val perView = springAnimations.getOrPut(view) { HashMap() }
        perView[property]?.cancel()

        val animation = SpringAnimation(view, property, target).apply {
            spring = SpringForce(target).apply {
                this.stiffness = stiffness
                dampingRatio = springDamping()
            }
        }
        perView[property] = animation
        animation.addEndListener { _, _, _, _ ->
            if (perView[property] === animation) {
                perView.remove(property)
            }
        }
        animation.start()
    }

    /**
     * Window transition for launching a game: the emulation window fades through from a slightly
     * smaller scale while the launcher it replaces pushes back. Activity-level rather than a
     * shared-element transition because the destination is a new activity whose surface is not
     * laid out yet.
     */
    fun applyEnterTransition(activity: Activity) {
        val enabled = transitionsEnabled()
        val enter = if (enabled) {
            org.yuzu.yuzu_emu.R.anim.launch_open_in
        } else {
            org.yuzu.yuzu_emu.R.anim.sakura_no_animation
        }
        val exit = if (enabled) {
            org.yuzu.yuzu_emu.R.anim.launch_open_out
        } else {
            org.yuzu.yuzu_emu.R.anim.sakura_no_animation
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                enter,
                exit
            )
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                enter,
                exit
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(enter, exit)
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
