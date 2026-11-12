// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.settings.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import coil.request.ImageRequest
import kotlin.math.max
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.WallpaperPreviewBinding
import org.yuzu.yuzu_emu.utils.GameHelper
import org.yuzu.yuzu_emu.utils.GameIconUtils

fun WallpaperFailure.message(context: Context): String = context.getString(
    when (this) {
        WallpaperFailure.NETWORK -> R.string.wallpaper_network_error
        WallpaperFailure.RATE_LIMIT -> R.string.wallpaper_rate_error
        WallpaperFailure.IMAGE -> R.string.wallpaper_image_error
        WallpaperFailure.SAVE -> R.string.wallpaper_save_error
    }
)

fun wallpaperColumns(context: Context): Int {
    val width = context.resources.configuration.screenWidthDp
    return if (width < 340) 1 else max(2, width / 180)
}

/**
 * Fills the miniature library with real cover art from the cached game list. Empty slots keep
 * the tonal tile and gamepad glyph, so an empty library still reads as a deliberate design
 * instead of a loading skeleton.
 */
fun WallpaperPreviewBinding.bindGameCovers(fragment: Fragment) {
    val slots = listOf(
        previewCover1 to previewCoverFallback1,
        previewCover2 to previewCoverFallback2,
        previewCover3 to previewCoverFallback3
    )
    val games = GameHelper.cachedGameList.take(slots.size)
    val context = fragment.requireContext()
    slots.forEachIndexed { index, slot ->
        val cover = slot.first
        val fallback = slot.second
        cover.setImageDrawable(null)
        fallback.isVisible = true
        val game = games.getOrNull(index) ?: return@forEachIndexed
        val request = ImageRequest.Builder(context)
            .data(game)
            .target(cover)
            .listener(
                onSuccess = { _, _ -> fallback.isVisible = false },
                onError = { _, _ ->
                    cover.setImageDrawable(null)
                    fallback.isVisible = true
                }
            )
            .build()
        GameIconUtils.imageLoader.enqueue(request)
    }
}

/** Keeps every wallpaper screen clear of the status and navigation bars. */
fun View.applyWallpaperBarInsets(toolbar: View, scroll: View, bottomBar: View? = null) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        toolbar.updatePadding(top = bars.top)
        val inset = resources.getDimensionPixelSize(R.dimen.spacing_large)
        (bottomBar ?: scroll).updatePadding(bottom = bars.bottom + if (bottomBar != null) inset else 0)
        scroll.updatePadding(left = bars.left, right = bars.right)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

fun View.applyKeyboardInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
        insets
    }
}

fun RecyclerView.respectReducedMotion() {
    val scale = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    )
    if (scale == 0f) itemAnimator = null
}

fun openWallpaperSource(fragment: Fragment, url: String) {
    runCatching { fragment.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure {
            Toast.makeText(fragment.requireContext(), R.string.wallpaper_source_unavailable, Toast.LENGTH_SHORT).show()
        }
}
