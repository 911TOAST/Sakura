// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils.background

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.yuzu.yuzu_emu.utils.Log

/**
 * Wallpapers contributed by other apps through [activities.WallpaperContributionActivity].
 *
 * Any app can share an image into Sakura (share sheet or the documented intent action); it lands
 * here encrypted at rest — the same Android-Keystore AES-GCM scheme as [SecureBackgroundStore] —
 * and shows up in the wallpaper hub under "From other apps", where the user reviews and applies
 * it like any other wallpaper. Contributing never silently replaces the current background.
 *
 * The index is a small JSON array in private preferences; the pixels only ever exist encrypted
 * on disk and decrypted in memory for thumbnails and the editor preview.
 */
object WallpaperContributions {
    const val URI_PREFIX = "suyu-secure://wallpaper-contribution/"

    /** Storage is bounded: enough for a personal rotation, not a way to fill the disk. */
    const val MAX_COUNT = 32

    private const val PREFS = "wallpaper_contributions"
    private const val PREF_INDEX = "index"

    @Serializable
    data class Contribution(
        val id: String,
        val title: String,
        val author: String,
        val source: String,
        val receivedAtMillis: Long,
        val byteCount: Long
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val thumbnailCache = object : LruCache<String, Bitmap>(16) {}

    fun isContributionUri(uri: String): Boolean =
        uri.startsWith(URI_PREFIX) && uri.substringAfter(URI_PREFIX).isNotBlank()

    fun idFromUri(uri: String): String = uri.substringAfter(URI_PREFIX)

    fun uriFor(id: String): String = "$URI_PREFIX$id"

    fun fileFor(context: Context, uri: String): File {
        require(isContributionUri(uri))
        return File(directory(context), "${idFromUri(uri)}.bin")
    }

    private fun directory(context: Context): File =
        File(context.filesDir, "secure_background/contributions")

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Newest first; entries whose encrypted blob has disappeared are dropped on read. */
    fun list(context: Context): List<Contribution> {
        val raw = preferences(context).getString(PREF_INDEX, null) ?: return emptyList()
        val parsed = runCatching {
            json.decodeFromString<List<Contribution>>(raw)
        }.getOrNull() ?: return emptyList()
        val alive = parsed.filter { fileFor(context, uriFor(it.id)).exists() }
        if (alive.size != parsed.size) {
            preferences(context).edit()
                .putString(PREF_INDEX, json.encodeToString(alive)).apply()
        }
        return alive
    }

    fun readMetadata(uri: String): BackgroundMetadata? {
        val context = org.yuzu.yuzu_emu.YuzuApplication.appContext
        val found = list(context).firstOrNull { it.id == idFromUri(uri) } ?: return null
        return BackgroundMetadata(
            id = found.id,
            title = found.title,
            author = found.author,
            source = found.source
        )
    }

    /**
     * Validates, encrypts and indexes one contribution. Throws on any rejection — the caller
     * surfaces a single "could not add" message regardless of the reason, because from the
     * contributing app's point of view they all look the same.
     */
    @Synchronized
    fun add(context: Context, bytes: ByteArray, metadata: BackgroundMetadata): Contribution {
        require(bytes.isNotEmpty() && bytes.size <= OnlineBackgroundCatalog.MAX_IMAGE_BYTES) {
            "Wallpaper too large"
        }
        // Same acceptance rules as every other wallpaper source: JPEG/PNG/WebP, sane dimensions.
        WallpaperImages.decode(bytes).recycle()

        val contribution = Contribution(
            id = UUID.randomUUID().toString(),
            title = metadata.title.take(100),
            author = metadata.author.take(100),
            source = metadata.source,
            receivedAtMillis = System.currentTimeMillis(),
            byteCount = bytes.size.toLong()
        )
        val file = fileFor(context, uriFor(contribution.id))
        SecureBackgroundStore.writeEncryptedFile(file, bytes)

        val index = (list(context) + contribution)
            .sortedByDescending { it.receivedAtMillis }
        val evicted = index.drop(MAX_COUNT)
        evicted.forEach { fileFor(context, uriFor(it.id)).delete() }
        val kept = index.take(MAX_COUNT)
        preferences(context).edit().putString(PREF_INDEX, json.encodeToString(kept)).apply()
        thumbnailCache.remove(contribution.id)
        return contribution
    }

    fun remove(context: Context, id: String) {
        fileFor(context, uriFor(id)).delete()
        val remaining = list(context).filterNot { it.id == id }
        preferences(context).edit().putString(PREF_INDEX, json.encodeToString(remaining)).apply()
        thumbnailCache.remove(id)
    }

    /** True when the selection still points at a contribution that no longer exists. */
    fun isMissing(context: Context, uri: String): Boolean =
        isContributionUri(uri) && !fileFor(context, uri).exists()

    /**
     * Decrypted thumbnail for the hub grid. Returns null on any failure; the tile then shows
     * its "thumbnail unavailable" state instead of crashing the scroll.
     */
    fun thumbnail(context: Context, id: String): Bitmap? {
        thumbnailCache.get(id)?.let { cached ->
            if (!cached.isRecycled) return cached
            thumbnailCache.remove(id)
        }
        val bitmap = runCatching {
            SecureBackgroundStore.loadBitmap(uriFor(id))?.let { full ->
                // Fit within 512 on the long edge, preserving aspect ratio.
                val scale = 512f / maxOf(full.width, full.height)
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        full,
                        (full.width * scale).toInt().coerceAtLeast(1),
                        (full.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    full
                }
                if (scaled !== full) full.recycle()
                scaled
            }
        }.onFailure { Log.warning("[Wallpaper] Contribution thumbnail failed: ${it.message}") }
            .getOrNull() ?: return null
        thumbnailCache.put(id, bitmap)
        return bitmap
    }

    /** Hub-grid view model of one contribution; thumbnails never go through the network. */
    fun toOnlineBackground(context: Context, contribution: Contribution): OnlineBackground =
        OnlineBackground(
            id = "contribution-${contribution.id}",
            title = contribution.title.ifBlank {
                context.getString(org.yuzu.yuzu_emu.R.string.wallpaper_contributions_fallback_title)
            },
            thumb = "contribution:${contribution.id}",
            author = listOfNotNull(
                contribution.author.takeIf { it.isNotBlank() },
                contribution.byteCount.takeIf { it > 0 }
                    ?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
            ).joinToString(" · "),
            source = contribution.source
        )

    fun listOnline(context: Context): List<OnlineBackground> =
        list(context).map { toOnlineBackground(context, it) }
}
