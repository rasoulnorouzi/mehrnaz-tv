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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bolbol.tv.core.player.PlaybackDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private const val OVERLAY_AUTO_HIDE_MS = 4_000L
private const val SUBTITLE_PADDING = 0.08f

/** ComponentCallbacks2.TRIM_MEMORY_COMPLETE (the constant is deprecated on API 34+). */
private const val LOW_MEMORY_LEVEL = 80

/**
 * Full-screen player.
 *
 * Remote: OK/ENTER/PLAY_PAUSE toggle · LEFT/RIGHT ∓/±10 s (accelerating when held, LTR
 * timeline) · FF/REW ±30 s · UP/DOWN show the controls · MENU or "تنظیمات پخش" opens the stream
 * picker · BACK closes the panel → hides the controls → exits (progress is saved).
 *
 * @param onExit navigate back; called after playback is paused (the ViewModel saves on clear).
 * @param subtitleTypeface the app passes Vazirmatn here.
 * @param showDiagnostics force the debug box on (it can also be toggled with UP ×3).
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleTypeface: Typeface = Typeface.DEFAULT,
    showDiagnostics: Boolean = false,
) {
    val controller = viewModel.controller
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    val phase = screen.phase
    val playing = phase == PlayerPhase.Playing

    val chrome = remember { PlayerChrome() }
    val keys = remember(chrome, viewModel) { PlayerKeyHandler(chrome, viewModel) }
    val currentOnExit by rememberUpdatedState(onExit)
    val exit = remember(viewModel) {
        {
            viewModel.prepareExit()
            currentOnExit()
        }
    }

    BackHandler {
        when {
            !playing || error != null -> exit()
            chrome.panelOpen -> chrome.closePanel()
            chrome.overlayVisible -> chrome.hideOverlay()
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

    // Auto-hide after 4 s without input; stays while paused, ended, or the panel is open.
    val autoHide = chrome.overlayVisible && !chrome.panelOpen && status.playWhenReady && !status.isEnded
    LaunchedEffect(autoHide, chrome.interactions) {
        if (autoHide) {
            delay(OVERLAY_AUTO_HIDE_MS)
            chrome.hideOverlay()
        }
    }
    LaunchedEffect(status.isEnded) {
        if (status.isEnded) chrome.showOverlay(OverlayFocus.PlayPause)
    }
    // Focus follows the chrome; dialogs and the panel focus themselves.
    val hasError = error != null
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
            cues = controller.cues,
            typeface = subtitleTypeface,
            scale = screen.subtitleScale,
            bottomPaddingFraction = if (chrome.overlayVisible) OVERLAY_SUBTITLE_PADDING else SUBTITLE_PADDING,
        )

        if (playing && status.isBuffering && !hasError) {
            BufferingSpinner(Modifier.align(Alignment.Center))
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
            StreamPickerPanel(
                streams = viewModel.request.streams,
                current = status.stream,
                subtitleScale = screen.subtitleScale,
                onAudio = viewModel::selectAudio,
                onQuality = viewModel::selectQuality,
                onSubtitleScale = viewModel::setSubtitleScale,
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
