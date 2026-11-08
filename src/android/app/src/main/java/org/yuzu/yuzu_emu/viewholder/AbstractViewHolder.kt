// SPDX-FileCopyrightText: Copyright 2024 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.viewholder

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import org.yuzu.yuzu_emu.adapters.AbstractDiffAdapter
import org.yuzu.yuzu_emu.adapters.AbstractListAdapter
import org.yuzu.yuzu_emu.utils.SuyuMotion
import java.util.Collections
import java.util.WeakHashMap

/**
 * Views that already played their entrance. Recycled holders keep their instance, so the guard is
 * per view and scrolling a list back does not replay the animation.
 */
private val revealedViews: MutableSet<View> =
    Collections.newSetFromMap(WeakHashMap<View, Boolean>())

/**
 * Staggered M3 entrance: the row rises and fades in the first time it is shown. The delay is
 * capped so long lists settle instead of turning into a waterfall.
 */
fun revealItemOnce(itemView: View, position: Int) {
    if (!revealedViews.add(itemView)) {
        return
    }

    val context = itemView.context
    val rise = 12f * itemView.resources.displayMetrics.density
    itemView.animate().cancel()
    itemView.alpha = 0f
    itemView.translationY = rise
    itemView.animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay((position.coerceAtMost(8) * 28).toLong())
        .setDuration(SuyuMotion.durationMedium2(context))
        .setInterpolator(SuyuMotion.emphasizedDecelerate(context))
        .start()
}

/**
 * [RecyclerView.ViewHolder] meant to work together with a [AbstractDiffAdapter] or a
 * [AbstractListAdapter] so we can run [bind] on each list item without needing a manual hookup.
 */
abstract class AbstractViewHolder<Model>(binding: ViewBinding) :
    RecyclerView.ViewHolder(binding.root) {
    abstract fun bind(model: Model)

    /** Plays the shared entrance animation the first time this row is bound. */
    fun revealOnce(position: Int) = revealItemOnce(itemView, position)

    /** Shared press-in/press-out scale for card style rows. Returns false to keep click handling. */
    fun applyPressFeedback() {
        SuyuMotion.installPressFeedback(itemView)
    }
}
