// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.animation.PathInterpolatorCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR
import org.yuzu.yuzu_emu.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * Material 3 Expressive wavy linear progress.
 *
 * The track is a sine wave instead of a straight bar: the indeterminate state sends a wave
 * packet travelling along a rippling line, and the determinate state fills the same wave up to
 * [progress]. Track height, amplitude and wave length are styleable dimensions, so one class
 * covers everything from a 4dp hairline to a 10dp hero bar in the loading surface.
 *
 * The animators only run while the view is actually visible: the emulation loading card is
 * hidden for the whole session, and a progress bar must not keep a choreographer awake while a
 * game is rendering.
 */
class WavyProgressIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val DEFAULT_THICKNESS_DP = 6f
        const val DEFAULT_WAVE_LENGTH_DP = 32f

        /** Share of the track covered by the travelling wave packet. */
        const val ACTIVE_FRACTION = 0.34f
        const val INDETERMINATE_DURATION_MS = 1500L
        const val PROGRESS_DURATION_MS = 280L
    }

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Stroke height of the wave. The view measures itself this much plus twice [waveAmplitude]. */
    var trackThickness: Float = DEFAULT_THICKNESS_DP * density
        set(value) {
            field = value.coerceAtLeast(1f)
            trackPaint.strokeWidth = field
            indicatorPaint.strokeWidth = field
            requestLayout()
            invalidate()
        }

    /** How far the line rises and falls around its centre line. */
    var waveAmplitude: Float = 3f * density
        set(value) {
            field = value.coerceAtLeast(0f)
            requestLayout()
            invalidate()
        }

    /** Horizontal distance covered by one full wave. */
    var waveLength: Float = DEFAULT_WAVE_LENGTH_DP * density
        set(value) {
            field = value.coerceAtLeast(12f * density)
            invalidate()
        }

    var trackColor: Int =
        themeColor(MaterialR.attr.colorSurfaceContainerHighest, R.color.suyu_surfaceVariant)
        set(value) {
            field = value
            trackPaint.color = value
            invalidate()
        }

    var indicatorColor: Int = themeColor(MaterialR.attr.colorPrimary, R.color.suyu_primary)
        set(value) {
            field = value
            indicatorPaint.color = value
            invalidate()
        }

    var isIndeterminate: Boolean = true
        set(value) {
            if (field == value) {
                return
            }
            field = value
            displayedProgress = progress.toFloat()
            progressAnimator?.cancel()
            updateAnimator()
            invalidate()
        }

    var max: Int = 100
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }

    var progress: Int = 0
        set(value) {
            val target = value.coerceIn(0, max)
            field = target
            if (isIndeterminate || !isShown) {
                displayedProgress = target.toFloat()
                invalidate()
                return
            }
            progressAnimator?.cancel()
            progressAnimator = ValueAnimator.ofFloat(displayedProgress, target.toFloat()).apply {
                duration = PROGRESS_DURATION_MS
                interpolator = PathInterpolatorCompat.create(0.05f, 0.7f, 0.1f, 1f)
                addUpdateListener {
                    displayedProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    private var displayedProgress = 0f
    private var phase = 0f
    private var indeterminateAnimator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null

    init {
        val typedArray = context.obtainStyledAttributes(
            attrs,
            R.styleable.WavyProgressIndicator,
            defStyleAttr,
            0
        )
        trackThickness = typedArray.getDimension(
            R.styleable.WavyProgressIndicator_wavyTrackThickness,
            trackThickness
        )
        waveAmplitude = typedArray.getDimension(
            R.styleable.WavyProgressIndicator_wavyWaveAmplitude,
            waveAmplitude
        )
        waveLength = typedArray.getDimension(
            R.styleable.WavyProgressIndicator_wavyWaveLength,
            waveLength
        )
        isIndeterminate = typedArray.getBoolean(
            R.styleable.WavyProgressIndicator_wavyIndeterminate,
            true
        )
        trackColor = typedArray.getColor(
            R.styleable.WavyProgressIndicator_wavyTrackColor,
            trackColor
        )
        indicatorColor = typedArray.getColor(
            R.styleable.WavyProgressIndicator_wavyIndicatorColor,
            indicatorColor
        )
        typedArray.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (200 * density).toInt() + paddingLeft + paddingRight
        val desiredHeight = (trackThickness + waveAmplitude * 2f).toInt() +
            paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val startX = paddingLeft + trackThickness / 2f
        val endX = width - paddingRight - trackThickness / 2f
        if (endX <= startX) {
            return
        }
        val centerY = height / 2f

        // The track always ripples; in the indeterminate state the whole line moves with it.
        val wavePhase = if (isIndeterminate) phase * 2f * PI.toFloat() else 0f
        val track = wavePath(startX, endX, centerY, wavePhase)
        canvas.drawPath(track, trackPaint)

        if (isIndeterminate) {
            drawActivePacket(canvas, track)
        } else {
            val fraction = (displayedProgress / max).coerceIn(0f, 1f)
            if (fraction <= 0f) {
                return
            }
            val activeEnd = startX + (endX - startX) * fraction
            canvas.save()
            canvas.clipRect(0f, 0f, activeEnd + trackThickness, height.toFloat())
            canvas.drawPath(track, indicatorPaint)
            canvas.restore()
        }
    }

    /** Runs a wave packet along the track by walking the path geometry. */
    private fun drawActivePacket(canvas: Canvas, track: Path) {
        val measure = PathMeasure(track, false)
        val length = measure.length
        if (length <= 0f) {
            return
        }
        val packet = length * ACTIVE_FRACTION
        val head = (phase % 1f) * length
        val tail = head + packet
        val segment = Path()
        if (tail <= length) {
            measure.getSegment(head, tail, segment, true)
        } else {
            measure.getSegment(head, length, segment, true)
            measure.getSegment(0f, tail - length, segment, true)
        }
        canvas.drawPath(segment, indicatorPaint)
    }

    private fun wavePath(startX: Float, endX: Float, centerY: Float, wavePhase: Float): Path {
        val path = Path()
        val steps = ((endX - startX) / 3f).toInt().coerceAtLeast(2)
        val wavelength = waveLength.coerceAtLeast(1f)
        path.moveTo(startX, centerY + waveAmplitude * sin(wavePhase))
        for (step in 1..steps) {
            val x = startX + (endX - startX) * step / steps
            val angle = wavePhase + (x - startX) / wavelength * 2f * PI.toFloat()
            path.lineTo(x, centerY + waveAmplitude * sin(angle))
        }
        return path
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateAnimator()
    }

    override fun onDetachedFromWindow() {
        stopIndeterminate()
        progressAnimator?.cancel()
        progressAnimator = null
        super.onDetachedFromWindow()
    }

    private fun updateAnimator() {
        if (isIndeterminate && isShown) {
            startIndeterminate()
        } else {
            stopIndeterminate()
        }
    }

    private fun startIndeterminate() {
        if (indeterminateAnimator != null) {
            return
        }
        indeterminateAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = INDETERMINATE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopIndeterminate() {
        indeterminateAnimator?.cancel()
        indeterminateAnimator = null
        phase = 0f
    }

    private fun themeColor(attr: Int, fallback: Int): Int =
        runCatching { MaterialColors.getColor(this, attr) }
            .getOrDefault(ContextCompat.getColor(context, fallback))
}
