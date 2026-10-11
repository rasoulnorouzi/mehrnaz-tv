package app.bolbol.tv.core.designsystem

import android.text.BidiFormatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private val RtlContextFormatter: BidiFormatter = BidiFormatter.getInstance(true)

/**
 * Makes [this] safe to show inside a right-to-left paragraph: a Latin title such as
 * "Dune (2024)" or "Mission: Impossible!" is wrapped in direction marks so its punctuation
 * and digits are not flipped to the wrong side. Persian text is returned unchanged.
 */
fun String.bidiSafe(): String = RtlContextFormatter.unicodeWrap(this)

/**
 * `Text` for anything that may contain user-visible titles (Persian, Latin or mixed).
 * Forces an RTL paragraph, so Persian and Latin titles all align to the start (right) edge,
 * and applies [bidiSafe]. Colour defaults to the surrounding content colour (inside a Surface
 * that is the focus-aware colour; in the theme root it is [BolbolColors.TextPrimary]).
 */
@Composable
fun BolbolText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Start,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val safe = remember(text) { text.bidiSafe() }
    val rtlStyle = remember(style) { style.copy(textDirection = TextDirection.Rtl) }
    Text(
        text = safe,
        modifier = modifier,
        color = color,
        style = rtlStyle,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
    )
}
