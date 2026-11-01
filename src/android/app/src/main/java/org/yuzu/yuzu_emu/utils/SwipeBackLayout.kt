// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max

/**
 * Left-edge swipe-to-dismiss container used by the settings panel.
 *
 * The user drags from the left edge of the screen; the whole panel follows the
 * finger and either settles back or slides away and fires [onSwipeBack]. It only
 * takes over when the gesture is clearly horizontal, so vertical scrolling keeps
 * working.
 */
class SwipeBackLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Invoked once the panel has been swiped far enough to be dismissed. */
    var onSwipeBack: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeSize = (24 * resources.displayMetrics.density).toInt()
    private val settleInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (onSwipeBack == null) {
            return super.onInterceptTouchEvent(ev)
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                tracking = ev.x <= edgeSize
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (tracking && !dragging) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (dx > touchSlop && dx > abs(dy) * 1.5f) {
                        dragging = true
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }

        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val wasDragging = dragging

        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    translationX = max(0f, ev.x - downX)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    if (translationX > width * 0.3f) {
                        // Hand the panel back to the navigation exit animation, which
                        // slides it away to the same side the user swiped towards.
                        translationX = 0f
                        onSwipeBack?.invoke()
                    } else {
                        animate()
                            .translationX(0f)
                            .setDuration(180L)
                            .setInterpolator(settleInterpolator)
                            .start()
                    }
                }
                dragging = false
                tracking = false
            }
        }

        return wasDragging || super.onTouchEvent(ev)
    }
}
