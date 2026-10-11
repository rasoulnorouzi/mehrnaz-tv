package app.bolbol.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekStepsTest {

    @Test fun stepAcceleratesWithHoldTime() {
        assertEquals(10_000, SeekSteps.stepForHold(0))
        assertEquals(10_000, SeekSteps.stepForHold(999))
        assertEquals(30_000, SeekSteps.stepForHold(1_000))
        assertEquals(30_000, SeekSteps.stepForHold(2_999))
        assertEquals(60_000, SeekSteps.stepForHold(3_000))
    }

    @Test fun targetClampsAtZeroAndBeforeTheEnd() {
        assertEquals(0, SeekSteps.target(currentMs = 4_000, deltaMs = -10_000, durationMs = 100_000))
        assertEquals(99_000, SeekSteps.target(currentMs = 95_000, deltaMs = 10_000, durationMs = 100_000))
        assertEquals(60_000, SeekSteps.target(currentMs = 50_000, deltaMs = 10_000, durationMs = 100_000))
        // Unknown duration: no upper clamp.
        assertEquals(60_000, SeekSteps.target(currentMs = 50_000, deltaMs = 10_000, durationMs = 0))
        // Already inside the end guard: a forward press doesn't jump backwards.
        assertEquals(99_500, SeekSteps.target(currentMs = 99_500, deltaMs = 10_000, durationMs = 100_000))
    }

    @Test fun repeaterAcceleratesAndThrottles() {
        val r = SeekKeyRepeater(minRepeatIntervalMs = 120)
        assertEquals(10_000L, r.onKeyDown(eventTimeMs = 1_000, downTimeMs = 1_000, repeatCount = 0))
        assertFalse(r.isRepeating)
        // First auto-repeat after the system delay (~500 ms).
        assertEquals(10_000L, r.onKeyDown(eventTimeMs = 1_500, downTimeMs = 1_000, repeatCount = 1))
        assertTrue(r.isRepeating)
        // 50 ms later: swallowed.
        assertNull(r.onKeyDown(eventTimeMs = 1_550, downTimeMs = 1_000, repeatCount = 2))
        // Held 1.2 s: 30 s steps.
        assertEquals(30_000L, r.onKeyDown(eventTimeMs = 2_200, downTimeMs = 1_000, repeatCount = 14))
        // Held 3.5 s: 60 s steps.
        assertEquals(60_000L, r.onKeyDown(eventTimeMs = 4_500, downTimeMs = 1_000, repeatCount = 60))
        assertTrue(r.onKeyUp())
        assertFalse(r.isRepeating)
        // A fresh press starts over at 10 s.
        assertEquals(10_000L, r.onKeyDown(eventTimeMs = 9_000, downTimeMs = 9_000, repeatCount = 0))
        assertFalse(r.onKeyUp())
    }
}
