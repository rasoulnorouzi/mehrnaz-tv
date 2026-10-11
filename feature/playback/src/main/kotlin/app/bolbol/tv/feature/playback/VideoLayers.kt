@file:OptIn(UnstableApi::class)

package app.bolbol.tv.feature.playback

import android.graphics.Color
import android.graphics.Typeface
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
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
 * Persian subtitles rendered by media3-ui's [SubtitleView] (canvas painter) from the
 * controller's filtered cues: white text, black outline, no background box.
 */
@Composable
internal fun SubtitleLayer(
    cues: StateFlow<List<Cue>>,
    typeface: Typeface,
    scale: Float,
    bottomPaddingFraction: Float,
    modifier: Modifier = Modifier,
) {
    val current by cues.collectAsStateWithLifecycle()
    val style = remember(typeface) {
        CaptionStyleCompat(
            /* foregroundColor = */ Color.WHITE,
            /* backgroundColor = */ Color.TRANSPARENT,
            /* windowColor = */ Color.TRANSPARENT,
            /* edgeType = */ CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            /* edgeColor = */ Color.BLACK,
            /* typeface = */ typeface,
        )
    }
    AndroidView(
        factory = { context ->
            SubtitleView(context).apply {
                // Our size setting wins over sizes embedded in the VTT; keep italics etc.
                setApplyEmbeddedFontSizes(false)
                setApplyEmbeddedStyles(true)
            }
        },
        update = { view ->
            view.setStyle(style)
            view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
            view.setBottomPaddingFraction(bottomPaddingFraction)
            view.setCues(current)
        },
        modifier = modifier.fillMaxSize(),
    )
}
