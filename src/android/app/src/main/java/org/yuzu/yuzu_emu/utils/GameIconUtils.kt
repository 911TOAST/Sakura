// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.LayerDrawable
import android.widget.ImageView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.LifecycleOwner
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.Options
import coil.size.isOriginal
import coil.size.pxOrElse
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.model.Game

class GameIconFetcher(
    private val game: Game,
    private val options: Options
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val decoded = decodeGameIcon(game.path, options)
        return DrawableResult(
            drawable = decoded.bitmap.toDrawable(options.context.resources),
            // Only claim sampling when the decoder actually shrank the image. Coil uses this flag
            // to validate memory-cache hits, so overstating it makes every bind a cache miss.
            isSampled = decoded.sampled,
            dataSource = DataSource.DISK
        )
    }

    private class Decoded(val bitmap: Bitmap, val sampled: Boolean)

    private fun decodeGameIcon(uri: String, options: Options): Decoded {
        val data = GameMetadata.getIcon(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)

        val sampleSize = calculateInSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            options
        )
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            // The bytes have no density information; never let the framework rescale them.
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)
            ?: throw IllegalStateException("Could not decode icon for $uri")
        return Decoded(bitmap, sampleSize > 1)
    }

    /**
     * Largest power-of-two reduction that still leaves the icon at or above the view size.
     * Switch covers are 256-512 px, so this mostly helps tablets rendering a dense icon grid.
     */
    private fun calculateInSampleSize(width: Int, height: Int, options: Options): Int {
        val size = options.size
        if (size.isOriginal || width <= 0 || height <= 0) {
            return 1
        }
        val targetWidth = size.width.pxOrElse { 0 }
        val targetHeight = size.height.pxOrElse { 0 }
        if (targetWidth <= 0 || targetHeight <= 0) {
            return 1
        }
        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetWidth &&
            height / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    class Factory : Fetcher.Factory<Game> {
        override fun create(data: Game, options: Options, imageLoader: ImageLoader): Fetcher =
            GameIconFetcher(data, options)
    }
}

class GameIconKeyer : Keyer<Game> {
    override fun key(data: Game, options: Options): String = data.path
}

object GameIconUtils {
    /**
     * Fixed ceiling for decoded covers. Coil's default is a quarter of the app heap, which on a
     * 512 MB heap means ~128 MB of icons. Icons are small and re-decodable, so a flat cap bounds
     * the cache without changing how the library looks.
     */
    private const val ICON_CACHE_BYTES = 32 * 1024 * 1024

    val imageLoader = ImageLoader.Builder(YuzuApplication.appContext)
        .components {
            add(GameIconKeyer())
            add(GameIconFetcher.Factory())
        }
        .memoryCache {
            MemoryCache.Builder(YuzuApplication.appContext)
                .maxSizeBytes(ICON_CACHE_BYTES)
                .build()
        }
        .build()

    /** Drops every decoded cover. Coil re-reads them from disk on the next bind. */
    fun trimMemory() {
        imageLoader.memoryCache?.clear()
    }

    fun loadGameIcon(game: Game, imageView: ImageView) {
        val request = ImageRequest.Builder(YuzuApplication.appContext)
            .data(game)
            .target(imageView)
            .error(R.drawable.default_icon)
            .build()
        imageLoader.enqueue(request)
    }

    suspend fun getGameIcon(lifecycleOwner: LifecycleOwner, game: Game): Bitmap {
        val request = ImageRequest.Builder(YuzuApplication.appContext)
            .data(game)
            .lifecycle(lifecycleOwner)
            .error(R.drawable.default_icon)
            .build()
        return imageLoader.execute(request)
            .drawable!!.toBitmap(config = Bitmap.Config.ARGB_8888)
    }

    suspend fun getShortcutIcon(lifecycleOwner: LifecycleOwner, game: Game): IconCompat {
        val layerDrawable = ResourcesCompat.getDrawable(
            YuzuApplication.appContext.resources,
            R.drawable.shortcut,
            null
        ) as LayerDrawable
        layerDrawable.setDrawableByLayerId(
            R.id.shortcut_foreground,
            getGameIcon(lifecycleOwner, game).toDrawable(YuzuApplication.appContext.resources)
        )
        val inset = YuzuApplication.appContext.resources
            .getDimensionPixelSize(R.dimen.icon_inset)
        layerDrawable.setLayerInset(1, inset, inset, inset, inset)
        return IconCompat.createWithAdaptiveBitmap(
            layerDrawable.toBitmap(config = Bitmap.Config.ARGB_8888)
        )
    }
}
