// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils.background

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Key-free online wallpaper sources. A custom catalog URL overrides all of them.
 * Every provider must stay safe-for-work and serve JPEG, PNG, or WebP over HTTPS.
 */
object WallpaperProviders {
    const val WALLHAVEN = "wallhaven"
    const val SAFEBOORU = "safebooru"
    const val BING = "bing"

    val ids = listOf(WALLHAVEN, SAFEBOORU, BING)

    fun normalize(id: String): String = if (id in ids) id else WALLHAVEN
}

private val providerJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** Only formats the shared downloader and decoder accept. */
private val SAFE_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

private const val SAFEBOORU_PAGE_SIZE = 24

fun safebooruSearchUrl(spec: WallpaperSearchSpec, page: Int): HttpUrl {
    val tags = spec.query.trim().take(200).split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
    if (spec.sort == 2) tags += "sort:score"
    return "https://safebooru.org/index.php".toHttpUrl().newBuilder()
        .addQueryParameter("page", "dapi")
        .addQueryParameter("s", "post")
        .addQueryParameter("q", "index")
        .addQueryParameter("json", "1")
        .addQueryParameter("limit", SAFEBOORU_PAGE_SIZE.toString())
        .addQueryParameter("pid", (page.coerceAtLeast(1) - 1).toString())
        .addQueryParameter("tags", tags.joinToString(" "))
        .build()
}

@Serializable
private data class SafebooruPost(
    val id: Long = 0,
    val directory: String = "",
    val image: String = "",
    val hash: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val owner: String = "",
    val rating: String = ""
)

/**
 * Safebooru is a safe-for-work booru, so it fits game and anime art searches.
 * Path components are third-party data: only plain file names are accepted so
 * they cannot escape the image directories when the URLs are assembled.
 */
fun parseSafebooruPage(text: String, page: Int): WallpaperPage {
    val posts = providerJson.decodeFromString<List<SafebooruPost>>(text)
    val items = posts.filter {
        it.id > 0 && it.width > 0 && it.height > 0 &&
            it.directory.matches(Regex("[A-Za-z0-9_-]+")) &&
            it.image.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9]+")) &&
            it.image.substringAfterLast('.').lowercase() in SAFE_IMAGE_EXTENSIONS &&
            it.hash.matches(Regex("[A-Za-z0-9]+")) &&
            (it.rating.isEmpty() || it.rating == "s" || it.rating == "safe")
    }.map {
        OnlineBackground(
            id = "safebooru-${it.id}", title = "Safebooru · ${it.id}", author = it.owner.take(200),
            thumb = "https://safebooru.org/thumbnails/${it.directory}/thumbnail_${it.hash}.jpg",
            full = "https://safebooru.org/images/${it.directory}/${it.image}",
            source = "https://safebooru.org/index.php?page=post&s=view&id=${it.id}",
            width = it.width, height = it.height
        )
    }.distinctBy { it.id }
    val current = page.coerceAtLeast(1)
    // The dapi reports no total; a full raw page implies there is a next one.
    return WallpaperPage(items, current, if (posts.size >= SAFEBOORU_PAGE_SIZE) current + 1 else current)
}

private const val BING_PAGE_SIZE = 8

fun bingWallpaperUrl(page: Int): HttpUrl =
    "https://www.bing.com/HPImageArchive.aspx".toHttpUrl().newBuilder()
        .addQueryParameter("format", "js")
        .addQueryParameter("idx", ((page.coerceIn(1, 2) - 1) * BING_PAGE_SIZE).toString())
        .addQueryParameter("n", BING_PAGE_SIZE.toString())
        .addQueryParameter("mkt", "en-US")
        .build()

@Serializable
private data class BingResponse(val images: List<BingImage> = emptyList())

@Serializable
private data class BingImage(val urlbase: String = "", val title: String = "", val copyright: String = "")

/**
 * Bing's daily photography feed. It has no search, so the query is matched
 * against titles locally by [filterBingResults]. Only the curated archive path
 * is accepted; the fixed 1080p rendition keeps downloads small and predictable.
 */
fun parseBingPage(text: String, page: Int): WallpaperPage {
    val images = providerJson.decodeFromString<BingResponse>(text).images
    val items = images.filter { it.urlbase.matches(Regex("/th\\?id=OHR\\.[A-Za-z0-9_-]+")) }.map {
        val base = "https://www.bing.com${it.urlbase}"
        OnlineBackground(
            id = "bing-${it.urlbase.substringAfter("id=")}",
            title = it.title.ifBlank { it.copyright }.take(200), author = it.copyright.take(200),
            thumb = "${base}_400x240.jpg", full = "${base}_1920x1080.jpg",
            source = "https://www.bing.com", width = 1920, height = 1080
        )
    }.distinctBy { it.id }
    val current = page.coerceIn(1, 2)
    return WallpaperPage(items, current, if (current == 1 && images.size >= BING_PAGE_SIZE) 2 else current)
}

fun filterBingResults(items: List<OnlineBackground>, spec: WallpaperSearchSpec): List<OnlineBackground> {
    val query = spec.query.trim()
    if (query.isEmpty()) return items
    return items.filter { it.title.contains(query, true) || it.author.contains(query, true) }
}
