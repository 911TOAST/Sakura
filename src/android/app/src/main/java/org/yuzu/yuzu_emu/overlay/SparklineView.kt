// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SparklineView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    def: Int = 0
) : View(ctx, attrs, def) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = FloatArray(64)
    private var n = 0
    private var lo = 0f
    private var hi = 1f

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
    }

    fun setColor(c: Int) {
        paint.color = c
        invalidate()
    }

    fun push(buf: DoubleArray, count: Int) {
        val m = if (count < ring.size) count else ring.size
        if (m <= 0) {
            n = 0
            invalidate()
            return
        }
        var mn = Float.MAX_VALUE
        var mx = 0f
        for (i in 0 until m) {
            val v = buf[i].toFloat()
            ring[i] = v
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        n = m
        lo = mn
        hi = if (mx > mn) mx else mn + 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (n < 2) {
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val span = hi - lo
        val step = w / (n - 1)
        var px = 0f
        var py = h - (ring[0] - lo) / span * h
        for (i in 1 until n) {
            val x = i * step
            val y = h - (ring[i] - lo) / span * h
            canvas.drawLine(px, py, x, y, paint)
            px = x
            py = y
        }
    }
}