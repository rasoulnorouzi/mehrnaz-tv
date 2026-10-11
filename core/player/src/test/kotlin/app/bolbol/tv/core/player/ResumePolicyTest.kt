package app.bolbol.tv.core.player

import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.WatchKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePolicyTest {
    private val key = WatchKey(353371)
    private val hour = 3_600_000L
    private fun saved(pos: Long, dur: Long = hour, completed: Boolean = false) =
        PlaybackProgress(key, pos, dur, updatedAtMs = 0, completed = completed)

    @Test fun nothingSavedStartsFromBeginning() {
        assertEquals(ResumeDecision.StartFromBeginning, ResumePolicy.decide(null, null))
    }

    @Test fun midwayAsksUser() {
        assertEquals(ResumeDecision.AskUser(2_832_000), ResumePolicy.decide(saved(2_832_000), null))
    }

    @Test fun exactlyThirtySecondsAsks() {
        assertEquals(ResumeDecision.AskUser(30_000), ResumePolicy.decide(saved(30_000), null))
    }

    @Test fun underThirtySecondsStartsOver() {
        assertEquals(ResumeDecision.StartFromBeginning, ResumePolicy.decide(saved(29_999), null))
    }

    @Test fun lessThanAMinuteLeftStartsOver() {
        assertEquals(ResumeDecision.StartFromBeginning, ResumePolicy.decide(saved(hour - 59_999), null))
        assertEquals(ResumeDecision.AskUser(hour - 60_000), ResumePolicy.decide(saved(hour - 60_000), null))
    }

    @Test fun completedStartsOver() {
        assertEquals(ResumeDecision.StartFromBeginning, ResumePolicy.decide(saved(1_000_000, completed = true), null))
    }

    @Test fun unknownDurationStartsOver() {
        assertEquals(ResumeDecision.StartFromBeginning, ResumePolicy.decide(saved(1_000_000, dur = 0), null))
    }

    @Test fun explicitStartWinsOverSavedProgress() {
        assertEquals(ResumeDecision.ResumeAt(120_000), ResumePolicy.decide(saved(2_832_000), 120_000))
        assertEquals(ResumeDecision.ResumeAt(120_000), ResumePolicy.decide(saved(1_000, completed = true), 120_000))
    }

    @Test fun explicitZeroStartsFromBeginningWithoutAsking() {
        assertEquals(ResumeDecision.StartFromBeginning, ResumePolicy.decide(saved(2_832_000), 0))
    }
}
