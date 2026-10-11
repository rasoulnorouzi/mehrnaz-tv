package app.bolbol.tv.core.player.subtitles

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleSyncTest {
    private val track = SubtitleTrack(
        listOf(
            SubtitleCue(1_000, 2_000, listOf("one")),
            SubtitleCue(3_000, 4_000, listOf("two")),
        ),
    )

    /** A fake playhead that advances with virtual time while "playing". */
    private class Clock(val scope: TestScope) {
        var basePosition = 0L
        var baseTime = 0L
        private var playing = false
        fun position(): Long = if (playing) basePosition + (scope.testScheduler.currentTime - baseTime) else basePosition
        fun seek(to: Long) {
            basePosition = to
            baseTime = scope.testScheduler.currentTime
        }
        fun play(p: Boolean) {
            seek(position())
            playing = p
        }
    }

    @Test fun followsThePlayheadWhilePlayingAndEmitsOnlyOnChange() = runTest {
        val clock = Clock(this)
        val sync = SubtitleSync(backgroundScope, clock::position)
        val emissions = mutableListOf<List<String>>()
        sync.lines.onEach { emissions += it }.launchIn(backgroundScope)
        sync.track = track
        clock.play(true)
        sync.setPlaying(true)
        runCurrent()

        advanceTimeBy(1_050) // t ≈ 1.05 s
        assertEquals(listOf("one"), sync.lines.value)
        advanceTimeBy(1_000) // t ≈ 2.05 s
        assertTrue(sync.lines.value.isEmpty())
        advanceTimeBy(1_000) // t ≈ 3.05 s
        assertEquals(listOf("two"), sync.lines.value)
        runCurrent()
        // [] → one → [] → two: 40 ticks, 4 distinct values.
        assertEquals(listOf(emptyList(), listOf("one"), emptyList(), listOf("two")), emissions)
    }

    @Test fun pausedDoesNotTickButSeekUpdatesImmediately() = runTest {
        val clock = Clock(this)
        val sync = SubtitleSync(backgroundScope, clock::position)
        sync.track = track
        clock.seek(3_500)
        sync.update()
        assertEquals(listOf("two"), sync.lines.value)
        clock.seek(1_500)
        advanceTimeBy(5_000)
        assertEquals(listOf("two"), sync.lines.value) // no ticker while paused
        sync.update() // what onPositionDiscontinuity / seekTo do
        assertEquals(listOf("one"), sync.lines.value)
    }

    @Test fun offsetShiftsTheLookup() = runTest {
        val clock = Clock(this)
        val sync = SubtitleSync(backgroundScope, clock::position)
        sync.track = track
        clock.seek(2_400)
        sync.update()
        assertTrue(sync.lines.value.isEmpty())
        sync.offsetMs = 500 // later: the cue for 1.9 s shows at 2.4 s
        assertEquals(listOf("one"), sync.lines.value)
        sync.offsetMs = -1_000 // earlier: the cue for 3.4 s shows at 2.4 s
        assertEquals(listOf("two"), sync.lines.value)
    }

    @Test fun disabledOrNoTrackShowsNothing() = runTest {
        val clock = Clock(this)
        val sync = SubtitleSync(backgroundScope, clock::position)
        clock.seek(1_500)
        sync.update()
        assertTrue(sync.lines.value.isEmpty())
        sync.track = track
        assertEquals(listOf("one"), sync.lines.value)
        sync.enabled = false
        assertTrue(sync.lines.value.isEmpty())
        sync.enabled = true
        assertEquals(listOf("one"), sync.lines.value)
        sync.track = null
        assertTrue(sync.lines.value.isEmpty())
    }

    @Test fun releaseStopsEverything() = runTest {
        val clock = Clock(this)
        val sync = SubtitleSync(backgroundScope, clock::position)
        sync.track = track
        clock.play(true)
        sync.setPlaying(true)
        sync.release()
        advanceTimeBy(1_500)
        assertTrue(sync.lines.value.isEmpty())
        sync.update()
        assertTrue(sync.lines.value.isEmpty())
    }

    @Test fun offsetNormalizationAndClampAt120Seconds() {
        assertEquals(0, SubtitleOffset.normalize(0))
        assertEquals(1_500, SubtitleOffset.normalize(1_500))
        assertEquals(1_500, SubtitleOffset.normalize(1_400))
        assertEquals(-500, SubtitleOffset.normalize(-260))
        assertEquals(50_000, SubtitleOffset.normalize(50_000)) // the user's example: ±50 s
        assertEquals(-50_000, SubtitleOffset.normalize(-50_000))
        assertEquals(12_500, SubtitleOffset.normalize(12_345))
        assertEquals(120_000, SubtitleOffset.normalize(120_000))
        assertEquals(120_000, SubtitleOffset.normalize(120_250))
        assertEquals(-120_000, SubtitleOffset.normalize(-999_999))
        assertEquals(120_000, SubtitleOffset.normalize(Int.MAX_VALUE))
        assertEquals(-120_000, SubtitleOffset.normalize(Int.MIN_VALUE))
    }

    @Test fun smallAndLargeSteps() {
        assertEquals(500, SubtitleOffset.shift(0, SubtitleOffset.SMALL_STEP_MS))
        assertEquals(-500, SubtitleOffset.shift(0, -SubtitleOffset.SMALL_STEP_MS))
        assertEquals(5_000, SubtitleOffset.shift(0, SubtitleOffset.LARGE_STEP_MS))
        assertEquals(-5_000, SubtitleOffset.shift(0, -SubtitleOffset.LARGE_STEP_MS))
        assertEquals(7_500, SubtitleOffset.shift(2_500, 5_000))
        assertEquals(-2_500, SubtitleOffset.shift(2_500, -5_000))
        // Ten 5 s presses from zero reach +50 s; the limits hold.
        val reached: Int = (1..10).fold(0) { acc, _ -> SubtitleOffset.shift(acc, 5_000) }
        assertEquals(50_000, reached)
        assertEquals(120_000, SubtitleOffset.shift(118_000, 5_000))
        assertEquals(120_000, SubtitleOffset.shift(120_000, 500))
        assertEquals(-120_000, SubtitleOffset.shift(-117_500, -5_000))
        assertEquals(119_500, SubtitleOffset.shift(120_000, -500))
    }

    @Test fun holdToRepeatTiming() {
        assertEquals(0L, HoldRepeat.delayBefore(0)) // the press steps at once
        assertEquals(400L, HoldRepeat.delayBefore(1)) // first repeat
        assertEquals(120L, HoldRepeat.delayBefore(2))
        assertEquals(120L, HoldRepeat.delayBefore(50))
        assertEquals(1, HoldRepeat.stepsAfter(0))
        assertEquals(1, HoldRepeat.stepsAfter(399))
        assertEquals(2, HoldRepeat.stepsAfter(400))
        assertEquals(2, HoldRepeat.stepsAfter(519))
        assertEquals(3, HoldRepeat.stepsAfter(520))
        // +50 s with the 5 s button: 10 steps, held ~1.4 s.
        assertEquals(10, HoldRepeat.stepsAfter(400L + 8 * 120))
        // delayBefore and stepsAfter agree.
        var elapsed = 0L
        for (i in 0 until 30) {
            elapsed += HoldRepeat.delayBefore(i)
            assertEquals(i + 1, HoldRepeat.stepsAfter(elapsed))
        }
        assertEquals(HoldRepeat.MAX_STEPS, HoldRepeat.stepsAfter(10 * 60_000L))
    }
}
