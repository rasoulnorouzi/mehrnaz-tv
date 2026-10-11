package app.bolbol.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelTest {
    private fun s(q: Int, a: AudioType) = StreamOption(q, a, "u$q$a", null, "$q", null)
    private val streams = listOf(
        s(1080, AudioType.SUBTITLED), s(720, AudioType.SUBTITLED), s(480, AudioType.SUBTITLED),
        s(1080, AudioType.DUBBED), s(480, AudioType.DUBBED),
    )

    @Test fun watchKeyRoundTrip() {
        val k = WatchKey(624236, 1, 2)
        assertEquals(k, WatchKey.parse(k.asString()))
        assertNull(WatchKey.parse("bad"))
    }

    @Test fun pickPrefersAudioThenClosestLowerQuality() {
        assertEquals(s(480, AudioType.DUBBED), streams.pick(PlaybackPrefs(AudioType.DUBBED, 720)))
        assertEquals(s(720, AudioType.SUBTITLED), streams.pick(PlaybackPrefs(AudioType.SUBTITLED, 720)))
        assertEquals(s(1080, AudioType.SUBTITLED), streams.pick(PlaybackPrefs(AudioType.SUBTITLED, 1080)))
    }

    @Test fun pickFallsBackToLowestWhenNothingBelowTarget() {
        assertEquals(s(480, AudioType.SUBTITLED), streams.pick(PlaybackPrefs(AudioType.SUBTITLED, 360)))
    }

    @Test fun pickEmptyIsNull() {
        assertNull(emptyList<StreamOption>().pick(null))
    }
}
