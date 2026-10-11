package app.bolbol.tv.feature.details

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.Season
import app.bolbol.tv.core.model.WatchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsContentTest {

    private val all = listOf(
        stream(1080, AudioType.SUBTITLED),
        stream(720, AudioType.SUBTITLED),
        stream(480, AudioType.SUBTITLED),
        stream(720, AudioType.DUBBED),
    )

    // ---- audio / quality chips ----

    @Test fun noPrefsPreselectsTheDefaultQualityAndListsDubbedFirst() {
        val c = streamChoices(all, null)
        assertEquals(listOf(AudioType.DUBBED, AudioType.SUBTITLED), c.audioOptions)
        assertEquals(AudioType.SUBTITLED, c.selectedAudio) // first 720p in the list
        assertEquals(720, c.selectedQuality)
        assertEquals(listOf(1080, 720, 480), c.qualityOptions)
    }

    @Test fun prefsPickAudioAndQuality() {
        val c = streamChoices(all, PlaybackPrefs(audio = AudioType.SUBTITLED, quality = 1080))
        assertEquals(AudioType.SUBTITLED, c.selectedAudio)
        assertEquals(1080, c.selectedQuality)
    }

    @Test fun qualityOptionsBelongToTheSelectedAudio() {
        // The dub only exists in 720p: the quality chips show just that, the intent (1080) is kept elsewhere.
        val c = streamChoices(all, PlaybackPrefs(audio = AudioType.DUBBED, quality = 1080))
        assertEquals(AudioType.DUBBED, c.selectedAudio)
        assertEquals(720, c.selectedQuality)
        assertEquals(listOf(720), c.qualityOptions)
    }

    @Test fun onlyAvailableAudioIsOffered() {
        val c = streamChoices(listOf(stream(720, AudioType.DUBBED)), null)
        assertEquals(listOf(AudioType.DUBBED), c.audioOptions)
    }

    @Test fun unknownQualityIsNotOffered() {
        val c = streamChoices(listOf(stream(0), stream(480)), PlaybackPrefs(quality = 480))
        assertEquals(listOf(480), c.qualityOptions)
        assertEquals(480, c.selectedQuality)
        assertNull(streamChoices(listOf(stream(0)), null).selectedQuality)
    }

    @Test fun noStreamsMeansNoChoices() {
        assertEquals(StreamChoices.None, streamChoices(emptyList(), null))
    }

    @Test fun chipTapsKeepTheOtherIntent() {
        val base = PlaybackPrefs(audio = null, quality = 1080, subtitleScale = 1.3f)
        assertEquals(PlaybackPrefs(AudioType.DUBBED, 1080, 1.3f), base.withAudio(AudioType.DUBBED))
        // Tapping a quality also stores the audio that was on screen.
        assertEquals(PlaybackPrefs(AudioType.SUBTITLED, 480, 1.3f), base.withQuality(480, AudioType.SUBTITLED))
        assertEquals(PlaybackPrefs(AudioType.DUBBED, 480), PlaybackPrefs(AudioType.DUBBED).withQuality(480, AudioType.SUBTITLED))
    }

    // ---- episode titles ----

    @Test fun genericEpisodeTitlesAreNotShown() {
        assertNull(episodeSubtitle("Episode 3"))
        assertNull(episodeSubtitle("episode"))
        assertNull(episodeSubtitle("قسمت ۳"))
        assertNull(episodeSubtitle("  "))
        assertEquals("The Pilot", episodeSubtitle(" The Pilot "))
    }

    // ---- movie content ----

    @Test fun movieContentResumesAndOffersNoSeasons() {
        val c = buildDetailsContent(movie(5), listOf(progress(5, 0, 0, 600_000)), null, null)
        assertEquals(PlayAction(WatchKey(5), 600_000), c.primary)
        assertTrue(c.primary!!.resume)
        assertTrue(c.seasons.isEmpty() && c.episodes.isEmpty())
        assertFalse(c.isSeries)
    }

    @Test fun movieWithoutStreamsHasNoPrimaryAction() {
        assertNull(buildDetailsContent(movie(5, streams = emptyList()), emptyList(), null, null).primary)
    }

    // ---- series content ----

    private val show = series(1, season(1, 3), season(2, 2))

    @Test fun seasonFollowsTheContinueEpisodeUntilTheUserPicksOne() {
        val finishedSeasonOne = listOf(progress(1, 1, 3, 2_900_000, completed = true))
        assertEquals(2, buildDetailsContent(show, finishedSeasonOne, null, null).selectedSeason)
        assertEquals(1, buildDetailsContent(show, finishedSeasonOne, null, 1).selectedSeason)
        // An unknown season falls back to the automatic one.
        assertEquals(2, buildDetailsContent(show, finishedSeasonOne, null, 9).selectedSeason)
    }

    @Test fun episodeRowsCarryProgressAndCompletion() {
        val p = listOf(
            progress(1, 1, 1, 2_900_000, completed = true, updatedAtMs = 1),
            progress(1, 1, 2, 750_000, durationMs = 3_000_000, updatedAtMs = 2),
        )
        val c = buildDetailsContent(show, p, null, 1)
        assertEquals(listOf(1, 2, 3), c.episodes.map { it.number })

        val done = c.episodes[0]
        assertTrue(done.completed)
        assertEquals(0L, done.startPositionMs)
        assertEquals(0f, done.fraction, 0f)

        val partial = c.episodes[1]
        assertFalse(partial.completed)
        assertEquals(750_000L, partial.startPositionMs)
        assertEquals(0.25f, partial.fraction, 0.001f)
        assertEquals(2_250_000L, partial.remainingMs)

        val fresh = c.episodes[2]
        assertEquals(0L, fresh.startPositionMs)
        assertEquals(WatchKey(1, 1, 3), fresh.key)
    }

    @Test fun emptySeasonsAreNotListedAndStreamlessEpisodesAreNotPlayable() {
        val s = series(1, Season(1, listOf(episode(1, 1), episode(1, 2, streams = emptyList()))), Season(2, emptyList()))
        val c = buildDetailsContent(s, emptyList(), null, null)
        assertEquals(listOf(1), c.seasons)
        assertEquals(listOf(true, false), c.episodes.map { it.playable })
    }

    @Test fun seriesChipsCoverEveryEpisode() {
        val s = series(
            1,
            Season(1, listOf(episode(1, 1, listOf(stream(720, AudioType.SUBTITLED)))) ),
            Season(2, listOf(episode(2, 1, listOf(stream(1080, AudioType.DUBBED))))),
        )
        val c = buildDetailsContent(s, emptyList(), null, null).choices
        assertEquals(listOf(AudioType.DUBBED, AudioType.SUBTITLED), c.audioOptions)
    }

    // ---- My List flag + progress sliver ----

    @Test fun theMyListFlagIsCarriedIntoMovieAndSeriesContent() {
        assertNull(buildDetailsContent(movie(1), emptyList(), null, null).inMyList)
        assertEquals(true, buildDetailsContent(movie(1), emptyList(), null, null, inMyList = true).inMyList)
        assertEquals(false, buildDetailsContent(series(1, season(1, 2)), emptyList(), null, null, inMyList = false).inMyList)
    }

    @Test fun startedEpisodesAlwaysShowAVisibleSliver() {
        assertEquals(0f, visibleProgress(0f), 0f)
        assertEquals(0f, visibleProgress(Float.NaN), 0f)
        assertEquals(MIN_VISIBLE_PROGRESS, visibleProgress(0.004f), 0f)
        assertEquals(0.4f, visibleProgress(0.4f), 0f)
        assertEquals(1f, visibleProgress(2f), 0f)
    }
}
