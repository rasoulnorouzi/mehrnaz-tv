package app.bolbol.tv.feature.playback

import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import app.bolbol.tv.core.designsystem.SubtitleText
import app.bolbol.tv.core.model.SubtitleStyle
import kotlinx.coroutines.flow.StateFlow

/**
 * Full-screen video on a SurfaceView (docs/decisions.md §Player), letterboxed to
 * [aspectRatio] and centred; the parent's black background fills the bars.
 */
@Composable
internal fun VideoSurface(player: ExoPlayer, aspectRatio: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    keepScreenOn = true
                    player.setVideoSurfaceView(this)
                }
            },
            onRelease = { player.clearVideoSurfaceView(it) },
            modifier = Modifier.aspectRatio(aspectRatio.takeIf { it > 0f } ?: (16f / 9f)),
        )
    }
}

/**
 * Persian subtitles from our own engine (core:player subtitles/), drawn by the shared
 * designsystem [SubtitleText] (the same renderer as the Settings preview). [lines] is collected
 * here so only this layer recomposes when a cue changes. With [aboveControls] the subtitle box
 * ends where the overlay's bottom controls start, so the text moves up instead of being hidden.
 */
@Composable
internal fun SubtitleLayer(
    lines: StateFlow<List<String>>,
    style: SubtitleStyle,
    aboveControls: Boolean,
    modifier: Modifier = Modifier,
    besidePanel: Boolean = false,
) {
    val current by lines.collectAsStateWithLifecycle()
    val base = if (aboveControls) Modifier.fillMaxWidth().fillMaxHeight(1f - CONTROLS_HEIGHT_FRACTION) else Modifier.fillMaxSize()
    // With the settings panel open (end side in RTL), centre subtitles in the still-visible video area.
    val area = if (besidePanel) base.padding(end = PANEL_WIDTH) else base
    SubtitleText(lines = current, style = style, modifier = modifier.then(area))
}
