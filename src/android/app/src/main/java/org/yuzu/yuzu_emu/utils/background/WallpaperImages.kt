// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Full images are kept in memory until Apply; only encrypted bytes can reach private storage. */
data class LoadedWallpaper(val bitmap: Bitmap?, val onlineBytes: ByteArray? = null)

object WallpaperImages {
    const val MAX_EDGE = 2560
    private const val MAX_SOURCE_EDGE = 32768
    private const val MAX_SOURCE_PIXELS = 200_000_000L

    suspend fun load(context: Context, selection: WallpaperSelection): LoadedWallpaper =
        withContext(Dispatchers.IO) {
            val online = selection.online
            if (online != null) {
                val bytes = WallpaperHttp.get(online.full, image = true)
                coroutineContext.ensureActive()
                return@withContext LoadedWallpaper(decode(bytes), bytes)
            }
            if (selection.uri.isEmpty()) return@withContext LoadedWallpaper(null)
            if (SecureBackgroundStore.isEncryptedSelection(selection.uri)) {
                return@withContext LoadedWallpaper(
                    SecureBackgroundStore.loadBitmap(selection.uri) ?: throw IOException("Saved wallpaper is unavailable")
                )
            }
            val uri = Uri.parse(selection.uri)
            require(uri.scheme == "content" || uri.scheme == "file") { "Unsupported local image URI" }
            val bytes = context.contentResolver.openInputStream(uri)?.use {
                WallpaperHttp.readBounded(it, OnlineBackgroundCatalog.MAX_IMAGE_BYTES)
            } ?: throw IOException("Image permission is no longer available")
            coroutineContext.ensureActive()
            LoadedWallpaper(decode(bytes))
        }

    fun decode(bytes: ByteArray, maxEdge: Int = MAX_EDGE): Bitmap {
        require(maxEdge in 1..MAX_EDGE)
        require(bytes.isNotEmpty() && bytes.size <= OnlineBackgroundCatalog.MAX_IMAGE_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType == "image/jpeg" || bounds.outMimeType == "image/png" ||
            bounds.outMimeType == "image/webp") { "Choose a JPEG, PNG, or WebP image" }
        require(bounds.outWidth in 1..MAX_SOURCE_EDGE && bounds.outHeight in 1..MAX_SOURCE_EDGE &&
            bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS) { "Invalid image dimensions" }
        var sample = 1
        while ((maxOf(bounds.outWidth, bounds.outHeight) + sample - 1) / sample > maxEdge) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IOException("Image cannot be decoded")
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}
