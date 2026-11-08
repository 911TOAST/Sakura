// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils.background

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yuzu.yuzu_emu.features.settings.ui.WallpaperDraft

class WallpaperLogicTest {
    private val day = LocalDate.of(2026, 11, 7)

    @Test
    fun searchEncodesQueryAndStaysSafeForWork() {
        val url = wallhavenSearchUrl(WallpaperSearchSpec(query = "a b&c/d"), 2)
        assertEquals("100", url.queryParameter("purity"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("a b&c/d", url.queryParameter("q"))
        assertEquals("toplist", wallhavenSearchUrl(WallpaperSearchSpec(sort = 2), 1).queryParameter("sorting"))
        assertEquals("1d", wallhavenSearchUrl(WallpaperSearchSpec(sort = 2), 1).queryParameter("topRange"))
    }

    @Test
    fun searchResultsAreDeduplicatedAcrossPages() {
        val first = listOf(item("a"), item("b"))
        val second = listOf(item("b"), item("c"))
        assertEquals(listOf("a", "b", "c"), mergeWallpaperPage(first, second).map { it.id })
    }

    @Test
    fun staleResponsesAreRejected() {
        val generations = WallpaperRequestGeneration()
        val first = generations.next()
        val second = generations.next()
        assertFalse(generations.accepts(first))
        assertTrue(generations.accepts(second))
    }

    @Test
    fun recentGamesKeepNewestDistinctTitlesWithinThirtyDays() {
        val now = 1_000_000_000_000L
        val dayMillis = 24 * 60 * 60 * 1000L
        val games = listOf(
            RecentWallpaperGame("  Zelda\u2122  ", now - dayMillis),
            RecentWallpaperGame("zelda", now),
            RecentWallpaperGame("Mario", now - 31 * dayMillis),
            RecentWallpaperGame("Pokemon", now - 2 * dayMillis),
            RecentWallpaperGame("Metroid", now - 3 * dayMillis),
            RecentWallpaperGame("Kirby", now - 4 * dayMillis)
        )
        assertEquals(listOf("zelda", "Pokemon", "Metroid"), recentWallpaperGames(games, now).map { it.title })
    }

    @Test
    fun recommendationsInvalidateOnNewDayOrDifferentGames() {
        val games = listOf(RecentWallpaperGame("Zelda", 1), RecentWallpaperGame("Mario", 2))
        val key = recommendationKey(games, day)
        assertTrue(key == recommendationKey(games.reversed(), day))
        assertFalse(key == recommendationKey(games, day.plusDays(1)))
        assertFalse(key == recommendationKey(listOf(games[0]), day))
    }

    @Test
    fun fillPansWithinTheCroppedImage() {
        val tuning = WallpaperTuning(fit = WallpaperTuning.FILL, zoom = 100, x = 0, y = 100, dim = 30)
        val transform = wallpaperTransform(2000, 1000, 1000, 1000, tuning)
        assertEquals(0.5f, transform.scaleX, 0.0001f)
        assertEquals(0f, transform.dx, 0.0001f)
        assertEquals(500f, transform.dy, 0.0001f)

        val centered = wallpaperTransform(2000, 1000, 1000, 1000, tuning.copy(x = 50, y = 50))
        assertEquals(0f, centered.dx, 0.0001f)
        assertEquals(250f, centered.dy, 0.0001f)
    }

    @Test
    fun fitLetterboxesAndStretchDistorts() {
        val fit = wallpaperTransform(2000, 1000, 1000, 1000, WallpaperTuning(fit = WallpaperTuning.FIT))
        assertEquals(0.5f, fit.scaleX, 0.0001f)
        assertEquals(0f, fit.dy, 0.0001f)

        val stretch = wallpaperTransform(2000, 1000, 1000, 1000, WallpaperTuning(fit = WallpaperTuning.STRETCH))
        assertEquals(0.5f, stretch.scaleX, 0.0001f)
        assertEquals(1f, stretch.scaleY, 0.0001f)
    }

    @Test
    fun zoomIsOnlyAppliedToFill() {
        val fill = wallpaperTransform(1000, 1000, 1000, 1000, WallpaperTuning(fit = WallpaperTuning.FILL, zoom = 300))
        assertEquals(3f, fill.scaleX, 0.0001f)
        val fit = wallpaperTransform(1000, 1000, 1000, 1000, WallpaperTuning(fit = WallpaperTuning.FIT, zoom = 300))
        assertEquals(1f, fit.scaleX, 0.0001f)
    }

    @Test
    fun tuningClampsEveryControlToItsRange() {
        val clamped = WallpaperTuning(fit = 9, dim = 140, blur = -3, zoom = 999, x = -10, y = 140).clamped()
        assertEquals(WallpaperTuning.STRETCH, clamped.fit)
        assertEquals(100, clamped.dim)
        assertEquals(0, clamped.blur)
        assertEquals(300, clamped.zoom)
        assertEquals(0, clamped.x)
        assertEquals(100, clamped.y)
    }

    @Test
    fun failedCommitRollsBackAndReportsTheOriginalError() {
        var rolledBack = false
        val error = IllegalStateException("settings")
        val thrown = runCatching {
            commitWallpaperSelection(
                commitImage = {},
                commitSettings = { throw error },
                rollback = { rolledBack = true }
            )
        }.exceptionOrNull()
        assertTrue(rolledBack)
        assertEquals(error, thrown)
    }

    @Test
    fun failedRollbackDoesNotHideTheCommitError() {
        val thrown = runCatching {
            commitWallpaperSelection(
                commitImage = {},
                commitSettings = { throw IllegalStateException("commit") },
                rollback = { throw IllegalArgumentException("rollback") }
            )
        }.exceptionOrNull()
        assertEquals("commit", thrown?.message)
        assertEquals(1, thrown?.suppressed?.size)
    }

    @Test
    fun draftOnlyCountsRealChanges() {
        val original = WallpaperSettingsSnapshot("content://media/1", WallpaperTuning())
        assertFalse(WallpaperDraft(WallpaperSelection(original.uri), original.tuning, original).changed)
        assertTrue(WallpaperDraft(WallpaperSelection(original.uri), original.tuning.copy(dim = 60), original).changed)
        assertTrue(WallpaperDraft(WallpaperSelection("content://media/2"), original.tuning, original).changed)
    }

    private fun item(id: String) = OnlineBackground(id = id, full = "https://example.com/$id.jpg")
}
