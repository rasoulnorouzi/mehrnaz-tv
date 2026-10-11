package app.bolbol.tv.core.player

import app.bolbol.tv.core.player.AutoNextPolicy.COUNTDOWN_MS
import app.bolbol.tv.core.player.AutoNextState.Counting
import app.bolbol.tv.core.player.AutoNextState.Dismissed
import app.bolbol.tv.core.player.AutoNextState.DismissedAtEnd
import app.bolbol.tv.core.player.AutoNextState.PlayNext
import app.bolbol.tv.core.player.AutoNextState.Waiting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoNextPolicyTest {
    private val duration = 22 * 60_000L // a 22-minute episode

    private fun at(
        positionMs: Long,
        ended: Boolean = false,
        playing: Boolean = !ended,
        held: Boolean = false,
        durationMs: Long = duration,
    ) = AutoNextInput(positionMs, durationMs, ended, playing, held)

    private fun AutoNextState.step(input: AutoNextInput, elapsedMs: Long = 200) = AutoNextPolicy.step(this, input, elapsedMs)

    @Test fun hiddenUntilTheLast30Seconds() {
        assertEquals(Waiting, Waiting.step(at(duration - 31_000)))
        assertEquals(Counting(COUNTDOWN_MS, startedAtEnd = false), Waiting.step(at(duration - 30_000)))
    }

    @Test fun unknownDurationNeverOpensTheWindow() {
        assertEquals(Waiting, Waiting.step(at(5_000, durationMs = 0)))
    }

    @Test fun countsDownWhilePlayingThenPlaysNext() {
        var s: AutoNextState = Counting(COUNTDOWN_MS, false)
        var pos = duration - 30_000
        repeat(49) {
            pos += 200
            s = s.step(at(pos))
        }
        assertEquals(Counting(200, false), s)
        assertEquals(PlayNext, s.step(at(pos + 200)))
    }

    @Test fun pausedBufferingOrHeldFreezesTheCountdown() {
        val s = Counting(6_000, false)
        assertEquals(s, s.step(at(duration - 20_000, playing = false), elapsedMs = 5_000))
        assertEquals(s, s.step(at(duration - 20_000, held = true), elapsedMs = 5_000))
    }

    @Test fun endingWhileCountingPlaysNextAtOnce() {
        assertEquals(PlayNext, Counting(7_000, false).step(at(duration, ended = true)))
    }

    @Test fun heldAtTheEndWaitsForTheChromeToClose() {
        val s = Counting(7_000, false)
        assertEquals(s, s.step(at(duration, ended = true, held = true)))
        assertEquals(PlayNext, s.step(at(duration, ended = true, held = false)))
    }

    @Test fun endWithoutTheCardShowsItThenCountsDown() {
        // The user fast-forwarded past the window straight to the end.
        var s: AutoNextState = Waiting.step(at(duration, ended = true))
        assertEquals(Counting(COUNTDOWN_MS, startedAtEnd = true), s)
        s = s.step(at(duration, ended = true), elapsedMs = 4_000)
        assertEquals(Counting(6_000, startedAtEnd = true), s)
        assertEquals(PlayNext, s.step(at(duration, ended = true), elapsedMs = 6_000))
    }

    @Test fun shortVideosOnlyGetTheCardAtTheEnd() {
        val short = 45_000L
        assertEquals(Waiting, Waiting.step(at(20_000, durationMs = short)))
        assertEquals(Counting(COUNTDOWN_MS, true), Waiting.step(at(short, ended = true, durationMs = short)))
    }

    @Test fun backDismissesUntilTheEndThenComesBackOnce() {
        var s: AutoNextState = AutoNextPolicy.dismiss(Counting(8_000, false), ended = false)
        assertEquals(Dismissed, s)
        s = s.step(at(duration - 10_000))
        assertEquals(Dismissed, s) // keeps watching the credits, no card
        s = s.step(at(duration, ended = true))
        assertEquals(Counting(COUNTDOWN_MS, startedAtEnd = true), s)
        s = AutoNextPolicy.dismiss(s, ended = true)
        assertEquals(DismissedAtEnd, s)
        assertEquals(DismissedAtEnd, s.step(at(duration, ended = true), elapsedMs = 60_000))
    }

    @Test fun seekingBackOutOfTheWindowRearms() {
        assertEquals(Waiting, Counting(4_000, false).step(at(duration - 60_000)))
        assertEquals(Waiting, Dismissed.step(at(duration - 60_000)))
        assertEquals(Waiting, DismissedAtEnd.step(at(10_000))) // restarted from the ended screen
        assertEquals(Dismissed, DismissedAtEnd.step(at(duration - 5_000)))
    }

    @Test fun restartAfterTheEndCardHidesIt() {
        assertEquals(Waiting, Counting(5_000, startedAtEnd = true).step(at(0)))
    }

    @Test fun playNextIsTerminal() {
        assertEquals(PlayNext, PlayNext.step(at(0)))
        assertEquals(PlayNext, AutoNextPolicy.dismiss(PlayNext, ended = true))
    }

    @Test fun dismissOnlyAffectsAVisibleCard() {
        assertEquals(Waiting, AutoNextPolicy.dismiss(Waiting, ended = false))
        assertTrue(AutoNextPolicy.cardVisible(Counting(1, false)))
        assertFalse(AutoNextPolicy.cardVisible(Dismissed))
    }
}
