package app.bolbol.tv.feature.details

import app.bolbol.tv.core.model.Season
import app.bolbol.tv.core.model.WatchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayActionTest {

    // ---- movie ----

    @Test fun movieWithoutProgressPlaysFromTheStart() {
        val action = moviePlayAction(7, emptyList())
        assertEquals(PlayAction(WatchKey(7), 0), action)
        assertFalse(action.resume)
    }

    @Test fun movieWithUnfinishedProgressResumesAtTheSavedPosition() {
        val action = moviePlayAction(7, listOf(progress(7, 0, 0, positionMs = 2_832_000)))
        assertEquals(2_832_000L, action.startPositionMs)
        assertTrue(action.resume)
    }

    @Test fun completedMoviePlaysFromTheStart() {
        val action = moviePlayAction(7, listOf(progress(7, 0, 0, positionMs = 2_900_000, completed = true)))
        assertEquals(0L, action.startPositionMs)
    }

    @Test fun justStartedMovieIsNotResumed() {
        assertEquals(0L, moviePlayAction(7, listOf(progress(7, 0, 0, positionMs = 3_000))).startPositionMs)
    }

    @Test fun movieIgnoresOtherTitlesProgress() {
        assertEquals(0L, moviePlayAction(7, listOf(progress(8, 0, 0, positionMs = 600_000))).startPositionMs)
    }

    // ---- series ----

    private val show = series(1, season(1, 3), season(2, 2))

    @Test fun seriesWithoutProgressStartsAtTheFirstEpisode() {
        assertEquals(PlayAction(WatchKey(1, 1, 1), 0), seriesPlayAction(show, emptyList()))
    }

    @Test fun seriesContinuesTheLatestUnfinishedEpisodeAtItsPosition() {
        val p = listOf(
            progress(1, 1, 1, 2_900_000, completed = true, updatedAtMs = 10),
            progress(1, 1, 2, 600_000, updatedAtMs = 20),
        )
        assertEquals(PlayAction(WatchKey(1, 1, 2), 600_000), seriesPlayAction(show, p))
    }

    @Test fun seriesStartsTheEpisodeAfterTheLastCompletedOne() {
        val p = listOf(
            progress(1, 1, 1, 2_900_000, completed = true, updatedAtMs = 10),
            progress(1, 1, 2, 2_900_000, completed = true, updatedAtMs = 20),
        )
        assertEquals(PlayAction(WatchKey(1, 1, 3), 0), seriesPlayAction(show, p))
    }

    @Test fun seriesNextEpisodeCrossesIntoTheNextSeason() {
        val p = listOf(progress(1, 1, 3, 2_900_000, completed = true))
        assertEquals(PlayAction(WatchKey(1, 2, 1), 0), seriesPlayAction(show, p))
    }

    @Test fun furthestCompletedWinsOverAnOlderHalfWatchedEpisode() {
        val p = listOf(
            progress(1, 1, 1, 600_000, updatedAtMs = 5), // left half-watched long ago
            progress(1, 1, 2, 2_900_000, completed = true, updatedAtMs = 30),
        )
        assertEquals(PlayAction(WatchKey(1, 1, 3), 0), seriesPlayAction(show, p))
    }

    @Test fun furthestCompletedIsByWatchOrderNotByRecency() {
        // Episode 3 finished first, then episode 1 re-watched: the next one is S2E1, not episode 2.
        val p = listOf(
            progress(1, 1, 3, 2_900_000, completed = true, updatedAtMs = 5),
            progress(1, 1, 1, 2_900_000, completed = true, updatedAtMs = 50),
        )
        assertEquals(PlayAction(WatchKey(1, 2, 1), 0), seriesPlayAction(show, p))
    }

    @Test fun nextEpisodeWithSavedPositionResumesThere() {
        val p = listOf(
            progress(1, 1, 1, 2_900_000, completed = true, updatedAtMs = 30),
            progress(1, 1, 2, 800_000, updatedAtMs = 5), // older than the completion of episode 1
        )
        // The latest entry (episode 1) is completed, so we look at "after the furthest completed" = episode 2,
        // which has a saved position.
        assertEquals(PlayAction(WatchKey(1, 1, 2), 800_000), seriesPlayAction(show, p))
    }

    @Test fun finishedSeriesStartsOverFromTheFirstEpisode() {
        val p = listOf(progress(1, 2, 2, 2_900_000, completed = true))
        assertEquals(PlayAction(WatchKey(1, 1, 1), 0), seriesPlayAction(show, p))
    }

    @Test fun justStartedEpisodeIsNotResumed() {
        val p = listOf(progress(1, 1, 1, 2_000, updatedAtMs = 10))
        assertEquals(PlayAction(WatchKey(1, 1, 1), 0), seriesPlayAction(show, p))
    }

    @Test fun episodesWithoutStreamsAreSkipped() {
        val s = series(1, Season(1, listOf(episode(1, 1), episode(1, 2, streams = emptyList()), episode(1, 3))))
        val p = listOf(progress(1, 1, 1, 2_900_000, completed = true))
        assertEquals(PlayAction(WatchKey(1, 1, 3), 0), seriesPlayAction(s, p))
    }

    @Test fun seriesWithNothingPlayableHasNoAction() {
        val s = series(1, Season(1, listOf(episode(1, 1, streams = emptyList()))))
        assertNull(seriesPlayAction(s, emptyList()))
    }

    @Test fun progressOfUnknownEpisodesIsIgnored() {
        val p = listOf(progress(1, 9, 9, 600_000, updatedAtMs = 99))
        assertEquals(PlayAction(WatchKey(1, 1, 1), 0), seriesPlayAction(show, p))
    }
}
