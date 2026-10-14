package app.bolbol.tv.feature.playback

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.OfflineMedia
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PlaybackRequest
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.player.audioTypes
import app.bolbol.tv.core.player.nextLowerQuality
import app.bolbol.tv.core.player.qualitiesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourceTest {
    private val key = WatchKey(9001, 1, 2)
    private val title = TitleSummary(9001, TitleType.SERIES, "Show", "سریال", null, null, 2024, null, hasDub = true, hasSubtitle = true)
    private val s1080 = StreamOption(1080, AudioType.SUBTITLED, "https://a/1080.mp4", "https://a/s.vtt", "1080p", null)
    private val s720 = StreamOption(720, AudioType.SUBTITLED, "https://b/720.mp4", "https://a/s.vtt", "720p", null)
    private val d720 = StreamOption(720, AudioType.DUBBED, "https://b/d720.mp4", null, "720p", null)
    private val request = PlaybackRequest(key, title, "فصل ۱ · قسمت ۲", listOf(s1080, s720, d720), null, next = null)
    private val saved = OfflineMedia(
        key = key,
        stream = s720.copy(url = "file:///data/downloads/9001_1_2/video.mp4", subtitleUrl = "file:///data/downloads/9001_1_2/subtitle.vtt"),
        subtitleUri = "file:///data/downloads/9001_1_2/subtitle.vtt",
    )

    @Test fun onlineUsesTheRequestAndThePreferredStream() {
        val source = PlaybackSource.online(request, PlaybackPrefs(audio = AudioType.DUBBED, quality = 720))
        assertFalse(source.offline)
        assertEquals(d720, source.stream)
        assertSame(request, source.requestFor(request))
    }

    @Test fun onlineWithoutStreamsHasNothingToPlay() {
        assertNull(PlaybackSource.online(request.copy(streams = emptyList()), PlaybackPrefs()).stream)
    }

    @Test fun savedCopyIsTheOnlyChoice() {
        val source = PlaybackSource.saved(request, saved)!!
        assertTrue(source.offline)
        assertEquals(saved.stream, source.stream)
        assertEquals("file:///data/downloads/9001_1_2/subtitle.vtt", source.stream!!.subtitleUrl)
        // Nothing to switch to: one audio, one quality, no lower-quality fallback, no CDN host to pre-warm.
        assertEquals(listOf(AudioType.SUBTITLED), source.streams.audioTypes())
        assertEquals(listOf(720), source.streams.qualitiesFor(AudioType.SUBTITLED))
        assertNull(source.streams.nextLowerQuality(source.stream!!))
        val played = source.requestFor(request)
        assertEquals(listOf(saved.stream), played.streams)
        assertEquals(request.copy(streams = played.streams), played)
    }

    @Test fun savedCopyStillPlaysWhenTheRequestHasNoStreams() {
        // e.g. opened from «دانلودها» with no internet: the request may carry only the remote stream, or none.
        val source = PlaybackSource.saved(request.copy(streams = emptyList()), saved)!!
        assertEquals(saved.stream, source.stream)
    }

    @Test fun savedCopyOfAnotherEpisodeIsIgnored() {
        assertNull(PlaybackSource.saved(request, saved.copy(key = WatchKey(9001, 1, 3))))
    }
}
