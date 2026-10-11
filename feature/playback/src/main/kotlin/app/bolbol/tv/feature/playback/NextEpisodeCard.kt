package app.bolbol.tv.feature.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.player.AutoNextPolicy
import app.bolbol.tv.core.player.AutoNextState

private val CardShape = RoundedCornerShape(8.dp)

/**
 * "قسمت بعد" corner card (M2.2): a countdown ring with the seconds left, the label and the next
 * episode ("فصل ۱ · قسمت ۳"). Opaque surface (no translucency over video). OK plays the next
 * episode now (handled by PlayerKeyHandler, so it works even if focus is elsewhere); BACK dismisses.
 *
 * [state] is read inside: the 5 Hz countdown recomposes only the number (once a second) and
 * redraws only the ring, never the rest of the player.
 */
@Composable
internal fun NextEpisodeCard(
    state: State<AutoNextState>,
    episodeLabel: String,
    focusRequester: FocusRequester,
    onPlayNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remainingMs = remember(state) {
        derivedStateOf { (state.value as? AutoNextState.Counting)?.remainingMs ?: 0L }
    }
    val seconds by remember(state) { derivedStateOf { ((remainingMs.value + 999) / 1000).toInt() } }
    Button(
        onClick = onPlayNow,
        modifier = modifier.focusRequester(focusRequester),
        shape = ButtonDefaults.shape(CardShape),
        contentPadding = PaddingValues(start = 12.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        colors = ButtonDefaults.colors(
            containerColor = BolbolColors.SurfaceHigh,
            contentColor = BolbolColors.TextPrimary,
            focusedContainerColor = BolbolColors.FocusRing,
            focusedContentColor = BolbolColors.OnAccent,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                val color = LocalContentColor.current
                Canvas(Modifier.size(44.dp)) {
                    val stroke = 4.dp.toPx()
                    val fraction = (remainingMs.value.toFloat() / AutoNextPolicy.COUNTDOWN_MS).coerceIn(0f, 1f)
                    val topLeft = Offset(stroke / 2, stroke / 2)
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                    drawArc(
                        color = BolbolColors.Accent,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                Text(seconds.toPersianDigits(), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.player_next_episode), style = MaterialTheme.typography.titleSmall)
                Text(episodeLabel, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A short message at the top centre over the video (opaque, no focus). */
@Composable
internal fun PlayerToast(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(BolbolColors.SurfaceHigh, CardShape)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = BolbolColors.TextPrimary)
    }
}
