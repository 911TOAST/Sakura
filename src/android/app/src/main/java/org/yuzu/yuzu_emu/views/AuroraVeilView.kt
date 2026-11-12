// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ambient aurora behind the library grid: three palette-tinted radial glows drifting on slow
 * orbits, leaning gently with the device when parallax is on.
 *
 * Cheap by construction: one hardware-accelerated draw of three gradients, invalidation capped
 * at ~30 fps, no per-frame allocation, animation stopped while detached, and a single static
 * glow when the system animator scale is zero (reduced motion).
 */
class AuroraVeilView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paints = Array(3) { Paint() }
    private var hues = floatArrayOf(210f, 252f, 168f)
    private var phase = 0f
    private var tiltX = 0f
    private var tiltY = 0f
    private var animator: ValueAnimator? = null
    private var lastFrameNs = 0L
    private val reducedMotion = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f

    /** A null seed hides the veil; otherwise the seed hue grows a triad of neighbouring glows. */
    fun setPalette(seed: Int?) {
        if (seed == null) {
            visibility = GONE
            stop()
            return
        }
        val hsv = FloatArray(3).also { Color.colorToHSV(seed, it) }
        hues = floatArrayOf(hsv[0], (hsv[0] + 42f) % 360f, (hsv[0] + 318f) % 360f)
        visibility = VISIBLE
        rebuildGradients()
        if (reducedMotion) invalidate() else start()
    }

    /** Low-passed tilt in [-1, 1]; ignored entirely for reduced-motion users. */
    fun setTilt(x: Float, y: Float) {
        if (reducedMotion) return
        tiltX = x.coerceIn(-1f, 1f)
        tiltY = y.coerceIn(-1f, 1f)
    }

    private fun rebuildGradients() {
        val radius = maxOf(width, height).coerceAtLeast(1) * 0.75f
        paints.forEachIndexed { i, paint ->
            val c = Color.HSVToColor(floatArrayOf(hues[i], 0.55f, 0.95f))
            paint.shader = RadialGradient(
                0f, 0f, radius,
                intArrayOf(Color.argb(58, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildGradients()
    }

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE || width == 0 || height == 0) return
        for (i in paints.indices) {
            val t = phase + i * 2.1f
            val cx = width * (0.5f + 0.34f * cos(t * 0.9f + i)) + tiltX * 24f
            val cy = height * (0.42f + 0.30f * sin(t * 0.7f + i * 1.7f)) + tiltY * 24f
            canvas.save()
            canvas.translate(cx, cy)
            canvas.drawPaint(paints[i])
            canvas.restore()
        }
    }

    private fun start() {
        stop()
        if (reducedMotion) return
        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 26_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                // Cap redraws at ~30 fps; the drift is slow enough that nothing is lost.
                val now = System.nanoTime()
                if (now - lastFrameNs >= 33_000_000L) {
                    lastFrameNs = now
                    postInvalidateOnAnimation()
                }
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) {
            if (reducedMotion) invalidate() else start()
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
