package app.bolbol.tv.feature.playback

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import app.bolbol.tv.core.player.PlayerTuning
import app.bolbol.tv.core.player.SeekKeyRepeater

internal enum class OverlayFocus { SeekBar, PlayPause, Settings }

/** Screen-local UI state of the player chrome (overlay, side panel, debug box, focus). */
@Stable
internal class PlayerChrome {
    var overlayVisible by mutableStateOf(false)
        private set
    var panelOpen by mutableStateOf(false)
        private set
    var debugVisible by mutableStateOf(false)
        private set

    /** Bumped on every key press; restarts the 4 s auto-hide timer. */
    var interactions by mutableIntStateOf(0)
        private set

    /** Bumped whenever focus must move (to [overlayFocus], or to the root when hidden). */
    var focusRequests by mutableIntStateOf(0)
        private set
    var overlayFocus by mutableStateOf(OverlayFocus.SeekBar)
        private set

    val rootFocus = FocusRequester()
    val seekBarFocus = FocusRequester()
    val playPauseFocus = FocusRequester()
    val settingsFocus = FocusRequester()

    // Written from onFocusChanged; read only by key handling (no recomposition needed).
    var rootFocused = false
    var seekBarFocused = false

    fun onInteraction() {
        interactions++
    }

    /** Shows the overlay; focus moves to [focus] only if it was hidden (or [force]). */
    fun showOverlay(focus: OverlayFocus, force: Boolean = false) {
        if (overlayVisible && !force) return
        overlayVisible = true
        overlayFocus = focus
        focusRequests++
    }

    fun hideOverlay() {
        if (!overlayVisible) return
        overlayVisible = false
        focusRequests++
    }

    fun openPanel() {
        panelOpen = true
    }

    /** Closes the side panel and puts focus back on the button that opened it. */
    fun closePanel() {
        panelOpen = false
        overlayVisible = true
        overlayFocus = OverlayFocus.Settings
        focusRequests++
    }

    fun toggleDebug() {
        debugVisible = !debugVisible
    }
}

/**
 * Remote-control handling for the player, installed as the root's onPreviewKeyEvent so it sees
 * every key before the focused child. Dialogs (resume, error) own their keys; BACK goes through
 * BackHandler.
 *
 * The timeline is LTR (docs/decisions.md): DPAD_RIGHT = forward, regardless of the RTL layout.
 */
internal class PlayerKeyHandler(
    private val chrome: PlayerChrome,
    private val viewModel: PlayerViewModel,
) {
    private val repeater = SeekKeyRepeater()
    private var seekKeyActive = false
    private var swallowCenterUp = false
    private val upPressTimes = LongArray(3) { Long.MIN_VALUE / 2 }
    private var upPressIndex = 0

    fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        val down = event.type == KeyEventType.KeyDown
        val up = event.type == KeyEventType.KeyUp
        if (!down && !up) return false
        if (down) chrome.onInteraction()

        val controller = viewModel.controller
        val active = viewModel.screen.value.phase == PlayerPhase.Playing && controller.error.value == null
        if (!active) return false

        val key = event.key
        val native = event.nativeKeyEvent

        // Finish what an earlier key-down started, so the release can't "click" a newly focused child.
        if (up) {
            if (key.isSeekArrow() && seekKeyActive) {
                seekKeyActive = false
                if (repeater.onKeyUp()) controller.setScrubbing(false)
                return true
            }
            if (key.isCenter() && swallowCenterUp) {
                swallowCenterUp = false
                return true
            }
        }

        when (key) {
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                if (down && native.repeatCount == 0) {
                    when (key) {
                        Key.MediaPlay -> controller.play()
                        Key.MediaPause -> controller.pause()
                        else -> controller.togglePlayPause()
                    }
                    chrome.showOverlay(OverlayFocus.SeekBar)
                }
                return true
            }
            Key.MediaFastForward, Key.MediaRewind -> {
                // Holding FF/REW repeats ~20x/s; act on every 4th repeat (~150 s of video per second).
                if (down && native.repeatCount % 4 == 0) {
                    val step = PlayerTuning.FAST_SEEK_STEP_MS
                    controller.seekBy(if (key == Key.MediaFastForward) step else -step)
                    chrome.showOverlay(OverlayFocus.SeekBar)
                }
                return true
            }
            Key.Menu -> {
                if (down && native.repeatCount == 0) {
                    if (chrome.panelOpen) chrome.closePanel() else chrome.openPanel()
                }
                return true
            }
        }

        if (chrome.panelOpen) return false

        if (down && key == Key.DirectionUp && native.repeatCount == 0 && isThirdQuickUpPress(native.eventTime)) {
            chrome.toggleDebug()
        }

        // Seek bar focused, or nothing in the overlay focused yet: arrows seek, centre toggles.
        val seekContext = !chrome.overlayVisible || chrome.seekBarFocused || chrome.rootFocused
        when {
            key.isSeekArrow() && seekContext -> {
                if (down) {
                    seek(key == Key.DirectionRight, native)
                    chrome.showOverlay(OverlayFocus.SeekBar)
                }
                return true
            }
            key.isCenter() && seekContext -> {
                if (down && native.repeatCount == 0) {
                    controller.togglePlayPause()
                    chrome.showOverlay(OverlayFocus.SeekBar)
                    swallowCenterUp = true
                }
                return true
            }
            (key == Key.DirectionUp || key == Key.DirectionDown) && (!chrome.overlayVisible || chrome.rootFocused) -> {
                if (down) chrome.showOverlay(OverlayFocus.PlayPause, force = true)
                return true
            }
        }
        return false
    }

    private fun seek(forward: Boolean, native: android.view.KeyEvent) {
        seekKeyActive = true
        val step = repeater.onKeyDown(native.eventTime, native.downTime, native.repeatCount) ?: return
        val controller = viewModel.controller
        if (repeater.isRepeating) controller.setScrubbing(true)
        controller.seekBy(if (forward) step else -step)
    }

    /** Three DPAD_UP presses within 1.5 s toggle the diagnostics box. */
    private fun isThirdQuickUpPress(timeMs: Long): Boolean {
        upPressTimes[upPressIndex] = timeMs
        upPressIndex = (upPressIndex + 1) % upPressTimes.size
        val oldest = upPressTimes[upPressIndex]
        if (timeMs - oldest <= 1_500) {
            upPressTimes.fill(Long.MIN_VALUE / 2)
            return true
        }
        return false
    }

    private fun Key.isSeekArrow() = this == Key.DirectionLeft || this == Key.DirectionRight

    private fun Key.isCenter() = this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter
}
