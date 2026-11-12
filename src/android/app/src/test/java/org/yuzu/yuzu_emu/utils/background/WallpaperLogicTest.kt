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
    fun safebooruSearchUsesTagQueryAndScoreSort() {
        val url = safebooruSearchUrl(WallpaperSearchSpec(query = "forest landscape", sort = 2), 3)
        assertEquals("dapi", url.queryParameter("page"))
        assertEquals("1", url.queryParameter("json"))
        assertEquals("2", url.queryParameter("pid"))
        assertEquals("forest landscape sort:score", url.queryParameter("tags"))
        assertEquals("", safebooruSearchUrl(WallpaperSearchSpec(), 1).queryParameter("tags"))
    }

    @Test
    fun safebooruResultsKeepOnlyStaticSafeImages() {
        val text = """[
            {"id":1,"directory":"aa","image":"pic.jpg","hash":"ab12","width":1920,"height":1080,"owner":"artist","rating":"s"},
            {"id":2,"directory":"bb","image":"clip.webm","hash":"cd34","width":1920,"height":1080},
            {"id":3,"directory":"../evil","image":"pic.png","hash":"ef56","width":100,"height":100},
            {"id":4,"directory":"cc","image":"pic.png","hash":"gh78","width":1080,"height":1920,"rating":"e"}
        ]"""
        val page = parseSafebooruPage(text, 1)
        assertEquals(listOf("safebooru-1"), page.items.map { it.id })
        assertEquals("https://safebooru.org/images/aa/pic.jpg", page.items[0].full)
        assertEquals("https://safebooru.org/thumbnails/aa/thumbnail_ab12.jpg", page.items[0].thumb)
        assertEquals(1, page.lastPage)
    }

    @Test
    fun safebooruFullRawPageImpliesNextPage() {
        val posts = (1..24).joinToString(",", "[", "]") {
            """{"id":$it,"directory":"aa","image":"pic$it.jpg","hash":"ab$it","width":100,"height":100}"""
        }
        assertEquals(2, parseSafebooruPage(posts, 1).lastPage)
    }

    @Test
    fun bingImagesUseFixedRenditionsAndRejectForeignPaths() {
        val text = """{"images":[
            {"urlbase":"/th?id=OHR.Aurora_EN-US123","title":"Aurora night","copyright":"Photographer"},
            {"urlbase":"/th?id=OHR.City_EN-US456","title":"","copyright":"Another"},
            {"urlbase":"https://evil.example/x","title":"Bad","copyright":""}
        ]}"""
        val page = parseBingPage(text, 1)
        assertEquals(listOf("bing-OHR.Aurora_EN-US123", "bing-OHR.City_EN-US456"), page.items.map { it.id })
        assertEquals("https://www.bing.com/th?id=OHR.Aurora_EN-US123_1920x1080.jpg", page.items[0].full)
        assertEquals("Another", page.items[1].title)
        assertEquals(1, page.lastPage)
    }

    @Test
    fun bingQueryFiltersTitlesLocally() {
        val items = listOf(
            OnlineBackground(id = "a", title = "Aurora night", author = "Photographer"),
            OnlineBackground(id = "b", title = "City lights", author = "Another")
        )
        assertEquals(listOf("a"), filterBingResults(items, WallpaperSearchSpec(query = "aurora")).map { it.id })
        assertEquals(listOf("b"), filterBingResults(items, WallpaperSearchSpec(query = "another")).map { it.id })
        assertEquals(items, filterBingResults(items, WallpaperSearchSpec(query = " ")))
    }

    @Test
    fun unknownProvidersFallBackToWallhaven() {
        assertEquals(WallpaperProviders.WALLHAVEN, WallpaperProviders.normalize("nope"))
        assertEquals(WallpaperProviders.SAFEBOORU, WallpaperProviders.normalize("safebooru"))
        assertEquals(WallpaperProviders.BING, WallpaperProviders.normalize("bing"))
    }

    @Test
    fun atmosphereDefaultsOnAndSurvivesSerializationRoundTrip() {
        val appearance = WallpaperAppearance()
        assertTrue(appearance.aurora)
        assertTrue(appearance.parallax)
        val encoded = kotlinx.serialization.json.Json.encodeToString(WallpaperAppearance.serializer(), appearance)
        assertEquals(appearance, kotlinx.serialization.json.Json.decodeFromString(WallpaperAppearance.serializer(), encoded))
        // Older saved state without the new keys must keep the atmosphere on.
        assertEquals(appearance, kotlinx.serialization.json.Json.decodeFromString(WallpaperAppearance.serializer(), "{}"))
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
            RecentWallpaperGame("  Aurora\u2122  ", now - dayMillis),
            RecentWallpaperGame("aurora", now),
            RecentWallpaperGame("Comet", now - 31 * dayMillis),
            RecentWallpaperGame("Starfall", now - 2 * dayMillis),
            RecentWallpaperGame("Nebula", now - 3 * dayMillis),
            RecentWallpaperGame("Pixel", now - 4 * dayMillis)
        )
        assertEquals(listOf("aurora", "Starfall", "Nebula"), recentWallpaperGames(games, now).map { it.title })
    }

    @Test
    fun restrictedFranchiseTitlesNeverSeedRecommendations() {
        val now = 1_000_000_000_000L
        val games = listOf(
            RecentWallpaperGame("The Legend of Zelda: Tears of the Kingdom", now),
            RecentWallpaperGame("Super Mario Odyssey", now - 1),
            RecentWallpaperGame("Aurora", now - 2)
        )
        assertEquals(listOf("Aurora"), recentWallpaperGames(games, now).map { it.title })
        assertTrue(isRestrictedWallpaperTitle("Pokémon Scarlet"))
        assertTrue(isRestrictedWallpaperTitle("Metroid Prime Remastered"))
        assertFalse(isRestrictedWallpaperTitle("Celeste"))
        assertFalse(isRestrictedWallpaperTitle("Hollow Knight"))
    }

    @Test
    fun recommendationsInvalidateOnNewDayOrDifferentGames() {
        val games = listOf(RecentWallpaperGame("Aurora", 1), RecentWallpaperGame("Comet", 2))
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
