package app.bolbol.tv.core.player

import app.bolbol.tv.core.model.WatchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSavePolicyTest {
    private val key = WatchKey(624236, 1, 2)
    private val dur = 1_000_000L
    private val policy = ProgressSavePolicy(intervalMs = 10_000, minPositionMs = 5_000, completedFraction = 0.95)

    private fun eval(pos: Long, now: Long, reason: SaveReason = SaveReason.TICK, ended: Boolean = false, duration: Long = dur) =
        policy.evaluate(key, pos, duration, ended, reason, nowElapsedMs = now, nowWallMs = 1_700_000_000_000 + now)

    @Test fun periodicSavesAreThrottledToTheInterval() {
        policy.reset(nowElapsedMs = 0, baselinePositionMs = 0)
        assertNull(eval(pos = 9_000, now = 9_000))
        val first = eval(pos = 10_000, now = 10_000)
        assertNotNull(first)
        assertEquals(10_000, first!!.positionMs)
        assertEquals(1_700_000_010_000, first.updatedAtMs)
        assertNull(eval(pos = 19_999, now = 19_999))
        assertNotNull(eval(pos = 20_000, now = 20_000))
    }

    @Test fun forcedSavesBypassTheThrottle() {
        policy.reset(nowElapsedMs = 0, baselinePositionMs = 0)
        assertNotNull(eval(pos = 6_000, now = 1_000, reason = SaveReason.PAUSE))
        assertNotNull(eval(pos = 7_000, now = 1_500, reason = SaveReason.RELEASE))
    }

    @Test fun neverSavesBelowFiveSeconds() {
        policy.reset(nowElapsedMs = 0, baselinePositionMs = 0)
        assertNull(eval(pos = 4_999, now = 60_000, reason = SaveReason.PAUSE))
        assertNotNull(eval(pos = 5_000, now = 60_000, reason = SaveReason.PAUSE))
    }

    @Test fun neverSavesBeforeDurationIsKnown() {
        policy.reset(nowElapsedMs = 0, baselinePositionMs = 0)
        assertNull(eval(pos = 50_000, now = 60_000, reason = SaveReason.STOP, duration = 0))
        // C.TIME_UNSET is Long.MIN_VALUE + 1.
        assertNull(eval(pos = 50_000, now = 60_000, reason = SaveReason.STOP, duration = Long.MIN_VALUE + 1))
    }

    @Test fun identicalSaveIsSkipped() {
        policy.reset(nowElapsedMs = 0, baselinePositionMs = 0)
        assertNotNull(eval(pos = 50_000, now = 1_000, reason = SaveReason.PAUSE))
        assertNull(eval(pos = 50_000, now = 2_000, reason = SaveReason.STOP))
        assertNull(eval(pos = 50_000, now = 3_000, reason = SaveReason.RELEASE))
    }

    @Test fun leavingAtTheResumePositionWithoutPlayingWritesNothing() {
        policy.reset(nowElapsedMs = 0, baselinePositionMs = 2_832_000)
        assertNull(eval(pos = 2_832_000, now = 5_000, reason = SaveReason.RELEASE, duration = 5_400_000))
    }

    @Test fun completedAtNinetyFivePercent() {
        policy.reset(nowElapsedMs = 0, baselinePositionMs = 0)
        assertFalse(eval(pos = 949_999, now = 1, reason = SaveReason.PAUSE)!!.completed)
        assertTrue(eval(pos = 950_000, now = 2, reason = SaveReason.PAUSE)!!.completed)
    }

    @Test fun endedIsCompletedAtFullDuration() {
        policy.reset(nowElapsedMs = 0, baselinePositionMs = 0)
        val p = eval(pos = 123_000, now = 1, reason = SaveReason.ENDED, ended = true)!!
        assertTrue(p.completed)
        assertEquals(dur, p.positionMs)
        assertEquals(key, p.key)
    }

    @Test fun positionIsClampedToDuration() {
        val p = ProgressSavePolicy.buildProgress(key, positionMs = dur + 5_000, durationMs = dur, ended = false, nowWallMs = 7)!!
        assertEquals(dur, p.positionMs)
        assertTrue(p.completed)
    }

    @Test fun resetRestartsTheThrottleWindow() {
        policy.reset(nowElapsedMs = 0, baselinePositionMs = 0)
        assertNotNull(eval(pos = 10_000, now = 10_000))
        policy.reset(nowElapsedMs = 12_000, baselinePositionMs = 10_000)
        assertNull(eval(pos = 15_000, now = 21_999))
        assertNotNull(eval(pos = 16_000, now = 22_000))
    }
}
