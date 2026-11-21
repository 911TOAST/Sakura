// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import java.util.WeakHashMap

/**
 * Small motion kit for the brand wordmark and the About screen.
 *
 * Everything here is decorative and deliberately cheap: shader offsets and view transforms, no
 * layout passes, no bitmaps. Animators either run forever until the view is detached or are owned
 * by the caller, which cancels them in `onDestroyView`.
 */
object BrandAnimation {

    /** One running sweep. Kept so a second start, a detach or a cancel can restore the paint. */
    private class Shimmer(
        val animator: ValueAnimator,
        val shader: Shader,
        val originalColor: Int,
        val detachListener: View.OnAttachStateChangeListener
    )

    /**
     * TextView -> sweep. Weak keys: a recycled or destroyed view must not be kept alive by this
     * bookkeeping, and the state itself is dropped the moment the sweep stops.
     */
    private val shimmers = WeakHashMap<TextView, Shimmer>()

    /**
     * Sweeps a light band across the text. Height never changes; only the paint shader moves.
     *
     * The shader is only installed while attached and is always removed again, together with a
     * colour restore, when the sweep stops or the view detaches. Leaving a shader (or the old
     * transparent colour) behind is what used to make the wordmark vanish between visits.
     */
    fun startShimmer(view: TextView, duration: Long = 2600L) {
        stopShimmer(view)
        view.post { beginShimmer(view, duration) }
    }

    /** Restores the plain text colour and removes the shader for good. Safe to call twice. */
    fun stopShimmer(view: TextView) {
        val shimmer = shimmers.remove(view) ?: return
        shimmer.animator.cancel()
        view.removeOnAttachStateChangeListener(shimmer.detachListener)
        if (view.paint.shader === shimmer.shader) {
            view.paint.shader = null
        }
        view.setTextColor(shimmer.originalColor)
        view.invalidate()
    }

    private fun beginShimmer(view: TextView, duration: Long) {
        // The first post can land before the first measure pass. Waiting for a real width keeps
        // the gradient sane and avoids parking an animator on a zero-width view.
        val width = view.width.toFloat()
        if (width <= 0f || !view.isAttachedToWindow) {
            return
        }
        if (shimmers.containsKey(view)) {
            return
        }

        val originalColor = view.currentTextColor
        val base = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorOnSurface
        )
        val highlight = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorPrimary
        )

        // The gradient starts just left of the text and is translated across it.
        val shader = LinearGradient(
            -width, 0f, 0f, 0f,
            intArrayOf(base, highlight, base),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        val matrix = Matrix()
        view.paint.shader = shader
        // Deliberately leave the text colour alone. The shader supplies the colours, but the
        // paint's alpha still multiplies them: setting the text transparent (alpha 0) made the
        // whole wordmark invisible instead of golden. That was the "text disappears by itself"
        // bug, not just the un-restored colour after detach.

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val dx = -width + (it.animatedValue as Float) * (2.4f * width)
                matrix.setTranslate(dx, 0f)
                shader.setLocalMatrix(matrix)
                view.invalidate()
            }
        }

        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                // Detaching is not a cancel: the view may come back with the same instance, so
                // tear the sweep down now instead of animating an off-screen paint.
                stopShimmer(v as TextView)
            }
        }

        shimmers[view] = Shimmer(animator, shader, originalColor, detachListener)
        view.addOnAttachStateChangeListener(detachListener)
        animator.start()
    }

    /** Slow width-only breathing. Only scaleX moves, so the glyph height stays identical. */
    fun breathe(view: View, amount: Float = 1.03f, duration: Long = 2400L): Animator =
        ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, amount).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

    /** Gentle vertical drift, used for the credit icon. */
    fun float(view: View, distancePx: Float, duration: Long = 1700L): Animator =
        ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -distancePx).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

    /** Gentle rock, used for the mark next to the wordmark. */
    fun sway(view: View, degrees: Float = 4f, duration: Long = 3200L): Animator =
        ObjectAnimator.ofFloat(view, View.ROTATION, -degrees, degrees).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

    /** Nudges a chevron back and forth to hint that the card opens something. */
    fun nudgeX(view: View, distancePx: Float, duration: Long = 1200L): Animator =
        ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, distancePx).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
}
