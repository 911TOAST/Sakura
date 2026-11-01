// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.ui

import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * Blurs a scrolling list along its direction of travel and fades the blur out when it stops.
 * The radius tracks pointer speed, and the effect is removed entirely at rest so an idle screen
 * is never composited through an offscreen layer. No-op below Android 12.
 */
class MotionBlur(
    private val view: View,
    private val maxRadiusDp: Float = 5f,
) : RecyclerView.OnScrollListener() {

    private val density = view.resources.displayMetrics.density
    private val maxRadiusPx = maxRadiusDp * density

    private var radiusX = 0f
    private var radiusY = 0f
    private var lastScrollMs = 0L
    private var lastFrameMs = 0L
    private var decaying = false

    private val decayRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val frameMs = (now - lastFrameMs).coerceIn(1L, 100L).toFloat()
            lastFrameMs = now
            val factor = exp(-frameMs / 90f)
            radiusX *= factor
            radiusY *= factor
            if (radiusX < 0.35f && radiusY < 0.35f) {
                clear()
            } else {
                apply()
                view.postOnAnimation(this)
            }
        }
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        if (!canBlur() || (dx == 0 && dy == 0)) {
            return
        }
        val now = SystemClock.uptimeMillis()
        val elapsedMs = (now - lastScrollMs).coerceIn(1L, 64L)
        lastScrollMs = now

        val distance = hypot(dx.toFloat(), dy.toFloat())
        val speedDpPerSecond = distance / density / (elapsedMs / 1000f)
        val magnitude = maxRadiusPx * (speedDpPerSecond / 1000f).coerceIn(0f, 1f)
        if (magnitude < 0.35f) {
            return
        }
        radiusX = magnitude * min(1f, abs(dx) / distance)
        radiusY = magnitude * min(1f, abs(dy) / distance)
        apply()

        if (!decaying) {
            decaying = true
            lastFrameMs = now
            view.postOnAnimation(decayRunnable)
        }
    }

    fun stop() {
        clear()
    }

    private fun canBlur(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ValueAnimator.areAnimatorsEnabled()

    private fun apply() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(
                RenderEffect.createBlurEffect(radiusX, radiusY, Shader.TileMode.CLAMP)
            )
        }
    }

    private fun clear() {
        view.removeCallbacks(decayRunnable)
        decaying = false
        radiusX = 0f
        radiusY = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }
}
