// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils.background

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A wallpaper offered by the online background browser.
 *
 * All URLs are https only. [full] is downloaded once and then stored encrypted by
 * [SecureBackgroundStore]; it is never re-fetched to draw the library.
 */
@Serializable
data class OnlineBackground(
    val id: String = "",
    val title: String = "",
    val author: String = "",
    val thumb: String = "",
    val full: String = "",
    val source: String = "",
    val width: Int = 0,
    val height: Int = 0
)

@Serializable
data class OnlineBackgroundCategory(
    val name: String,
    val items: List<OnlineBackground> = emptyList()
)

/**
 * JSON schema for a self-hosted catalog:
 *
 * ```json
 * {
 *   "categories": [
 *     {
 *       "name": "Recommended",
 *       "items": [
 *         {
 *           "id": "aurora-01",
 *           "title": "Aurora",
 *           "author": "Photographer",
 *           "thumb": "https://example.com/aurora-thumb.jpg",
 *           "full": "https://example.com/aurora-1920.jpg",
 *           "source": "https://example.com/photo-page"
 *         }
 *       ]
 *     }
 *   ]
 * }
 * ```
 *
 * Unknown fields are ignored, so catalogs can grow without breaking older builds.
 */
@Serializable
data class BackgroundCatalog(
    val categories: List<OnlineBackgroundCategory> = emptyList()
) {
    fun sanitized(): BackgroundCatalog = BackgroundCatalog(
        categories = categories.mapNotNull { category ->
            val items = category.items.filter { it.isSafe() }.take(1000).map {
                it.copy(id = it.id.ifBlank { it.full }, title = it.title.take(200), author = it.author.take(200))
            }
            if (items.isEmpty()) null else OnlineBackgroundCategory(category.name, items)
        }
    )

    private fun OnlineBackground.isSafe(): Boolean =
        thumb.toHttpUrlOrNull()?.isHttps == true && full.toHttpUrlOrNull()?.isHttps == true && full != thumb
}

object OnlineBackgroundCatalog {
    private const val PREFS = "secure_library_background"
    private const val PREF_CATALOG_URL = "catalog_url"

    /** Refuse anything that could be huge or a decompression bomb before decoding it. */
    const val MAX_IMAGE_BYTES = 24 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun catalogUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_CATALOG_URL, "")
            .orEmpty()

    fun setCatalogUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_CATALOG_URL, url.trim())
            .apply()
    }

    /**
     * Parses a self-hosted catalog. Catalog content is third-party data, so unusable entries are
     * dropped rather than trusted.
     */
    fun parseCatalog(text: String): BackgroundCatalog = json.decodeFromString<BackgroundCatalog>(text)
        .sanitized().also { require(it.categories.isNotEmpty()) { "catalog has no usable entries" } }
}
