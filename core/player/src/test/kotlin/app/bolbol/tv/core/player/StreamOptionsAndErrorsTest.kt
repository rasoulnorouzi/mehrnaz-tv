package app.bolbol.tv.core.player

import androidx.media3.common.PlaybackException
import app.bolbol.tv.core.model.AudioType.DUBBED
import app.bolbol.tv.core.model.AudioType.SUBTITLED
import app.bolbol.tv.core.model.StreamOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamOptionsAndErrorsTest {
    private fun s(q: Int, a: app.bolbol.tv.core.model.AudioType) = StreamOption(q, a, "u$q$a", null, "$q", null)
    private val all = listOf(
        s(1080, SUBTITLED), s(720, SUBTITLED), s(480, SUBTITLED),
        s(1080, DUBBED), s(480, DUBBED),
    )

    @Test fun audioAndQualities() {
        assertEquals(listOf(SUBTITLED, DUBBED), all.audioTypes())
        assertEquals(listOf(SUBTITLED), listOf(s(720, SUBTITLED)).audioTypes())
        assertEquals(listOf(1080, 480), all.qualitiesFor(DUBBED))
        assertEquals(s(720, SUBTITLED), all.find(SUBTITLED, 720))
        assertNull(all.find(DUBBED, 720))
    }

    @Test fun audioSwitchKeepsQualityOrGoesLower() {
        assertEquals(s(1080, DUBBED), all.forAudio(DUBBED, 1080))
        assertEquals(s(480, DUBBED), all.forAudio(DUBBED, 720))
        assertEquals(s(480, DUBBED), all.forAudio(DUBBED, 360))
    }

    @Test fun nextLowerQualityPrefersSameAudio() {
        assertEquals(s(720, SUBTITLED), all.nextLowerQuality(s(1080, SUBTITLED)))
        assertEquals(s(480, DUBBED), all.nextLowerQuality(s(1080, DUBBED)))
        assertNull(all.nextLowerQuality(s(480, SUBTITLED)))
        // No lower dub at 720 → any audio.
        assertEquals(s(480, SUBTITLED), listOf(s(720, DUBBED), s(480, SUBTITLED)).nextLowerQuality(s(720, DUBBED)))
    }

    @Test fun errorClassification() {
        assertEquals(PlaybackError.Kind.SOURCE_4XX, PlaybackError.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertEquals(PlaybackError.Kind.SOURCE_4XX, PlaybackError.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
        assertEquals(PlaybackError.Kind.SOURCE_5XX, PlaybackError.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 503))
        assertEquals(PlaybackError.Kind.NETWORK, PlaybackError.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertEquals(PlaybackError.Kind.NETWORK, PlaybackError.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
        assertEquals(PlaybackError.Kind.NETWORK, PlaybackError.classify(PlaybackException.ERROR_CODE_TIMEOUT, null))
        assertEquals(PlaybackError.Kind.SOURCE_FORMAT, PlaybackError.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null))
        assertEquals(PlaybackError.Kind.DECODER, PlaybackError.classify(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null))
        assertEquals(PlaybackError.Kind.DECODER, PlaybackError.classify(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, null))
        assertEquals(PlaybackError.Kind.OTHER, PlaybackError.classify(PlaybackException.ERROR_CODE_UNSPECIFIED, null))
    }
}
