package app.bolbol.tv.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Thin progress bar: accent fill on a dark track. Two rectangles drawn in the draw phase, no
 * child layouts. The fill grows from the **start** edge (right in RTL). [fraction] is clamped
 * to 0..1. Default height 4 dp; the default width is the parent's width.
 */
@Composable
fun ProgressLine(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    color: Color = BolbolColors.Accent,
    trackColor: Color = BolbolColors.ProgressTrack,
) {
    val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                drawRect(trackColor)
                val w = size.width * f
                if (w > 0f) {
                    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
                    drawRect(color, topLeft = Offset(left, 0f), size = Size(w, size.height))
                }
            },
    )
}
