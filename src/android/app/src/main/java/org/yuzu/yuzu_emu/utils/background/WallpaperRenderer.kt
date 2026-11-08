// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils.background

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.ImageView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** One measured transform and black dimming layer for both the editor and the real library. */
class WallpaperRenderer(private val image: ImageView, private val scrim: View) {
    private var tuning = WallpaperTuning()
    private var bound: Bitmap? = null
    private var appliedDim = -1
    private var appliedBlur = -1
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTransform() }

    init {
        image.scaleType = ImageView.ScaleType.MATRIX
        image.scaleX = 1f
        image.scaleY = 1f
        image.addOnLayoutChangeListener(layoutListener)
    }

    fun render(bitmap: Bitmap?, value: WallpaperTuning) {
        if (bitmap !== bound) {
            bound = bitmap
            image.setImageBitmap(bitmap)
        }
        image.visibility = if (bitmap == null) View.INVISIBLE else View.VISIBLE
        scrim.visibility = if (bitmap == null) View.INVISIBLE else View.VISIBLE
        update(value)
    }

    fun update(value: WallpaperTuning) {
        tuning = value.clamped()
        if (tuning.dim != appliedDim) {
            appliedDim = tuning.dim
            scrim.setBackgroundColor(Color.argb((tuning.dim * 255f / 100).roundToInt(), 0, 0, 0))
        }
        if (tuning.blur != appliedBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appliedBlur = tuning.blur
            image.setRenderEffect(if (tuning.blur == 0) null else RenderEffect.createBlurEffect(
                tuning.blur.toFloat(), tuning.blur.toFloat(), Shader.TileMode.CLAMP
            ))
        }
        updateTransform()
    }

    private fun updateTransform() {
        val drawable = image.drawable ?: return
        if (image.width <= 0 || image.height <= 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return
        val transform = wallpaperTransform(drawable.intrinsicWidth, drawable.intrinsicHeight, image.width, image.height, tuning)
        image.imageMatrix = Matrix().apply {
            setScale(transform.scaleX, transform.scaleY)
            postTranslate(transform.dx, transform.dy)
        }
        // Fit's letterboxing stays the static launcher surface, including at 100% dimming.
        scrim.clipBounds = if (tuning.fit == WallpaperTuning.FIT) Rect(
            floor(transform.dx).toInt().coerceAtLeast(0),
            floor(transform.dy).toInt().coerceAtLeast(0),
            ceil(transform.dx + drawable.intrinsicWidth * transform.scaleX).toInt().coerceAtMost(image.width),
            ceil(transform.dy + drawable.intrinsicHeight * transform.scaleY).toInt().coerceAtMost(image.height)
        ) else null
    }

    fun reposition(start: WallpaperTuning, deltaX: Float, deltaY: Float): WallpaperTuning {
        val drawable = image.drawable ?: return start
        if (start.fit != WallpaperTuning.FILL || image.width <= 0 || image.height <= 0) return start
        val transform = wallpaperTransform(drawable.intrinsicWidth, drawable.intrinsicHeight, image.width, image.height, start)
        val overflowX = image.width - drawable.intrinsicWidth * transform.scaleX
        val overflowY = image.height - drawable.intrinsicHeight * transform.scaleY
        return start.copy(
            x = if (overflowX < -0.5f) (start.x + deltaX * 100 / overflowX).roundToInt() else 50,
            y = if (overflowY < -0.5f) (start.y + deltaY * 100 / overflowY).roundToInt() else 50
        ).clamped()
    }

    fun close() {
        image.removeOnLayoutChangeListener(layoutListener)
        bound = null
        appliedDim = -1
        appliedBlur = -1
        image.setImageDrawable(null)
        scrim.visibility = View.INVISIBLE
        scrim.clipBounds = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) image.setRenderEffect(null)
    }
}
