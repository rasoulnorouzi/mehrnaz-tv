package app.bolbol.tv.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private val BadgeShape = RoundedCornerShape(4.dp)

/** Solid pill used by all badges. No alpha: a flat colour is the cheapest thing to draw. */
@Composable
private fun Pill(
    background: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .background(background, BadgeShape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * Audio flavour pills: "دوبله" (dubbed, accent) and/or "زیرنویس" (subtitled, dark).
 * Draws nothing when both flags are false.
 */
@Composable
fun AudioBadge(
    dubbed: Boolean,
    subtitled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!dubbed && !subtitled) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (dubbed) {
            Pill(BolbolColors.Accent) {
                Text(
                    text = "دوبله",
                    style = MaterialTheme.typography.labelSmall,
                    color = BolbolColors.OnAccent,
                    maxLines = 1,
                )
            }
        }
        if (subtitled) {
            Pill(BolbolColors.Background) {
                Text(
                    text = "زیرنویس",
                    style = MaterialTheme.typography.labelSmall,
                    color = BolbolColors.TextPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** "IMDb 8.1" pill. The rating keeps Latin digits (data, see Formatters.kt). */
@Composable
fun RatingBadge(
    imdb: Float,
    modifier: Modifier = Modifier,
) {
    val label = remember(imdb) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = BolbolColors.Accent, fontWeight = FontWeight.Bold)) { append("IMDb") }
            append(' ')
            append(formatImdb(imdb))
        }
    }
    Pill(BolbolColors.Background, modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = BolbolColors.TextPrimary,
            maxLines = 1,
        )
    }
}

/**
 * Joins [parts] with " · " (blank parts are skipped): year, runtime, age rating, genres...
 * Example: `MetaLine(listOf("2025", formatRuntimeFa(102), "PG-13"))`.
 */
@Composable
fun MetaLine(
    parts: List<String>,
    modifier: Modifier = Modifier,
    color: Color = BolbolColors.TextSecondary,
    maxLines: Int = 1,
) {
    val line = remember(parts) { parts.filter { it.isNotBlank() }.joinToString(" · ") }
    if (line.isEmpty()) return
    BolbolText(
        text = line,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        maxLines = maxLines,
    )
}
