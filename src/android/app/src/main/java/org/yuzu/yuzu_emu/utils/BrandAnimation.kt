// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import com.google.android.material.color.MaterialColors

/**
 * Small motion kit for the brand wordmark and the About screen.
 *
 * Everything here is decorative and deliberately cheap: shader offsets and view transforms, no
 * layout passes, no bitmaps. Animators either run forever until the view is detached or are owned
 * by the caller, which cancels them in `onDestroyView`.
 */
object BrandAnimation {

    /** Sweeps a light band across the text. Height never changes; only the paint shader moves. */
    fun startShimmer(view: TextView, duration: Long = 2600L) {
        view.post {
            val width = view.width.toFloat()
            if (width <= 0f) {
                return@post
            }

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
            view.setTextColor(Color.TRANSPARENT)

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
                start()
            }

            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    animator.cancel()
                    view.paint.shader = null
                }
            })
        }
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
