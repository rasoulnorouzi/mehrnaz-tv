package app.bolbol.tv.feature.playback

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.bolbol.tv.core.designsystem.BolbolColors

private val PillShape = RoundedCornerShape(24.dp)

/**
 * Shown over the last video frame while a quality/audio switch loads
 * (`PlaybackController.switchingQuality`, v1.1-2), instead of the bare buffering spinner, so a
 * frozen picture reads as "working on it". A small accent spinner and the label in one dark pill;
 * the Row follows the screen's RTL direction (spinner at the start = right). Not focusable.
 *
 * Wiring (PlayerScreen, in place of the BufferingSpinner while switching):
 * ```
 * val switching by controller.switchingQuality.collectAsStateWithLifecycle()
 * if (switching && !hasError) QualitySwitchIndicator(Modifier.align(Alignment.Center))
 * else if (playing && status.isBuffering && !hasError) BufferingSpinner(Modifier.align(Alignment.Center))
 * ```
 */
@Composable
internal fun QualitySwitchIndicator(modifier: Modifier = Modifier, label: String = "در حال تغییر کیفیت…") {
    Row(
        modifier
            .background(BolbolColors.Surface.copy(alpha = 0.88f), PillShape)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SmallSpinner()
        Text(label, style = MaterialTheme.typography.titleMedium, color = BolbolColors.TextPrimary)
    }
}

/** 20 dp accent arc; the rotation runs in the draw phase only (no recomposition per frame). */
@Composable
private fun SmallSpinner() {
    val transition = rememberInfiniteTransition(label = "switching")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "angle",
    )
    Canvas(Modifier.size(20.dp).graphicsLayer { rotationZ = angle }) {
        val stroke = 2.5.dp.toPx()
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
