package app.bolbol.tv.feature.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import app.bolbol.tv.core.designsystem.BolbolColors

/** Soft red of the heart in the dedication (#FF6B81). */
private val HeartColor = Color(0xFFFF6B81)

private const val HEART_ID = "heart"

/**
 * "Made with ❤ for Mehi by Rasoul": the dedication shown on the far end of the top bar (docs/design-system.md §Screens).
 * One left-to-right line in the secondary text colour, not focusable. The heart is a vector inside the text (a font
 * glyph would depend on the TV's emoji font), so it is always drawn, always [HeartColor]; the accessibility text still
 * reads "Made with ❤ for Mehi by Rasoul".
 */
@Composable
internal fun DedicationLine(modifier: Modifier = Modifier) {
    val text = remember {
        buildAnnotatedString {
            append("Made with ")
            appendInlineContent(HEART_ID, "❤")
            append(" for Mehi by Rasoul")
        }
    }
    val inlineContent = remember {
        mapOf(
            HEART_ID to InlineTextContent(
                Placeholder(width = 1.1.em, height = 1.1.em, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
            ) {
                Icon(imageVector = HeartIcon, contentDescription = null, modifier = Modifier.fillMaxSize(), tint = HeartColor)
            },
        )
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(
            color = BolbolColors.TextSecondary,
            textDirection = TextDirection.Ltr,
        ),
        overflow = TextOverflow.Clip,
        maxLines = 1,
        inlineContent = inlineContent,
    )
}

/** Material Symbols "favorite" (Apache 2.0). */
private val HeartIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Heart",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(
            "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09" +
                "C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}
