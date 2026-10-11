package app.bolbol.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRequestsTest {
    private val stream = StreamOption(720, AudioType.SUBTITLED, "u", "s", "720", null)
    private fun ep(s: Int, e: Int) = Episode(s, e, "Episode $e", listOf(stream))
    private val summary = TitleSummary(7, null, "Show", "سریال", null, null, 2026, null, false, true)
    private fun details(type: TitleType, seasons: List<Season> = emptyList(), streams: List<StreamOption> = emptyList()) =
        TitleDetails(summary, type, "", null, null, null, emptyList(), emptyList(), emptyList(), emptyList(), null, null, streams, seasons, emptyList())

    private val series = details(
        TitleType.SERIES,
        seasons = listOf(Season(2, listOf(ep(2, 1))), Season(1, listOf(ep(1, 2), ep(1, 1)))),
    )

    @Test fun nextEpisodeCrossesSeasonsInOrder() {
        assertEquals(WatchKey(7, 1, 2), series.nextEpisodeKey(WatchKey(7, 1, 1)))
        assertEquals(WatchKey(7, 2, 1), series.nextEpisodeKey(WatchKey(7, 1, 2)))
        assertNull(series.nextEpisodeKey(WatchKey(7, 2, 1)))
    }

    @Test fun episodeRequestHasPersianLineAndNext() {
        val r = series.playbackRequest(WatchKey(7, 1, 1))!!
        assertEquals("فصل ۱ · قسمت ۱", r.subtitleLine)
        assertEquals(WatchKey(7, 1, 2), r.next)
        assertEquals(TitleType.SERIES, r.title.type)
    }

    @Test fun movieRequestUsesMovieStreams() {
        val movie = details(TitleType.MOVIE, streams = listOf(stream))
        val r = movie.playbackRequest(WatchKey(7), startPositionMs = 1000)!!
        assertNull(r.subtitleLine); assertNull(r.next); assertEquals(1000L, r.startPositionMs)
    }

    @Test fun unknownEpisodeOrNoStreamsIsNull() {
        assertNull(series.playbackRequest(WatchKey(7, 9, 9)))
        assertNull(details(TitleType.MOVIE).playbackRequest(WatchKey(7)))
    }

    @Test fun previousEpisodeCrossesSeasonsInOrder() {
        assertNull(series.previousEpisodeKey(WatchKey(7, 1, 1)))
        assertEquals(WatchKey(7, 1, 1), series.previousEpisodeKey(WatchKey(7, 1, 2)))
        assertEquals(WatchKey(7, 1, 2), series.previousEpisodeKey(WatchKey(7, 2, 1)))
        assertNull(series.previousEpisodeKey(WatchKey(7, 9, 9)))
    }

    @Test fun episodeRequestCarriesPreviousAndSortedSeasons() {
        val r = series.playbackRequest(WatchKey(7, 1, 2))!!
        assertEquals(WatchKey(7, 1, 1), r.previous)
        assertEquals(listOf(1, 2), r.seasons.map { it.number })
        assertEquals(listOf(1, 2), r.seasons.first().episodes.map { it.number })
    }

    @Test fun movieRequestHasNoPreviousOrSeasons() {
        val r = details(TitleType.MOVIE, streams = listOf(stream)).playbackRequest(WatchKey(7))!!
        assertNull(r.previous)
        assertEquals(emptyList<Season>(), r.seasons)
    }
}
