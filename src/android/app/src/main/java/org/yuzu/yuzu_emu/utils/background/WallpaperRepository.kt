// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils.background

import android.content.Context
import androidx.preference.PreferenceManager
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.GameHelper

@Serializable
data class WallpaperSearchSpec(
    val query: String = "",
    val orientation: Int = 0,
    val resolution: Int = 0,
    val sort: Int = 0,
    val catalogUrl: String = ""
)

data class WallpaperPage(val items: List<OnlineBackground>, val page: Int, val lastPage: Int)

@Serializable
data class WallpaperRecommendation(val game: String, val items: List<OnlineBackground>)

fun wallhavenSearchUrl(spec: WallpaperSearchSpec, page: Int): HttpUrl {
    val builder = "https://wallhaven.cc/api/v1/search".toHttpUrl().newBuilder()
        .addQueryParameter("q", spec.query.trim().take(200))
        .addQueryParameter("purity", "100")
        .addQueryParameter("categories", "111")
        .addQueryParameter("page", page.coerceAtLeast(1).toString())
        .addQueryParameter("sorting", when (spec.sort) { 1 -> "date_added"; 2 -> "toplist"; else -> "relevance" })
        .addQueryParameter("order", "desc")
    if (spec.sort == 2) builder.addQueryParameter("topRange", "1d")
    if (spec.resolution > 0) {
        val hd = spec.resolution == 1
        builder.addQueryParameter(
            "atleast",
            when (spec.orientation) {
                1 -> if (hd) "1920x1080" else "3840x2160"
                2 -> if (hd) "1080x1920" else "2160x3840"
                else -> if (hd) "1080x1080" else "2160x2160"
            }
        )
    }
    return builder.build()
}

/** Wallhaven has no orientation parameter, so its results are narrowed here instead. */
fun filterWallpaperResults(items: List<OnlineBackground>, spec: WallpaperSearchSpec): List<OnlineBackground> =
    items.filter { item ->
        val short = minOf(item.width, item.height)
        when (spec.orientation) {
            1 -> item.width > item.height
            2 -> item.height > item.width
            3 -> item.width > 0 && item.width == item.height
            else -> true
        } && when (spec.resolution) {
            1 -> short >= 1080
            2 -> short >= 2160
            else -> true
        }
    }

@Serializable
private data class WallhavenResponse(val data: List<WallhavenImage> = emptyList(), val meta: WallhavenMeta = WallhavenMeta())
@Serializable
private data class WallhavenMeta(@SerialName("current_page") val page: Int = 1, @SerialName("last_page") val last: Int = 1)
@Serializable
private data class WallhavenThumbs(val large: String = "")
@Serializable
private data class WallhavenImage(
    val id: String = "", val url: String = "", val path: String = "", val purity: String = "",
    @SerialName("dimension_x") val width: Int = 0,
    @SerialName("dimension_y") val height: Int = 0,
    @SerialName("file_size") val size: Long = 0,
    val thumbs: WallhavenThumbs = WallhavenThumbs()
)

class WallpaperRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("wallpaper_discovery", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private var catalog: Pair<String, List<OnlineBackground>>? = null
    private val recommendationLock = Any()
    private var recommendationGeneration = 0L

    suspend fun search(spec: WallpaperSearchSpec, page: Int): WallpaperPage = withContext(Dispatchers.IO) {
        if (spec.catalogUrl.isNotBlank()) return@withContext searchCatalog(spec, page)
        val result = parseWallhavenPage(WallpaperHttp.get(wallhavenSearchUrl(spec, page).toString(), api = true).decodeToString())
        result.copy(items = filterWallpaperResults(result.items, spec))
    }

    private suspend fun searchCatalog(spec: WallpaperSearchSpec, page: Int): WallpaperPage {
        if (catalog?.first != spec.catalogUrl) {
            val result = OnlineBackgroundCatalog.parseCatalog(WallpaperHttp.get(spec.catalogUrl).decodeToString())
            catalog = spec.catalogUrl to result.categories.flatMap { it.items }.distinctBy { it.id }
        }
        val items = catalog!!.second.filter { item ->
            (spec.query.isBlank() || item.title.contains(spec.query, true) || item.author.contains(spec.query, true)) &&
                when (spec.orientation) {
                    1 -> item.width > item.height
                    2 -> item.height > item.width
                    3 -> item.width > 0 && item.width == item.height
                    else -> true
                } && when (spec.resolution) {
                    1 -> minOf(item.width, item.height) >= 1080
                    2 -> minOf(item.width, item.height) >= 2160
                    else -> true
                }
        }
        return WallpaperPage(items.drop((page - 1) * 24).take(24), page, ((items.size + 23) / 24).coerceAtLeast(1))
    }

    suspend fun recentGames(): List<RecentWallpaperGame> = withContext(Dispatchers.IO) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(app)
        val games = GameHelper.cachedGameList.toList().ifEmpty {
            preferences.getStringSet(GameHelper.KEY_GAMES, emptySet()).orEmpty().mapNotNull {
                runCatching { json.decodeFromString<Game>(it) }.getOrNull()
            }
        }
        recentWallpaperGames(games.map { RecentWallpaperGame(it.title, preferences.getLong(it.keyLastPlayedTime, 0L)) }, System.currentTimeMillis())
    }

    suspend fun recommendations(games: List<RecentWallpaperGame>, force: Boolean): List<WallpaperRecommendation> = withContext(Dispatchers.IO) {
        val generation = synchronized(recommendationLock) { recommendationGeneration }
        val key = recommendationKey(games, LocalDate.now())
        if (!force && prefs.getString("recommendation_key", null) == key) {
            runCatching { json.decodeFromString<List<WallpaperRecommendation>>(prefs.getString("recommendations", "[]")!!) }
                .getOrNull()?.let { return@withContext it }
        }
        val result = games.map { game ->
            WallpaperRecommendation(game.title, search(WallpaperSearchSpec(query = game.title), 1).items.take(6))
        }
        val jobContext = currentCoroutineContext()
        synchronized(recommendationLock) {
            jobContext.ensureActive()
            if (generation == recommendationGeneration) {
                prefs.edit().putString("recommendation_key", key).putString("recommendations", json.encodeToString(result)).apply()
            }
        }
        result
    }

    fun clearRecommendations() = synchronized(recommendationLock) {
        recommendationGeneration++
        prefs.edit().remove("recommendation_key").remove("recommendations").apply()
    }

    suspend fun popular(force: Boolean): List<OnlineBackground> = withContext(Dispatchers.IO) {
        val key = LocalDate.now().toString()
        if (!force && prefs.getString("popular_day", null) == key) {
            runCatching { json.decodeFromString<List<OnlineBackground>>(prefs.getString("popular", "[]")!!) }
                .getOrNull()?.let { return@withContext it }
        }
        val result = search(WallpaperSearchSpec(sort = 2), 1).items
        currentCoroutineContext().ensureActive()
        prefs.edit().putString("popular_day", key).putString("popular", json.encodeToString(result)).apply()
        result
    }

    fun history(): List<String> = runCatching {
        json.decodeFromString<List<String>>(prefs.getString("history", "[]")!!)
    }.getOrDefault(emptyList()).take(10)

    fun rememberSearch(query: String) {
        val text = query.trim().take(200)
        if (text.isEmpty()) return
        prefs.edit().putString("history", json.encodeToString((listOf(text) + history()).distinct().take(10))).apply()
    }

    fun clearHistory() { prefs.edit().remove("history").apply() }

    companion object {
        fun parseWallhavenPage(text: String): WallpaperPage {
            val result = Json { ignoreUnknownKeys = true; coerceInputValues = true }.decodeFromString<WallhavenResponse>(text)
            val items = result.data.filter {
                it.purity == "sfw" && it.id.isNotBlank() && it.width > 0 && it.height > 0 &&
                    it.size in 1..OnlineBackgroundCatalog.MAX_IMAGE_BYTES.toLong() &&
                    it.path.toHttpUrlOrNull()?.isHttps == true && it.thumbs.large.toHttpUrlOrNull()?.isHttps == true
            }.map {
                OnlineBackground(
                    id = "wallhaven-${it.id}", title = "Wallhaven · ${it.id}", thumb = it.thumbs.large,
                    full = it.path, source = it.url.takeIf { url -> url.toHttpUrlOrNull()?.isHttps == true }.orEmpty(),
                    width = it.width, height = it.height
                )
            }.distinctBy { it.id }
            val page = result.meta.page.coerceAtLeast(1)
            return WallpaperPage(items, page, result.meta.last.coerceAtLeast(page))
        }
    }
}
