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

internal enum class OverlayFocus { SeekBar, PlayPause, Episodes, Settings }

/** The side panels at the end edge (one at a time, same width [PANEL_WIDTH]). */
internal enum class PlayerPanel(
    /** The controls button that opened the panel: focus returns there when it closes. */
    val opener: OverlayFocus,
) {
    /** «تنظیمات پخش»: audio, quality, subtitles. */
    Settings(OverlayFocus.Settings),

    /** «قسمت‌ها»: seasons + episodes of a series (v1.1-4). */
    Episodes(OverlayFocus.Episodes),
}

/** Screen-local UI state of the player chrome (overlay, side panel, debug box, focus). */
@Stable
internal class PlayerChrome {
    var overlayVisible by mutableStateOf(false)
        private set
    /** The open side panel, or null. */
    var panel by mutableStateOf<PlayerPanel?>(null)
        private set
    val panelOpen: Boolean get() = panel != null
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
    val episodesFocus = FocusRequester()

    // Written from onFocusChanged; read only by key handling (no recomposition needed).
    var rootFocused = false
    var seekBarFocused = false

    /** The "قسمت بعد" card is on screen (written from composition; read by key handling). */
    var autoNextVisible = false

    /** Small in-player message ("زیرنویس: +۱٫۵ ثانیه"); [toastId] restarts its timer. */
    var toast by mutableStateOf<String?>(null)
        private set
    var toastId by mutableIntStateOf(0)
        private set

    fun onInteraction() {
        interactions++
    }

    fun showToast(text: String) {
        toast = text
        toastId++
    }

    fun clearToast() {
        toast = null
    }

    /** Re-applies the focus rule (root when the overlay is hidden), e.g. after the card closes. */
    fun refocus() {
        focusRequests++
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

    fun openPanel(which: PlayerPanel = PlayerPanel.Settings) {
        panel = which
    }

    /** Closes the side panel and puts focus back on the button that opened it. */
    fun closePanel() {
        val closing = panel ?: return
        panel = null
        overlayVisible = true
        overlayFocus = closing.opener
        focusRequests++
    }

    fun toggleDebug() {
        debugVisible = !debugVisible
    }
}

/**
 * Remote-control handling for the player, installed as the root's onPreviewKeyEvent so it sees
 * every key before the focused child. Dialogs (resume, error) own their other keys.
 *
 * BACK is handled here, first, for every phase and focus position. Why: Compose turns a BACK key-down
 * that nobody consumed into "move focus to the parent focus target" (FocusDirection.Exit) and consumes
 * it. The root `focusable()` is the parent of every button in the panel, the controls and the dialogs,
 * so with focus on one of them the first BACK only moved focus to the root and never reached
 * `BackHandler`. We take both halves of the press ourselves (action on key-up, see [BackPressTracker])
 * and run [onBack]; the screen's `BackHandler` calls the same [onBack] for a BACK that arrives without
 * a key event.
 *
 * The timeline is LTR (docs/decisions.md): DPAD_RIGHT = forward, regardless of the RTL layout.
 * MEDIA_NEXT / MEDIA_PREVIOUS switch episodes (series); DOWN on the controls' button row opens «قسمت‌ها».
 */
internal class PlayerKeyHandler(
    private val chrome: PlayerChrome,
    private val viewModel: PlayerViewModel,
    private val onBack: () -> Unit,
) {
    private val backPress = BackPressTracker()
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

        if (event.key == Key.Back) {
            if (down) {
                backPress.onKeyDown() // also swallows the auto-repeats of a held key
                return true
            }
            if (!backPress.onKeyUp()) return false // not our press: the system path handles it
            onBack()
            return true
        }

        // v1.1-1: the remote's ⏮ / ⏭ switch episodes (series only; ignored for movies and at the ends). Also on the
        // resume prompt and the error panel, so an episode that won't play can be skipped.
        if (event.key == Key.MediaNext || event.key == Key.MediaPrevious) {
            if (down && event.nativeKeyEvent.repeatCount == 0) {
                if (event.key == Key.MediaNext) viewModel.playNextEpisode() else viewModel.playPreviousEpisode()
            }
            return true
        }

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
                    if (chrome.panelOpen) chrome.closePanel() else chrome.openPanel(PlayerPanel.Settings)
                }
                return true
            }
        }

        if (chrome.panelOpen) return false

        // Next-episode card: OK plays the next episode now (BACK dismisses it, see backAction).
        if (chrome.autoNextVisible && !chrome.overlayVisible && key.isCenter()) {
            if (down && native.repeatCount == 0) {
                viewModel.playNextNow()
                swallowCenterUp = true
            }
            return true
        }

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
            // Shortcut (v1.1-4): DOWN on the controls' button row (nothing lies below it) opens «قسمت‌ها»,
            // so DOWN, DOWN from the bare video opens the episodes panel.
            key == Key.DirectionDown && chrome.overlayVisible && !chrome.seekBarFocused && viewModel.hasEpisodePanel -> {
                if (down && native.repeatCount == 0) chrome.openPanel(PlayerPanel.Episodes)
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
