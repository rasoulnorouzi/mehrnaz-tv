package app.bolbol.tv.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults

/**
 * Base focusable card: no shadows (too costly on a 2 GB TV), focus shown with
 * a white border + 1.08x scale, as specified in docs/design-system.md.
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(BolbolSpacing.CardRadius)
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = BolbolColors.Surface,
            focusedContainerColor = BolbolColors.SurfaceHigh,
        ),
        scale = CardDefaults.scale(focusedScale = BolbolSpacing.FOCUS_SCALE),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing),
                shape = shape,
            ),
        ),
        glow = CardDefaults.glow(),
    ) {
        content()
    }
}
