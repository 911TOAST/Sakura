// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.graphics.Color
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.features.settings.model.Settings
import org.yuzu.yuzu_emu.ui.CarouselRecyclerView

/**
 * App-wide layout presets, picked in Settings > Interface look > App layout.
 *
 * A preset bundles the library's structure (grid, list, carousel, columns) with the shape language
 * used by cards, so one choice changes the whole front end instead of only one screen. Values are
 * stored in the default SharedPreferences because they are pure UI state: no core config key is
 * involved and nothing needs to reach the emulator thread.
 */
enum class UiLayout(
    val id: Int,
    val labelRes: Int,
    val libraryStyle: LibraryStyle,
    /** 0 keeps the density-specific value from integers.xml. */
    val libraryColumns: Int,
    val cornerRadiusDp: Float,
    /** Transparent card containers with a primary outline instead of a filled surface. */
    val outlined: Boolean
) {
    SAKURA(0, R.string.ui_layout_sakura, LibraryStyle.GRID, 0, 20f, false),
    COMPACT(1, R.string.ui_layout_compact, LibraryStyle.COMPACT_GRID, 3, 12f, false),
    CINEMA(2, R.string.ui_layout_cinema, LibraryStyle.CINEMA, 1, 28f, false),
    LIST(3, R.string.ui_layout_list, LibraryStyle.LIST, 0, 16f, false),
    CAROUSEL(4, R.string.ui_layout_carousel, LibraryStyle.CAROUSEL, 0, 24f, false),
    OUTLINE(5, R.string.ui_layout_outline, LibraryStyle.GRID, 0, 16f, true);

    enum class LibraryStyle {
        GRID,
        COMPACT_GRID,
        LIST,
        CAROUSEL,
        /** One full-width cover per row. */
        CINEMA
    }

    fun cornerRadiusPx(context: Context): Float =
        cornerRadiusDp * context.resources.displayMetrics.density

    /** Shape overlay applied on top of the theme so dialogs, sheets and menus follow the preset. */
    val themeOverlay: Int
        get() = when (this) {
            COMPACT -> R.style.ThemeOverlay_Sakura_Layout_Compact
            CINEMA -> R.style.ThemeOverlay_Sakura_Layout_Cinema
            LIST -> R.style.ThemeOverlay_Sakura_Layout_List
            CAROUSEL -> R.style.ThemeOverlay_Sakura_Layout_Carousel
            OUTLINE -> R.style.ThemeOverlay_Sakura_Layout_Outline
            SAKURA -> 0
        }

    fun outlineWidthPx(context: Context): Int =
        (context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    fun cardFill(context: Context, selected: Boolean = false): Int = when {
        selected -> MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSecondaryContainer,
            Color.TRANSPARENT
        )

        outlined -> Color.TRANSPARENT
        else -> MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurfaceContainer,
            Color.TRANSPARENT
        )
    }

    fun cardStroke(context: Context, selected: Boolean = false): Int = when {
        selected || outlined -> MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorPrimary,
            Color.TRANSPARENT
        )

        else -> MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOutlineVariant,
            Color.TRANSPARENT
        )
    }

    /**
     * Applies the preset to a library card. Non-outline presets keep the background the layout
     * declares, so the default look is untouched.
     */
    fun applyToCard(card: MaterialCardView) {
        val context = card.context
        card.radius = cornerRadiusPx(context)
        if (outlined) {
            card.setCardBackgroundColor(Color.TRANSPARENT)
            card.strokeWidth = outlineWidthPx(context)
            card.strokeColor = MaterialColors.getColor(
                card,
                com.google.android.material.R.attr.colorPrimary,
                Color.TRANSPARENT
            )
        } else {
            card.strokeWidth = 0
        }
    }

    companion object {
        @Volatile
        private var cached: UiLayout? = null

        /** Positive values request more columns (smaller tiles); negative values request fewer. */
        fun gameTileSizeStep(context: Context): Int = PreferenceManager
            .getDefaultSharedPreferences(context)
            .getInt(Settings.PREF_GAME_TILE_SIZE, 0)
            .coerceIn(-1, 1)

        fun from(id: Int): UiLayout = entries.firstOrNull { it.id == id } ?: SAKURA

        fun current(context: Context): UiLayout {
            cached?.let { return it }
            val stored = PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(Settings.PREF_UI_LAYOUT, SAKURA.id)
            return from(stored).also { cached = it }
        }

        fun set(context: Context, layout: UiLayout) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putInt(Settings.PREF_UI_LAYOUT, layout.id)
            }
            cached = layout
        }

        /**
         * The library remembers a per-orientation view mode from the toolbar toggle. Picking a new
         * preset clears those overrides so the preset actually shows up.
         */
        fun clearLibraryViewOverrides(context: Context) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                remove(CarouselRecyclerView.CAROUSEL_VIEW_TYPE_LANDSCAPE)
                remove(CarouselRecyclerView.CAROUSEL_VIEW_TYPE_PORTRAIT)
            }
        }
    }
}
