package app.bolbol.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StutterGuardTest {

    /** Ticks once a second for [seconds], dropping [dropPerSecond] frames each second; returns the reload count. */
    private fun StutterGuard.run(startMs: Long, seconds: Int, dropPerSecond: Int, dropped: LongArray, fps: Float = 24f): Int {
        var reloads = 0
        for (s in 0..seconds) {
            if (onTick(startMs + s * 1000L, isPlaying = true, droppedTotal = dropped[0], frameRate = fps)) reloads++
            dropped[0] += dropPerSecond
        }
        return reloads
    }

    @Test fun smoothPlaybackNeverReloads() {
        val dropped = longArrayOf(0)
        assertEquals(0, StutterGuard().run(0, 120, dropPerSecond = 1, dropped = dropped))
    }

    @Test fun choppyPlaybackReloadsAfterTwoBadWindows() {
        val guard = StutterGuard()
        val dropped = longArrayOf(0)
        var firstReloadAt = -1
        for (s in 0..20) {
            if (guard.onTick(s * 1000L, true, dropped[0], 24f) && firstReloadAt < 0) firstReloadAt = s
            dropped[0] += 12 // half the frames dropped
        }
        assertEquals("two 4 s windows after the first tick", 8, firstReloadAt)
    }

    @Test fun reloadsAreRateLimitedAndCapped() {
        val dropped = longArrayOf(0)
        val guard = StutterGuard()
        assertEquals("once per minute", 1, guard.run(0, 50, dropPerSecond = 20, dropped = dropped))
        assertEquals("max 3 per stream", 2, guard.run(60_000, 600, dropPerSecond = 20, dropped = dropped))
        guard.reset()
        assertEquals("a new stream gets a new budget", 1, guard.run(700_000, 20, dropPerSecond = 20, dropped = dropped))
    }

    @Test fun pausesDoNotCountAsStutter() {
        val guard = StutterGuard()
        var dropped = 0L
        for (s in 0..30) {
            val playing = s % 3 != 0 // paused every third second
            assertFalse(guard.onTick(s * 1000L, playing, dropped, 24f))
            dropped += 20
        }
    }

    @Test fun unknownFrameRateUsesASafeDefault() {
        val dropped = longArrayOf(0)
        assertTrue(StutterGuard().run(0, 20, dropPerSecond = 15, dropped = dropped, fps = 0f) >= 1)
    }
}
