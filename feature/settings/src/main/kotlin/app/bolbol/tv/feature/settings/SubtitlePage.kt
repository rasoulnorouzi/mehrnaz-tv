package app.bolbol.tv.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.SubtitleText
import app.bolbol.tv.core.model.SubtitleStyle

/** Sample dialogue. Line 3 starts with Latin text, so it carries the RLM the subtitle engine adds. */
private val PreviewLines = listOf(
    "- سلام! حالت چطوره؟",
    "این یک زیرنویس نمونه است.",
    "\u200FOK، بریم!",
)

/** The TV canvas the subtitle layout is designed for (docs/design-system.md), in dp. */
private const val CanvasWidth = 960
private const val CanvasHeight = 540

private val PreviewWidth = 384.dp
private val PreviewHeight = 216.dp
private const val PreviewScale = 384f / CanvasWidth

private val FrameBrush = Brush.verticalGradient(listOf(Color(0xFF2B3552), Color(0xFF151A27), Color(0xFF0B0D12)))

/**
 * Settings → زیرنویس: live preview on top (fixed), the option rows below (scrollable).
 * [entry] marks the first row, where focus lands when entering the page.
 */
@Composable
internal fun SubtitlePage(
    style: SubtitleStyle,
    onChange: (SubtitleChange) -> Unit,
    entry: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SubtitlePreview(style)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BolbolText(
                    text = "پیش‌نمایش زنده",
                    style = MaterialTheme.typography.titleSmall,
                )
                BolbolText(
                    text = "هر تغییر همان لحظه ذخیره می‌شود و همین‌جا دیده می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BolbolColors.TextSecondary,
                )
                BolbolText(
                    text = "هماهنگی زمان‌بندی (جلو یا عقب بردن زیرنویس) هنگام پخش، از منوی زیرنویس در پخش‌کننده تنظیم می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BolbolColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            OptionRows(
                specs = SubtitleOptionSpecs,
                value = style,
                onChange = onChange,
                firstRowModifier = Modifier.focusRequester(entry),
            )
        }
    }
}

/**
 * A 16:9 miniature of the screen. The real [SubtitleText] (the one the player uses) is laid out on
 * the full 960x540 dp canvas and scaled down, so size, position and wrapping match what the user
 * will get on the TV, just smaller.
 */
@Composable
private fun SubtitlePreview(style: SubtitleStyle, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(PreviewWidth, PreviewHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(FrameBrush),
    ) {
        if (!style.enabled) {
            BolbolText(
                text = "زیرنویس خاموش است",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodySmall,
                color = BolbolColors.TextSecondary,
            )
        }
        Box(
            Modifier
                .requiredSize(CanvasWidth.dp, CanvasHeight.dp)
                .graphicsLayer {
                    scaleX = PreviewScale
                    scaleY = PreviewScale
                },
        ) {
            SubtitleText(lines = PreviewLines, style = style)
        }
    }
}
