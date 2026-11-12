// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.stats

import android.content.Context
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.yuzu.yuzu_emu.YuzuApplication

@Serializable
data class GamePlaytime(val seconds: Long = 0, val sessions: Int = 0)

/**
 * Playtime is keyed by game path, the same key space as the library's last-played stamps.
 * [days] holds ISO-date totals for streaks and weekly summaries; only the last year is kept.
 */
@Serializable
data class PlaytimeData(
    val games: Map<String, GamePlaytime> = emptyMap(),
    val days: Map<String, Long> = emptyMap()
)

private const val MAX_DAY_ENTRIES = 366

fun recordPlaytime(
    data: PlaytimeData, gameKey: String, seconds: Long, day: LocalDate, newSession: Boolean = false
): PlaytimeData {
    if (gameKey.isEmpty() || (seconds <= 0 && !newSession)) return data
    val old = data.games[gameKey] ?: GamePlaytime()
    val games = data.games + (gameKey to GamePlaytime(
        seconds = old.seconds + seconds.coerceAtLeast(0),
        sessions = old.sessions + if (newSession) 1 else 0
    ))
    val days = if (seconds > 0) {
        val updated = data.days + (day.toString() to (data.days[day.toString()] ?: 0L) + seconds)
        updated.entries.sortedByDescending { it.key }.take(MAX_DAY_ENTRIES).associate { it.toPair() }
    } else {
        data.days
    }
    return PlaytimeData(games, days)
}

fun totalPlaytime(data: PlaytimeData): Long = data.games.values.sumOf { it.seconds }

fun totalSessions(data: PlaytimeData): Int = data.games.values.sumOf { it.sessions }

/** Consecutive played days ending today; a quiet today keeps yesterday's streak alive. */
fun playtimeStreak(days: Map<String, Long>, today: LocalDate): Int {
    var cursor = if ((days[today.toString()] ?: 0L) > 0) today else today.minusDays(1)
    var streak = 0
    while ((days[cursor.toString()] ?: 0L) > 0) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return streak
}

fun weekPlaytime(days: Map<String, Long>, today: LocalDate): Long =
    (0L..6L).sumOf { offset -> days[today.minusDays(offset).toString()] ?: 0L }

fun topPlaytimeGames(data: PlaytimeData, limit: Int): List<Pair<String, GamePlaytime>> =
    data.games.entries.sortedByDescending { it.value.seconds }.take(limit).map { it.key to it.value }

/** Foreground playtime only; segments are measured with the monotonic clock by the caller. */
object PlaytimeStore {
    private const val PREFS = "playtime_stats"
    private const val KEY_DATA = "data"
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun read(): PlaytimeData = runCatching {
        json.decodeFromString<PlaytimeData>(prefs().getString(KEY_DATA, "")!!)
    }.getOrDefault(PlaytimeData())

    fun record(gameKey: String, seconds: Long, newSession: Boolean = false) {
        synchronized(this) {
            val updated = recordPlaytime(read(), gameKey, seconds, LocalDate.now(), newSession)
            prefs().edit().putString(KEY_DATA, json.encodeToString(updated)).apply()
        }
    }

    private fun prefs() =
        YuzuApplication.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
