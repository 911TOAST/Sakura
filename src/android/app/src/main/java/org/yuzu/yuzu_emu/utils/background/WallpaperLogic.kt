// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils.background

import java.time.LocalDate
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

@Serializable
data class WallpaperTuning(
    val fit: Int = FILL,
    val dim: Int = 30,
    val blur: Int = 12,
    val zoom: Int = 100,
    val x: Int = 50,
    val y: Int = 50
) {
    fun clamped() = copy(
        fit = fit.coerceIn(FILL, STRETCH), dim = dim.coerceIn(0, 100),
        blur = blur.coerceIn(0, 32),
        // The zoom control has a 5% step; snapping here keeps stored values renderable.
        zoom = (zoom.coerceIn(100, 300) / 5) * 5,
        x = x.coerceIn(0, 100), y = y.coerceIn(0, 100)
    )

    companion object {
        const val FILL = 0
        const val FIT = 1
        const val STRETCH = 2
    }
}

@Serializable
data class WallpaperSelection(val uri: String = "", val online: OnlineBackground? = null)

data class WallpaperTransform(val scaleX: Float, val scaleY: Float, val dx: Float, val dy: Float)

fun wallpaperTransform(
    imageWidth: Int, imageHeight: Int, width: Int, height: Int, tuning: WallpaperTuning
): WallpaperTransform {
    require(imageWidth > 0 && imageHeight > 0 && width > 0 && height > 0)
    val t = tuning.clamped()
    val sx = width.toFloat() / imageWidth
    val sy = height.toFloat() / imageHeight
    if (t.fit == WallpaperTuning.STRETCH) return WallpaperTransform(sx, sy, 0f, 0f)
    val scale = if (t.fit == WallpaperTuning.FIT) min(sx, sy) else max(sx, sy) * t.zoom / 100f
    val remainingX = width - imageWidth * scale
    val remainingY = height - imageHeight * scale
    return WallpaperTransform(
        scale, scale,
        remainingX * (if (t.fit == WallpaperTuning.FILL) t.x / 100f else 0.5f),
        remainingY * (if (t.fit == WallpaperTuning.FILL) t.y / 100f else 0.5f)
    )
}

data class RecentWallpaperGame(val title: String, val lastPlayed: Long)

fun normalizeWallpaperQuery(title: String): String =
    title.replace(Regex("[™®©]"), "").replace(Regex("[\\s\\p{Z}]+"), " ").trim().take(200)

fun recentWallpaperGames(games: List<RecentWallpaperGame>, now: Long): List<RecentWallpaperGame> =
    games.asSequence()
        .filter { it.lastPlayed in (now - 30L * 24 * 60 * 60 * 1000)..now && it.lastPlayed > 0 }
        .map { it.copy(title = normalizeWallpaperQuery(it.title)) }
        .filter { it.title.isNotEmpty() }
        .sortedByDescending { it.lastPlayed }
        .distinctBy { it.title.lowercase(Locale.ROOT) }
        .take(3).toList()

fun recommendationKey(games: List<RecentWallpaperGame>, day: LocalDate): String =
    day.toString() + "|" + games.map { it.title.lowercase(Locale.ROOT) }.sorted()
        .joinToString("|") { "${it.length}:$it" }

fun mergeWallpaperPage(old: List<OnlineBackground>, page: List<OnlineBackground>) =
    (old + page).distinctBy { it.id }

/** A response from an earlier query must never update the current results. */
class WallpaperRequestGeneration {
    private var current = 0L
    fun next(): Long = ++current
    fun accepts(generation: Long): Boolean = generation == current
}

/** Reversible commit ordering, also used by the encrypted store. */
fun commitWallpaperSelection(commitImage: () -> Unit, commitSettings: () -> Unit, rollback: () -> Unit) {
    try {
        commitImage()
        commitSettings()
    } catch (error: Exception) {
        try {
            rollback()
        } catch (rollbackError: Exception) {
            error.addSuppressed(rollbackError)
        }
        throw error
    }
}
