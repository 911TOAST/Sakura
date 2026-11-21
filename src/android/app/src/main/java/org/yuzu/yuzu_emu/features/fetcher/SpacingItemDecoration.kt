// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.fetcher

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Even gaps for a grid that does not use a ItemDecoration elsewhere.
 *
 * The previous version only set a bottom offset, so on a multi-column grid the cards in a row
 * touched each other and the last row kept a trailing gap. Both axes are handled here, and the
 * caller drops the layout's own margin so the spacing has exactly one owner.
 */
class SpacingItemDecoration(
    private val spacing: Int,
    /** Also inset the leading/trailing edges. Off for full-bleed lists that own their padding. */
    private val includeEdges: Boolean = true
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return

        val columns = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        if (columns <= 1) {
            // Single column: no side gaps, and a row of its own still needs air above it.
            outRect.bottom = spacing
            if (position == 0) outRect.top = spacing
            return
        }

        val column = position % columns
        val row = position / columns

        outRect.left = spacing / 2
        outRect.right = spacing / 2
        outRect.bottom = spacing
        outRect.top = if (row == 0) spacing / 2 else 0

        if (includeEdges && column == 0) {
            outRect.left = spacing
        }
        if (includeEdges && column == columns - 1) {
            outRect.right = spacing
        }
    }
}