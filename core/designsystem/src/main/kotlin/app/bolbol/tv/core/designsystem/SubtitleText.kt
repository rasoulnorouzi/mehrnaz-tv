package app.bolbol.tv.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.bolbol.tv.core.model.SubtitleBackground
import app.bolbol.tv.core.model.SubtitleColor
import app.bolbol.tv.core.model.SubtitleStyle

/** Base subtitle size before [SubtitleStyle.sizeScale] (on the 960×540 dp TV canvas ≈ 5.5 % of height). */
val SubtitleBaseSize = 30.sp

/**
 * The single subtitle renderer (docs/subtitles.md). Used by the player AND by the Settings preview,
 * so what the user previews is exactly what they get.
 *
 * Draws [lines] centred, right-to-left (`TextDirection.ContentOrRtl`), Vazirmatn, with either a black
 * outline (two layered texts: stroke then fill; no blur/shadow, cheap on the 2 GB TV) or a
 * semi-transparent box. Place it full-screen: it positions itself at [SubtitleStyle.position].
 * Recomposes only when [lines] or [style] change (the engine emits per cue, not per frame).
 */
@Composable
fun SubtitleText(
    lines: List<String>,
    style: SubtitleStyle,
    modifier: Modifier = Modifier,
) {
    if (!style.enabled || lines.isEmpty()) return
    val text = remember(lines) { lines.joinToString("\n") }
    val fill = when (style.color) {
        SubtitleColor.WHITE -> Color.White
        SubtitleColor.YELLOW -> BolbolColors.SubtitleYellow
        SubtitleColor.CYAN -> BolbolColors.SubtitleCyan
        SubtitleColor.GREEN -> BolbolColors.SubtitleGreen
        SubtitleColor.PINK -> BolbolColors.SubtitlePink
    }
    val textStyle = remember(style, fill) {
        TextStyle(
            fontFamily = VazirmatnFamily,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Medium,
            fontSize = SubtitleBaseSize * style.sizeScale,
            lineHeight = SubtitleBaseSize * style.sizeScale * 1.35f,
            textAlign = TextAlign.Center,
            textDirection = TextDirection.ContentOrRtl,
            color = fill,
        )
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val bottom = maxHeight * style.position.bottomFraction
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 96.dp, end = 96.dp, bottom = bottom),
        ) {
            when (style.background) {
                SubtitleBackground.BOX -> Text(
                    text = text,
                    style = textStyle,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
                SubtitleBackground.OUTLINE -> Box {
                    val strokeWidth = with(androidx.compose.ui.platform.LocalDensity.current) {
                        (3.dp * style.sizeScale).toPx()
                    }
                    Text(
                        text = text,
                        style = textStyle.copy(
                            color = Color.Black,
                            drawStyle = Stroke(width = strokeWidth, join = StrokeJoin.Round),
                        ),
                    )
                    Text(text = text, style = textStyle)
                }
            }
        }
    }
}
