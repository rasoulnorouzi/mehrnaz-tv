package app.bolbol.tv.core.player

import app.bolbol.tv.core.player.StreamSwitchPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSwitchPolicyTest {
    // timeout 8 s, stall 2 s, hard limit 20 s, 1 re-prepare (the PlayerTuning defaults)
    private val policy = StreamSwitchPolicy(firstFrameTimeoutMs = 8_000, stallMs = 2_000, hardLimitMs = 20_000, maxReprepares = 1)

    @Test fun firstFrameInTimeEndsTheSwitch() {
        assertEquals(8_000, policy.begin(nowMs = 1_000))
        assertTrue(policy.isSwitching)
        policy.end()
        assertFalse(policy.isSwitching)
        assertEquals(Action.None, policy.check(nowMs = 9_000, lastByteAtMs = -1))
    }

    @Test fun staleTimerJustWaitsForTheDeadline() {
        policy.begin(nowMs = 0)
        assertEquals(Action.Wait(3_000), policy.check(nowMs = 5_000, lastByteAtMs = -1))
    }

    @Test fun stalledSwitchIsReprepredOnceThenGivesUp() {
        policy.begin(nowMs = 0)
        // 8 s, last byte 6.5 s ago (stalled) → re-prepare, check again in 8 s
        assertEquals(Action.Reprepare(8_000), policy.check(nowMs = 8_000, lastByteAtMs = 1_500))
        assertEquals(1, policy.reprepares)
        assertTrue(policy.isSwitching)
        // still nothing at 16 s → give up (error panel)
        assertEquals(Action.GiveUp, policy.check(nowMs = 16_000, lastByteAtMs = 1_500))
        assertFalse(policy.isSwitching)
    }

    @Test fun noBytesAtAllCountsAsStalled() {
        policy.begin(nowMs = 0)
        assertEquals(Action.Reprepare(8_000), policy.check(nowMs = 8_000, lastByteAtMs = -1))
    }

    @Test fun bytesStillArrivingKeepWaitingInsteadOfKillingASlowSwitch() {
        policy.begin(nowMs = 0)
        assertEquals(Action.Wait(2_000), policy.check(nowMs = 8_000, lastByteAtMs = 7_500))
        assertEquals(Action.Wait(2_000), policy.check(nowMs = 10_000, lastByteAtMs = 9_900))
        assertEquals(0, policy.reprepares)
        // bytes stop: stalled at 12 s → re-prepare
        assertEquals(Action.Reprepare(8_000), policy.check(nowMs = 12_000, lastByteAtMs = 9_900))
    }

    @Test fun hardLimitGivesUpEvenWhileBytesTrickle() {
        policy.begin(nowMs = 0)
        var now = 8_000L
        var action = policy.check(now, lastByteAtMs = now - 100)
        while (action is Action.Wait) {
            now += action.delayMs
            action = policy.check(now, lastByteAtMs = now - 100)
        }
        assertEquals(20_000, now)
        assertEquals(Action.GiveUp, action)
    }

    @Test fun repreparedDeadlineIsCappedByTheHardLimit() {
        policy.begin(nowMs = 0)
        assertEquals(Action.Wait(2_000), policy.check(nowMs = 8_000, lastByteAtMs = 7_000))
        // stalled at 15 s (after a few waits): re-prepare gets only the 5 s left
        assertEquals(Action.Reprepare(5_000), policy.check(nowMs = 15_000, lastByteAtMs = 7_000))
        assertEquals(Action.GiveUp, policy.check(nowMs = 20_000, lastByteAtMs = 7_000))
    }

    @Test fun aNewSwitchResetsTheCounters() {
        policy.begin(nowMs = 0)
        policy.check(nowMs = 8_000, lastByteAtMs = -1)
        assertEquals(1, policy.reprepares)
        policy.begin(nowMs = 9_000)
        assertEquals(0, policy.reprepares)
        assertEquals(Action.Wait(7_000), policy.check(nowMs = 10_000, lastByteAtMs = -1))
        assertEquals(Action.Reprepare(8_000), policy.check(nowMs = 17_000, lastByteAtMs = -1))
    }

    @Test fun logLineShowsTheBreakdownFromTheSwitchStart() {
        val line = SwitchTimeline(
            startedAtMs = 10_000,
            from = "720p/SUBTITLED",
            to = "1080p/SUBTITLED",
            host = "dl20.acenteri.ir",
            requestedPositionMs = 600_000,
            preparedAtMs = 11_830,
            firstFrameAtMs = 12_610,
            readyAtMs = 13_900,
            startPositionMs = 598_400,
            transfer = TransferSnapshot(
                watchedAtMs = 10_000, requests = 2, firstResponseAtMs = 10_412, lastResponseAtMs = 12_200,
                lastRequestAtMs = 11_900, lastByteAtMs = 13_800, bytes = 4_900_000,
            ),
        ).logLine("ok")
        assertEquals(
            "switch 720p/SUBTITLED->1080p/SUBTITLED host=dl20.acenteri.ir result=ok firstByte=412ms moov=1830ms " +
                "rangeByte=2200ms firstFrame=2610ms ready=3900ms snap=-1.6s requests=2 mb=4.9 errors=0 reprepares=0",
            line,
        )
    }

    @Test fun logLineMarksMissingSteps() {
        val line = SwitchTimeline(0, "-", "480p/DUBBED", "h", 0).logLine("gave_up")
        assertEquals(
            "switch -->480p/DUBBED host=h result=gave_up firstByte=- moov=- rangeByte=- firstFrame=- ready=- snap=- " +
                "requests=0 mb=0.0 errors=0 reprepares=0",
            line,
        )
    }
}
