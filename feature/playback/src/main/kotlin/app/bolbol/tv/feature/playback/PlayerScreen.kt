package app.bolbol.tv.feature.playback

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.formatEpisodeFa
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.player.AutoNextState
import app.bolbol.tv.core.player.PlaybackDiagnostics
import app.bolbol.tv.core.player.PlayerEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private const val OVERLAY_AUTO_HIDE_MS = 4_000L
private const val TOAST_MS = 2_000L

/** ComponentCallbacks2.TRIM_MEMORY_COMPLETE (the constant is deprecated on API 34+). */
private const val LOW_MEMORY_LEVEL = 80

/**
 * Full-screen player.
 *
 * Remote: OK/ENTER/PLAY_PAUSE toggle · LEFT/RIGHT ∓/±10 s (accelerating when held, LTR
 * timeline) · FF/REW ±30 s · UP/DOWN show the controls · MENU or "تنظیمات پخش" opens the side
 * panel (audio, quality, subtitle on/off, size and sync) · BACK closes the panel → hides the
 * controls → dismisses the next-episode card → exits (progress is saved).
 *
 * Next episode (M2.2): when the request has [app.bolbol.tv.core.model.PlaybackRequest.next], a
 * "قسمت بعد" card with a 10 s countdown appears in the last 30 s (or at the end); OK plays the next
 * episode now, BACK dismisses it. Leaving marks this episode completed, then calls [onPlayNext].
 *
 * @param onExit navigate back; called after playback is paused (the ViewModel saves on clear).
 * @param onPlayNext open the player for the next episode (replace this entry so this player is
 *   released before the next one is created: one ExoPlayer at a time).
 * @param subtitleTypeface ignored: subtitles are drawn by the designsystem `SubtitleText` with
 *   Vazirmatn. Kept only so existing call sites compile; remove it from the call site.
 * @param showDiagnostics force the debug box on (it can also be toggled with UP ×3).
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayNext: (next: WatchKey) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") subtitleTypeface: Typeface? = null,
    showDiagnostics: Boolean = false,
) {
    val controller = viewModel.controller
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    val subtitleStyle by controller.subtitleStyle.collectAsStateWithLifecycle()
    val subtitleOffsetMs by controller.subtitleOffsetMs.collectAsStateWithLifecycle()
    // Not delegated: the countdown changes 5×/s, so only derived values are read here.
    val autoNextState = viewModel.autoNext.collectAsStateWithLifecycle()
    val autoNextCounting by remember { derivedStateOf { autoNextState.value is AutoNextState.Counting } }
    val autoNextIdleAtEnd by remember {
        derivedStateOf { autoNextState.value.let { it == AutoNextState.DismissedAtEnd || it == AutoNextState.PlayNext } }
    }
    val phase = screen.phase
    val playing = phase == PlayerPhase.Playing
    val hasError = error != null
    val next = viewModel.request.next

    val chrome = remember { PlayerChrome() }
    val keys = remember(chrome, viewModel) { PlayerKeyHandler(chrome, viewModel) }
    val currentOnExit by rememberUpdatedState(onExit)
    val currentOnPlayNext by rememberUpdatedState(onPlayNext)
    val exit = remember(viewModel) {
        {
            viewModel.prepareExit()
            currentOnExit()
        }
    }

    val cardVisible = autoNextCounting && playing && !hasError && !chrome.overlayVisible && !chrome.panelOpen
    SideEffect { chrome.autoNextVisible = cardVisible }

    BackHandler {
        when {
            !playing || hasError -> exit()
            chrome.panelOpen -> chrome.closePanel()
            chrome.overlayVisible -> chrome.hideOverlay()
            cardVisible -> viewModel.dismissAutoNext()
            else -> exit()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (viewModel.onStart()) chrome.showOverlay(OverlayFocus.PlayPause)
    }
    val context = LocalContext.current
    DisposableEffect(context, viewModel) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = viewModel.onTrimMemory(level)
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = viewModel.onTrimMemory(LOW_MEMORY_LEVEL)
        }
        val app = context.applicationContext
        app.registerComponentCallbacks(callbacks)
        onDispose { app.unregisterComponentCallbacks(callbacks) }
    }

    // One-shot events: subtitle load failure toast, and leaving for the next episode.
    val subtitleFailed = stringResource(R.string.player_subtitle_load_failed)
    val currentSubtitleFailed by rememberUpdatedState(subtitleFailed)
    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                PlayerEvent.SubtitleLoadFailed -> chrome.showToast(currentSubtitleFailed)
            }
        }
    }
    LaunchedEffect(viewModel) { viewModel.playNext.collect { currentOnPlayNext(it) } }
    LaunchedEffect(chrome.toastId) {
        if (chrome.toast != null) {
            delay(TOAST_MS)
            chrome.clearToast()
        }
    }

    // Auto-hide after 4 s without input; stays while paused, ended, or the panel is open.
    val autoHide = chrome.overlayVisible && !chrome.panelOpen && status.playWhenReady && !status.isEnded
    LaunchedEffect(autoHide, chrome.interactions) {
        if (autoHide) {
            delay(OVERLAY_AUTO_HIDE_MS)
            chrome.hideOverlay()
        }
    }
    // At the end: the next-episode card takes over (hide the controls), unless there is no next
    // episode or the user dismissed the card at the end; then show the controls ("پخش" restarts).
    val nextTakesOverAtEnd = next != null && !autoNextIdleAtEnd
    LaunchedEffect(status.isEnded, nextTakesOverAtEnd) {
        if (!status.isEnded) return@LaunchedEffect
        if (nextTakesOverAtEnd) chrome.hideOverlay() else chrome.showOverlay(OverlayFocus.PlayPause)
    }
    // The card's countdown is frozen while the controls or the panel cover it.
    val covering = chrome.overlayVisible || chrome.panelOpen
    LaunchedEffect(covering) { viewModel.setChromeCovering(covering) }

    // Focus follows the chrome; dialogs, the panel and the card focus themselves.
    LaunchedEffect(chrome.focusRequests, playing, hasError, chrome.panelOpen) {
        if (!playing || hasError || chrome.panelOpen) return@LaunchedEffect
        val target = if (!chrome.overlayVisible) {
            chrome.rootFocus
        } else {
            when (chrome.overlayFocus) {
                OverlayFocus.SeekBar -> chrome.seekBarFocus
                OverlayFocus.PlayPause -> chrome.playPauseFocus
                OverlayFocus.Settings -> chrome.settingsFocus
            }
        }
        target.tryRequestFocus()
    }
    val cardFocus = remember { FocusRequester() }
    var cardWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(cardVisible) {
        if (cardVisible) {
            cardFocus.tryRequestFocus()
        } else if (cardWasVisible) {
            chrome.refocus() // the focused card left: give focus back to the root (or the overlay)
        }
        cardWasVisible = cardVisible
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent(keys::onPreviewKeyEvent)
            .onFocusChanged { chrome.rootFocused = it.isFocused }
            .focusRequester(chrome.rootFocus)
            .focusable(),
    ) {
        VideoSurface(viewModel.player, status.videoAspectRatio)
        SubtitleLayer(
            lines = controller.subtitleLines,
            style = subtitleStyle,
            aboveControls = chrome.overlayVisible && !chrome.panelOpen,
        )

        if (playing && status.isBuffering && !hasError) {
            BufferingSpinner(Modifier.align(Alignment.Center))
        }
        if (cardVisible && next != null) {
            NextEpisodeCard(
                state = autoNextState,
                episodeLabel = formatEpisodeFa(next.season, next.episode),
                focusRequester = cardFocus,
                onPlayNow = viewModel::playNextNow,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = BolbolSpacing.SafeHorizontal, bottom = BolbolSpacing.SafeVertical + 16.dp),
            )
        }
        if (playing && chrome.overlayVisible && !chrome.panelOpen && !hasError) {
            PlayerOverlay(
                request = viewModel.request,
                status = status,
                position = controller.position,
                chrome = chrome,
                onTogglePlay = controller::togglePlayPause,
                onOpenSettings = chrome::openPanel,
            )
        }
        if (playing && chrome.panelOpen && !hasError) {
            val showOffsetToast = { ms: Int ->
                chrome.showToast(context.getString(R.string.player_subtitle_offset_toast, formatSubtitleOffset(ms)))
            }
            StreamPickerPanel(
                streams = viewModel.request.streams,
                current = status.stream,
                subtitleStyle = subtitleStyle,
                subtitleOffsetMs = subtitleOffsetMs,
                onAudio = viewModel::selectAudio,
                onQuality = viewModel::selectQuality,
                onSubtitlesEnabled = viewModel::setSubtitlesEnabled,
                onSubtitleSize = viewModel::setSubtitleSize,
                onSubtitleShift = { direction -> showOffsetToast(viewModel.shiftSubtitles(direction)) },
                onSubtitleReset = { showOffsetToast(viewModel.resetSubtitleOffset()) },
            )
        }
        error?.let { e ->
            if (playing) {
                ErrorPanel(
                    error = e,
                    canLowerQuality = viewModel.canLowerQuality(),
                    onRetry = viewModel::retry,
                    onLowerQuality = viewModel::lowerQuality,
                )
            }
        }
        when (phase) {
            is PlayerPhase.AskResume -> {
                val title = viewModel.request.title
                ResumeDialog(
                    title = title.faTitle.ifBlank { title.title },
                    positionMs = phase.positionMs,
                    onResume = { viewModel.onResumeChoice(resume = true) },
                    onStartOver = { viewModel.onResumeChoice(resume = false) },
                )
            }
            PlayerPhase.NoStreams -> NoStreamsPanel(onBack = exit)
            else -> Unit
        }
        chrome.toast?.let { text ->
            PlayerToast(text, Modifier.align(Alignment.TopCenter).padding(top = BolbolSpacing.SafeVertical))
        }
        if (showDiagnostics || chrome.debugVisible) {
            DiagnosticsLayer(controller.diagnostics, Modifier.align(AbsoluteAlignment.TopLeft).padding(12.dp))
        }
    }
}

@Composable
private fun DiagnosticsLayer(diagnostics: StateFlow<PlaybackDiagnostics>, modifier: Modifier) {
    val current by diagnostics.collectAsStateWithLifecycle()
    PlaybackDiagnosticsOverlay(current, modifier)
}
