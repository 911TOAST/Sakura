// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaytimeLogicTest {
    private val today = LocalDate.of(2026, 11, 11)

    @Test
    fun sessionsAccumulateSecondsAndLaunches() {
        var data = PlaytimeData()
        data = recordPlaytime(data, "game", 0, today, newSession = true)
        data = recordPlaytime(data, "game", 120, today)
        data = recordPlaytime(data, "game", 0, today, newSession = true)
        data = recordPlaytime(data, "game", 60, today)
        assertEquals(180L, data.games["game"]!!.seconds)
        assertEquals(2, data.games["game"]!!.sessions)
        assertEquals(180L, data.days[today.toString()])
        assertEquals(2, totalSessions(data))
    }

    @Test
    fun zeroSegmentsAndEmptyKeysAreIgnored() {
        val data = PlaytimeData()
        assertSame(data, recordPlaytime(data, "game", 0, today))
        assertSame(data, recordPlaytime(data, "", 50, today))
    }

    @Test
    fun streakSurvivesAQuietTodayButBreaksOnAGap() {
        val days = mapOf(
            today.toString() to 60L,
            today.minusDays(1).toString() to 60L,
            today.minusDays(2).toString() to 60L
        )
        assertEquals(3, playtimeStreak(days, today))
        assertEquals(3, playtimeStreak(days - today.toString(), today))
        assertEquals(1, playtimeStreak(days - today.minusDays(1).toString(), today))
        assertEquals(0, playtimeStreak(emptyMap(), today))
    }

    @Test
    fun weekSumsOnlyTheLastSevenDays() {
        val days = (0L..8L).associate { today.minusDays(it).toString() to 60L }
        assertEquals(7 * 60L, weekPlaytime(days, today))
    }

    @Test
    fun dayHistoryIsPrunedToAYear() {
        var data = PlaytimeData()
        (0L..400L).forEach { data = recordPlaytime(data, "g", 1, today.minusDays(it)) }
        assertEquals(366, data.days.size)
        assertTrue(data.days.containsKey(today.toString()))
        assertFalse(data.days.containsKey(today.minusDays(400).toString()))
    }

    @Test
    fun topGamesAreSortedByPlaytime() {
        var data = PlaytimeData()
        data = recordPlaytime(data, "a", 10, today)
        data = recordPlaytime(data, "b", 30, today)
        data = recordPlaytime(data, "c", 20, today)
        assertEquals(listOf("b", "c"), topPlaytimeGames(data, 2).map { it.first })
        assertEquals(60L, totalPlaytime(data))
    }
}
