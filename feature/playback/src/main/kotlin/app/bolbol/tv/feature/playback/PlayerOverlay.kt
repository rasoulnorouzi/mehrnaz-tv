package app.bolbol.tv.feature.playback

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.PlaybackRequest
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.player.PlaybackPosition
import app.bolbol.tv.core.player.PlayerStatus
import kotlinx.coroutines.flow.StateFlow

// Pre-built scrims: the only translucency allowed over video (docs/design-system.md).
private val TopScrim = Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))
private val BottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))
private val TrackColor = Color(0x59FFFFFF)
private val BufferedColor = Color(0x99FFFFFF)

/** Fraction of the screen height the bottom controls cover (subtitles move above it). */
internal const val OVERLAY_SUBTITLE_PADDING = 0.27f

/**
 * The controls overlay: RTL title block on top; LTR timeline + RTL controls row at the bottom.
 * [position] is collected inside the seek row only, so the 1 Hz ticks don't recompose the rest.
 */
@Composable
internal fun PlayerOverlay(
    request: PlaybackRequest,
    status: PlayerStatus,
    position: StateFlow<PlaybackPosition>,
    chrome: PlayerChrome,
    onTogglePlay: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(TopScrim)
                .padding(
                    start = BolbolSpacing.SafeHorizontal,
                    end = BolbolSpacing.SafeHorizontal,
                    top = BolbolSpacing.SafeVertical,
                    bottom = 48.dp,
                ),
        ) {
            val summary = request.title
            val primary = summary.faTitle.ifBlank { summary.title }
            val secondary = request.subtitleLine
                ?: summary.title.takeIf { summary.faTitle.isNotBlank() && it != summary.faTitle }
            Text(
                primary,
                style = MaterialTheme.typography.headlineSmall,
                color = BolbolColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BolbolColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(BottomScrim)
                .padding(
                    start = BolbolSpacing.SafeHorizontal,
                    end = BolbolSpacing.SafeHorizontal,
                    top = 48.dp,
                    bottom = BolbolSpacing.SafeVertical,
                ),
        ) {
            SeekBarRow(position, chrome)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val playing = status.playWhenReady && !status.isEnded
                Button(onClick = onTogglePlay, modifier = Modifier.focusRequester(chrome.playPauseFocus)) {
                    PlayPauseGlyph(playing, Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (playing) R.string.player_pause else R.string.player_play))
                }
                Button(onClick = onOpenSettings, modifier = Modifier.focusRequester(chrome.settingsFocus)) {
                    Text(stringResource(R.string.player_settings))
                }
                Spacer(Modifier.weight(1f))
                status.stream?.let {
                    Text(streamLabel(it), style = MaterialTheme.typography.labelMedium, color = BolbolColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
internal fun streamLabel(stream: StreamOption): String =
    "${stream.quality.toPersianDigits()} · ${audioLabel(stream.audio)}"

@Composable
internal fun audioLabel(audio: AudioType): String = stringResource(
    when (audio) {
        AudioType.DUBBED -> R.string.player_audio_dubbed
        AudioType.SUBTITLED -> R.string.player_audio_subtitled
    },
)

/** Current time · bar · total time, always left-to-right (docs/decisions.md §Architecture). */
@Composable
private fun SeekBarRow(position: StateFlow<PlaybackPosition>, chrome: PlayerChrome) {
    val pos by position.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf(false) }
    val withHours = pos.durationMs >= 3_600_000L
    val duration = pos.durationMs
    val fraction = if (duration > 0) (pos.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val buffered = if (duration > 0) (pos.bufferedPositionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatPlaybackTime(pos.positionMs, withHours).toPersianDigits(),
                style = MaterialTheme.typography.titleMedium,
                color = BolbolColors.TextPrimary,
            )
            Spacer(Modifier.width(16.dp))
            SeekBar(
                fraction = fraction,
                buffered = buffered,
                focused = focused,
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
                    .onFocusChanged {
                        focused = it.isFocused
                        chrome.seekBarFocused = it.isFocused
                    }
                    .focusRequester(chrome.seekBarFocus)
                    .focusable(),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                if (duration > 0) formatPlaybackTime(duration, withHours).toPersianDigits() else "",
                style = MaterialTheme.typography.titleMedium,
                color = BolbolColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun SeekBar(fraction: Float, buffered: Float, focused: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val trackHeight = (if (focused) 8.dp else 4.dp).toPx()
        val top = size.height / 2 - trackHeight / 2
        val radius = CornerRadius(trackHeight / 2)
        drawRoundRect(TrackColor, Offset(0f, top), Size(size.width, trackHeight), radius)
        drawRoundRect(BufferedColor, Offset(0f, top), Size(size.width * buffered, trackHeight), radius)
        drawRoundRect(BolbolColors.Accent, Offset(0f, top), Size(size.width * fraction, trackHeight), radius)
        if (focused) drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(size.width * fraction, size.height / 2))
    }
}

/** Play triangle / pause bars. Media glyphs are never mirrored in RTL. */
@Composable
private fun PlayPauseGlyph(playing: Boolean, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        if (playing) {
            val bar = size.width * 0.32f
            drawRect(color, Offset(0f, 0f), Size(bar, size.height))
            drawRect(color, Offset(size.width - bar, 0f), Size(bar, size.height))
        } else {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, color)
        }
    }
}

/** Small accent spinner while buffering; rotation runs in the draw phase only. */
@Composable
internal fun BufferingSpinner(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "buffering")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "angle",
    )
    Canvas(modifier.size(44.dp).graphicsLayer { rotationZ = angle }) {
        val stroke = 4.dp.toPx()
        drawArc(
            color = BolbolColors.Accent,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
