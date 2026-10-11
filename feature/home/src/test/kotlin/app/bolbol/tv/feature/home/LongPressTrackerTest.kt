package app.bolbol.tv.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressTrackerTest {

    private val tracker = LongPressTracker()

    private fun select(down: Boolean, repeat: Int = 0) = tracker.onKey(RemoteKey.SELECT, down, repeat)

    @Test fun aShortPressPassesThroughSoTheCardStillClicks() {
        assertEquals(KeyOutcome.Pass, select(down = true))
        assertEquals(KeyOutcome.Pass, select(down = false))
    }

    @Test fun holdingOkFiresOnceOnTheFirstRepeatAndSwallowsTheRest() {
        assertEquals(KeyOutcome.Pass, select(down = true, repeat = 0))

        val first = select(down = true, repeat = 1)
        assertTrue(first.consumed)
        assertEquals(ActionTrigger.LONG_PRESS, first.trigger)

        for (repeat in 2..6) assertEquals(KeyOutcome.Swallow, select(down = true, repeat = repeat))
        // The release must not click the card.
        assertEquals(KeyOutcome.Swallow, select(down = false))
    }

    @Test fun afterALongPressTheNextShortPressClicksAgain() {
        select(down = true, repeat = 0)
        select(down = true, repeat = 1)
        select(down = false)

        assertEquals(KeyOutcome.Pass, select(down = true, repeat = 0))
        assertEquals(KeyOutcome.Pass, select(down = false))
    }

    @Test fun aSecondHoldFiresAgain() {
        repeat(2) {
            select(down = true, repeat = 0)
            assertEquals(ActionTrigger.LONG_PRESS, select(down = true, repeat = 1).trigger)
            select(down = false)
        }
    }

    @Test fun menuFiresOnItsDownAndIgnoresRepeats() {
        val down = tracker.onKey(RemoteKey.MENU, down = true, repeatCount = 0)
        assertTrue(down.consumed)
        assertEquals(ActionTrigger.MENU, down.trigger)

        val repeat = tracker.onKey(RemoteKey.MENU, down = true, repeatCount = 3)
        assertTrue(repeat.consumed)
        assertNull(repeat.trigger)
        assertNull(tracker.onKey(RemoteKey.MENU, down = false, repeatCount = 0).trigger)
    }

    @Test fun otherKeysAreNeverTouched() {
        assertFalse(tracker.onKey(RemoteKey.OTHER, down = true, repeatCount = 0).consumed)
        assertFalse(tracker.onKey(RemoteKey.OTHER, down = true, repeatCount = 5).consumed)
    }
}
