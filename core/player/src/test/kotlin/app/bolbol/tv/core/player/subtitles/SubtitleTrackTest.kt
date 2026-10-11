package app.bolbol.tv.core.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTrackTest {
    private val a = SubtitleCue(1_000, 2_000, listOf("a"))
    private val b = SubtitleCue(3_000, 4_000, listOf("b1", "b2"))
    private val c = SubtitleCue(3_500, 5_000, listOf("c")) // overlaps b
    private val track = SubtitleTrack(listOf(c, a, b)) // unsorted on purpose

    @Test fun sortsCues() {
        assertEquals(listOf(a, b, c), track.cues)
    }

    @Test fun startInclusiveEndExclusive() {
        assertTrue(track.linesAt(999).isEmpty())
        assertEquals(listOf("a"), track.linesAt(1_000))
        assertEquals(listOf("a"), track.linesAt(1_999))
        assertTrue(track.linesAt(2_000).isEmpty())
    }

    @Test fun gapsAndEdges() {
        assertTrue(track.linesAt(-500).isEmpty())
        assertTrue(track.linesAt(2_500).isEmpty())
        assertTrue(track.linesAt(10_000).isEmpty())
        assertTrue(SubtitleTrack.EMPTY.linesAt(0).isEmpty())
    }

    @Test fun singleActiveCueReturnsItsOwnList() {
        assertSame(b.lines, track.linesAt(3_200))
    }

    @Test fun overlappingCuesStackInStartOrder() {
        assertEquals(listOf("b1", "b2", "c"), track.linesAt(3_600))
        assertEquals(listOf("c"), track.linesAt(4_500))
    }

    @Test fun longCueSpanningShortOnesIsFound() {
        val long = SubtitleCue(0, 10_000, listOf("sign"))
        val short = SubtitleCue(5_000, 6_000, listOf("dialogue"))
        val t = SubtitleTrack(listOf(long, short, SubtitleCue(7_000, 8_000, listOf("more"))))
        assertEquals(listOf("sign", "dialogue"), t.linesAt(5_500))
        assertEquals(listOf("sign", "more"), t.linesAt(7_500))
        assertEquals(listOf("sign"), t.linesAt(9_000))
    }
}
