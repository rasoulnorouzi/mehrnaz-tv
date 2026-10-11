package app.bolbol.tv.feature.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBackTest {
    private fun action(
        panelOpen: Boolean = false,
        overlayVisible: Boolean = false,
        resumeDialog: Boolean = false,
        errorShown: Boolean = false,
        nextCardVisible: Boolean = false,
    ) = backAction(panelOpen, overlayVisible, resumeDialog, errorShown, nextCardVisible)

    @Test fun nothingShowingExits() {
        assertEquals(BackAction.Exit, action())
    }

    @Test fun overlayIsHiddenBeforeExiting() {
        assertEquals(BackAction.HideOverlay, action(overlayVisible = true))
    }

    @Test fun panelClosesFirst() {
        assertEquals(BackAction.ClosePanel, action(panelOpen = true))
        // The controls may still be flagged visible under the panel: the panel still goes first.
        assertEquals(BackAction.ClosePanel, action(panelOpen = true, overlayVisible = true))
    }

    @Test fun fullSequenceIsPanelThenOverlayThenExit() {
        var panel = true
        var overlay = true
        val seen = mutableListOf<BackAction>()
        while (true) {
            val next = action(panelOpen = panel, overlayVisible = overlay)
            seen += next
            when (next) {
                BackAction.ClosePanel -> panel = false // closePanel() leaves the controls up
                BackAction.HideOverlay -> overlay = false
                BackAction.DismissNextCard -> error("no card in this scenario")
                BackAction.Exit -> break
            }
        }
        assertEquals(listOf(BackAction.ClosePanel, BackAction.HideOverlay, BackAction.Exit), seen)
    }

    @Test fun nextEpisodeCardIsDismissedAfterTheControls() {
        assertEquals(BackAction.DismissNextCard, action(nextCardVisible = true))
        assertEquals(BackAction.HideOverlay, action(overlayVisible = true, nextCardVisible = true))
        assertEquals(BackAction.ClosePanel, action(panelOpen = true, nextCardVisible = true))
    }

    @Test fun resumeDialogExitsAtOnce() {
        assertEquals(BackAction.Exit, action(resumeDialog = true))
        // Stale chrome flags behind the dialog must not cost extra presses.
        assertEquals(BackAction.Exit, action(resumeDialog = true, panelOpen = true, overlayVisible = true))
    }

    @Test fun errorPanelExitsAtOnce() {
        assertEquals(BackAction.Exit, action(errorShown = true))
        assertEquals(BackAction.Exit, action(errorShown = true, panelOpen = true, overlayVisible = true, nextCardVisible = true))
    }

    @Test fun pressFiresOnceOnKeyUp() {
        val tracker = BackPressTracker()
        tracker.onKeyDown()
        assertTrue(tracker.onKeyUp())
        assertFalse("a second release without a press does nothing", tracker.onKeyUp())
    }

    @Test fun heldKeyFiresOnce() {
        val tracker = BackPressTracker()
        repeat(10) { tracker.onKeyDown() } // auto-repeat key-downs
        assertTrue(tracker.onKeyUp())
        assertFalse(tracker.onKeyUp())
    }

    @Test fun releaseWithoutOurKeyDownIsNotOurs() {
        val tracker = BackPressTracker()
        assertFalse(tracker.onKeyUp())
    }

    @Test fun eachPressIsIndependent() {
        val tracker = BackPressTracker()
        repeat(3) {
            tracker.onKeyDown()
            assertTrue(tracker.onKeyUp())
        }
    }
}
