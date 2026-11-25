// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils.background

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
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
    const val PICSUM = "picsum"
    const val WIKIMEDIA = "wikimedia"
    const val NEKOS = "nekos"
    const val WAIFU_PICS = "waifupics"

    val ids = listOf(WALLHAVEN, SAFEBOORU, BING, PICSUM, WIKIMEDIA, NEKOS, WAIFU_PICS)

    fun normalize(id: String): String = if (id in ids) id else WALLHAVEN
}

private val providerJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** Only formats the shared downloader and decoder accept. */
private val SAFE_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

private const val SAFEBOORU_PAGE_SIZE = 24

fun safebooruSearchUrl(spec: WallpaperSearchSpec, page: Int): HttpUrl {
    val tags = spec.query.trim().take(200).split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
    // Safebooru's dapi is Gelbooru 0.1-era and has no "sort:" meta tags: passing one makes the
    // server treat it as a literal tag and return zero results, which silently broke the
    // "popular" feed and "surprise me" whenever Safebooru was the active provider.
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
    // The dapi usually returns a JSON array, but deployments differ: a single-hit page can come
    // back as one bare post object, and a server-side redirect to an HTML error page is not a
    // serialization error either. Both must read as "no results", not crash the search screen.
    val posts: List<SafebooruPost> = runCatching {
        providerJson.decodeFromString<List<SafebooruPost>>(text)
    }.getOrElse {
        runCatching { listOf(providerJson.decodeFromString<SafebooruPost>(text)) }
            .getOrDefault(emptyList())
    }
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

private const val PICSUM_PAGE_SIZE = 24

/** Picsum has no search: each page is a new random slice of its curated photo pool. */
fun picsumSearchUrl(page: Int): HttpUrl =
    "https://picsum.photos/v2/list".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.coerceAtLeast(1).toString())
        .addQueryParameter("limit", PICSUM_PAGE_SIZE.toString())
        .build()

@Serializable
private data class PicsumPhoto(
    val id: String = "",
    val author: String = "",
    val width: Int = 0,
    val height: Int = 0
)

/**
 * Everything Picsum serves is hand-curated photography, so the filter only has to keep the
 * structural contract: an ASCII id that the fixed /id/ download path can be built from.
 */
fun parsePicsumPage(text: String, page: Int): WallpaperPage {
    val photos = runCatching {
        providerJson.decodeFromString<List<PicsumPhoto>>(text)
    }.getOrDefault(emptyList())
    val items = photos.filter {
        it.id.matches(Regex("[A-Za-z0-9]+")) && it.width > 0 && it.height > 0
    }.map {
        OnlineBackground(
            id = "picsum-${it.id}",
            title = "Picsum · ${it.id}",
            author = it.author.take(200),
            thumb = "https://picsum.photos/id/${it.id}/400/240",
            // Request the original dimensions so the editor sees the real aspect ratio.
            full = "https://picsum.photos/id/${it.id}/${it.width}/${it.height}",
            source = "https://picsum.photos/id/${it.id}",
            width = it.width, height = it.height
        )
    }.distinctBy { it.id }
    val current = page.coerceAtLeast(1)
    return WallpaperPage(items, current, if (photos.size >= PICSUM_PAGE_SIZE) current + 1 else current)
}

private const val WIKIMEDIA_PAGE_SIZE = 24

fun wikimediaSearchUrl(spec: WallpaperSearchSpec, page: Int): HttpUrl {
    // fileType:bitmap keeps the search to static images instead of PDFs, audio and video.
    val query = "filetype:bitmap " + spec.query.trim().take(200)
    return "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
        .addQueryParameter("action", "query")
        .addQueryParameter("format", "json")
        .addQueryParameter("formatversion", "2")
        .addQueryParameter("generator", "search")
        .addQueryParameter("gsrnamespace", "6")
        .addQueryParameter("gsrsearch", query)
        .addQueryParameter("gsrlimit", WIKIMEDIA_PAGE_SIZE.toString())
        .addQueryParameter("gsroffset", ((page.coerceAtLeast(1) - 1) * WIKIMEDIA_PAGE_SIZE).toString())
        .addQueryParameter("prop", "imageinfo")
        .addQueryParameter("iiprop", "url|size")
        .addQueryParameter("iiurlwidth", "400")
        .build()
}

@Serializable
private data class WikimediaResponse(val query: WikimediaQuery = WikimediaQuery())
@Serializable
private data class WikimediaQuery(val pages: List<WikimediaPage> = emptyList())
@Serializable
private data class WikimediaPage(
    val pageid: Long = 0,
    val title: String = "",
    val imageinfo: List<WikimediaImageInfo> = emptyList()
)
@Serializable
private data class WikimediaImageInfo(
    val url: String = "",
    val descriptionurl: String = "",
    val thumburl: String = "",
    val width: Int = 0,
    val height: Int = 0
)

/**
 * Wikimedia Commons serves free-licensed media. Hosts are upload*.wikimedia.org; the extension
 * check keeps SVG (script-bearing vector format, rejected by the image downloader anyway) and
 * tiff out of the grid so only renderable bitmap URLs are shown.
 */
fun parseWikimediaPage(text: String, page: Int): WallpaperPage {
    val response = runCatching { providerJson.decodeFromString<WikimediaResponse>(text) }
        .getOrDefault(WikimediaResponse())
    val items = response.query.pages.mapNotNull { entry ->
        val info = entry.imageinfo.firstOrNull() ?: return@mapNotNull null
        val extension = info.url.substringAfterLast('.').lowercase().substringBefore('?')
        if (entry.pageid <= 0 ||
            entry.title.isBlank() ||
            info.width <= 0 || info.height <= 0 ||
            extension !in SAFE_IMAGE_EXTENSIONS ||
            !info.url.startsWith("https://upload.wikimedia.org/") ||
            (info.thumburl.isNotEmpty() && !info.thumburl.startsWith("https://upload.wikimedia.org/"))
        ) {
            return@mapNotNull null
        }
        OnlineBackground(
            id = "wikimedia-${entry.pageid}",
            title = entry.title.removePrefix("File:").substringBeforeLast('.').take(200),
            author = "Wikimedia Commons",
            thumb = info.thumburl.ifEmpty { info.url },
            full = info.url,
            source = info.descriptionurl.ifEmpty { "https://commons.wikimedia.org" },
            width = info.width, height = info.height
        )
    }.distinctBy { it.id }
    val current = page.coerceAtLeast(1)
    // The search API reports no total; a full page implies there is a next one.
    return WallpaperPage(items, current, if (items.size >= WIKIMEDIA_PAGE_SIZE) current + 1 else current)
}

private const val NEKOS_PAGE_SIZE = 20

/** nekos.best has no search and no pagination: each call returns a fresh random set. */
fun nekosSearchUrl(page: Int): HttpUrl =
    "https://nekos.best/api/v2/neko".toHttpUrl().newBuilder()
        .addQueryParameter("amount", NEKOS_PAGE_SIZE.toString())
        .build()

@Serializable
private data class NekosResult(
    val id: String = "",
    val url: String = "",
    @SerialName("artist_name") val artistName: String = "",
    val width: Int = 0,
    val height: Int = 0
)

@Serializable
private data class NekosResponse(val results: List<NekosResult> = emptyList())

/**
 * nekos.best serves static SFW images from its own CDN, plus artist and source links. Only
 * same-host bitmap URLs are accepted so third-party data can never redirect a download.
 */
fun parseNekosPage(text: String, page: Int): WallpaperPage {
    val response = runCatching { providerJson.decodeFromString<NekosResponse>(text) }
        .getOrDefault(NekosResponse())
    val items = response.results.mapNotNull { result ->
        val id = result.id.ifEmpty { result.url.substringAfterLast('/').substringBeforeLast('.') }
        val extension = result.url.substringAfterLast('.').lowercase().substringBefore('?')
        if (result.url.isBlank() || id.isBlank() ||
            extension !in SAFE_IMAGE_EXTENSIONS ||
            !result.url.startsWith("https://nekos.best/")
        ) {
            return@mapNotNull null
        }
        OnlineBackground(
            id = "nekos-$id",
            title = "Nekos · $id",
            author = result.artistName.take(200).ifEmpty { "nekos.best" },
            thumb = result.url,
            full = result.url,
            source = "https://nekos.best",
            width = result.width, height = result.height
        )
    }.distinctBy { it.id }
    val current = page.coerceAtLeast(1)
    // A full random set means more exist, which is true for every request.
    return WallpaperPage(items, current, current + 1)
}

/** waifu.pics returns exactly one random image per request; there is no search or paging. */
fun waifuPicsSearchUrl(): HttpUrl = "https://api.waifu.pics/sfw/waifu".toHttpUrl()

@Serializable
private data class WaifuPicsResult(val url: String = "")

/**
 * The repository fetches a batch of one-post responses and hands us the bodies joined into a
 * JSON array. Each entry is small third-party data, so the URL must be plain HTTPS from the
 * API's own CDN host with a whitelisted bitmap extension.
 */
fun parseWaifuPicsPage(text: String, page: Int): WallpaperPage {
    val results = runCatching {
        providerJson.decodeFromString<List<WaifuPicsResult>>(text)
    }.getOrElse {
        runCatching { listOf(providerJson.decodeFromString<WaifuPicsResult>(text)) }
            .getOrDefault(emptyList())
    }
    val items = results.filter {
        it.url.startsWith("https://") &&
            it.url.substringAfter("https://", "").substringBefore('/').endsWith(".waifu.pics") &&
            it.url.substringAfterLast('.').lowercase().substringBefore('?') in SAFE_IMAGE_EXTENSIONS
    }.map {
        OnlineBackground(
            id = "waifupics-${it.url.hashCode().toUInt()}",
            title = "Waifu.pics",
            author = "Waifu.pics",
            thumb = it.url,
            full = it.url,
            source = "https://waifu.pics",
            width = 0, height = 0
        )
    }.distinctBy { it.id }
    val current = page.coerceAtLeast(1)
    // Every request is random, so there is always "one more page".
    return WallpaperPage(items, current, current + 1)
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
