package app.bolbol.tv.feature.playback

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.Episode
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.PlaybackRequest
import app.bolbol.tv.core.model.Season
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.model.WatchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeNavigationTest {
    private val id = 7L
    private val sub = StreamOption(720, AudioType.SUBTITLED, "https://x/s.mp4", "https://x/s.vtt", "720p", null)
    private val dub = StreamOption(720, AudioType.DUBBED, "https://x/d.mp4", null, "720p", null)
    private val title = TitleSummary(id, TitleType.SERIES, "Show", "سریال", null, null, 2024, null, hasDub = true, hasSubtitle = true)

    private val season1 = Season(
        1,
        listOf(
            Episode(1, 1, "Episode 1", listOf(sub, dub)),
            Episode(1, 2, "The Long Night", listOf(sub)),
            Episode(1, 3, "  ", listOf(sub)),
            Episode(1, 4, "قسمت ۴", emptyList()),
        ),
    )
    private val season2 = Season(2, listOf(Episode(2, 1, "Episode 1", listOf(dub))))

    private fun request(
        key: WatchKey,
        previous: WatchKey? = null,
        next: WatchKey? = null,
        seasons: List<Season> = listOf(season1, season2),
    ) = PlaybackRequest(key, title, null, listOf(sub), null, next = next, previous = previous, seasons = seasons)

    private fun progress(season: Int, episode: Int, positionMs: Long, durationMs: Long = 100_000, completed: Boolean = false, postId: Long = id) =
        PlaybackProgress(WatchKey(postId, season, episode), positionMs, durationMs, updatedAtMs = 0, completed = completed)

    // ---- prev / next availability (v1.1-1) ---------------------------------------------------

    @Test fun movieHasNoEpisodeButtonsOrPanel() {
        val movie = PlaybackRequest(WatchKey(id), title, null, listOf(sub), null, next = null)
        assertFalse(movie.episodeButtons().visible)
        assertFalse(movie.hasEpisodePanel())
    }

    @Test fun firstEpisodeHasNextOnly() {
        val b = request(WatchKey(id, 1, 1), next = WatchKey(id, 1, 2)).episodeButtons()
        assertTrue(b.visible)
        assertFalse(b.hasPrevious)
        assertTrue(b.hasNext)
        assertEquals(WatchKey(id, 1, 2), b.next)
    }

    @Test fun lastEpisodeHasPreviousOnly() {
        val b = request(WatchKey(id, 2, 1), previous = WatchKey(id, 1, 4)).episodeButtons()
        assertTrue(b.visible)
        assertTrue(b.hasPrevious)
        assertFalse(b.hasNext)
        assertEquals(WatchKey(id, 1, 4), b.previous)
    }

    @Test fun middleEpisodeHasBoth() {
        val b = request(WatchKey(id, 1, 2), previous = WatchKey(id, 1, 1), next = WatchKey(id, 1, 3)).episodeButtons()
        assertTrue(b.hasPrevious && b.hasNext)
    }

    @Test fun panelNeedsEpisodes() {
        assertTrue(request(WatchKey(id, 1, 1)).hasEpisodePanel())
        assertFalse(request(WatchKey(id, 1, 1), seasons = emptyList()).hasEpisodePanel())
        assertFalse(request(WatchKey(id, 1, 1), seasons = listOf(Season(1, emptyList()))).hasEpisodePanel())
    }

    @Test fun switchOnlyToAnotherEpisodeOfThisTitle() {
        val r = request(WatchKey(id, 1, 2))
        assertTrue(r.canSwitchTo(WatchKey(id, 2, 1)))
        assertFalse("the playing episode", r.canSwitchTo(WatchKey(id, 1, 2)))
        assertFalse("another title", r.canSwitchTo(WatchKey(99, 1, 1)))
        assertFalse("a movie key", r.canSwitchTo(WatchKey(id)))
        val movie = PlaybackRequest(WatchKey(id), title, null, listOf(sub), null, next = null)
        assertFalse("from a movie", movie.canSwitchTo(WatchKey(id, 1, 1)))
    }

    // ---- episode rows (v1.1-4) -----------------------------------------------------------------

    @Test fun rowsCarryNumberTitleDubPlayableAndCurrent() {
        val rows = episodeRows(id, season1, emptyList(), current = WatchKey(id, 1, 2))
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.number })
        assertEquals(listOf(null, "The Long Night", null, null), rows.map { it.title })
        assertEquals(listOf(true, false, false, false), rows.map { it.dubbed })
        assertEquals(listOf(true, true, true, false), rows.map { it.playable })
        assertEquals(listOf(false, true, false, false), rows.map { it.current })
        assertEquals(WatchKey(id, 1, 3), rows[2].key)
    }

    @Test fun inProgressShowsFractionWithAMinimum() {
        val rows = episodeRows(
            id,
            season1,
            listOf(progress(1, 1, positionMs = 40_000), progress(1, 2, positionMs = 6_000, durationMs = 3_600_000)),
            current = WatchKey(id, 1, 1),
        )
        assertEquals(0.4f, rows[0].fraction, 0.0001f)
        assertEquals("a minute into an hour still shows", MIN_VISIBLE_FRACTION, rows[1].fraction, 0.0001f)
        assertFalse(rows[0].watched)
    }

    @Test fun completedIsWatchedWithoutBar() {
        val rows = episodeRows(id, season1, listOf(progress(1, 3, positionMs = 98_000, completed = true)), WatchKey(id, 1, 1))
        assertTrue(rows[2].watched)
        assertEquals(0f, rows[2].fraction, 0f)
    }

    @Test fun barelyStartedOrUnknownDurationShowsNoBar() {
        assertEquals(0f, progressFraction(progress(1, 1, positionMs = 3_000)), 0f)
        assertEquals(0f, progressFraction(progress(1, 1, positionMs = 60_000, durationMs = 0)), 0f)
        assertEquals(0f, progressFraction(null), 0f)
    }

    @Test fun progressOfOtherSeasonsAndTitlesIsIgnored() {
        val rows = episodeRows(
            id,
            season1,
            listOf(progress(2, 1, positionMs = 50_000, completed = true), progress(1, 1, positionMs = 50_000, completed = true, postId = 99)),
            WatchKey(id, 1, 1),
        )
        assertTrue(rows.none { it.watched || it.fraction > 0f })
    }

    @Test fun panelOpensOnThePlayingSeason() {
        assertEquals(2, initialPanelSeason(listOf(season1, season2), WatchKey(id, 2, 1)))
        assertEquals("unknown season falls back to the first", 1, initialPanelSeason(listOf(season1, season2), WatchKey(id, 5, 1)))
        assertNull(initialPanelSeason(emptyList(), WatchKey(id, 1, 1)))
    }

    @Test fun genericTitlesAreHidden() {
        assertNull(episodeTitle("Episode 12"))
        assertNull(episodeTitle("ep. 3"))
        assertNull(episodeTitle("قسمت ۱۲"))
        assertNull(episodeTitle(""))
        assertEquals("Pilot", episodeTitle(" Pilot "))
    }
}
