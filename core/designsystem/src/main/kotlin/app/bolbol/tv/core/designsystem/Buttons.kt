package app.bolbol.tv.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface

enum class TvButtonStyle {
    /** Yellow filled: the one main action on a screen ("پخش", "ادامه از ..."). */
    Primary,

    /** Outlined: every other action; fills white when focused. */
    Secondary,

    /** Red filled: removing something ("حذف دانلود", "حذف همه"). Always asks to confirm first. */
    Danger,
}

private val ButtonShape = RoundedCornerShape(BolbolSpacing.CardRadius)
private val ChipShape = RoundedCornerShape(50)

private val FocusBorder = Border(
    border = BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing),
    shape = ButtonShape,
)
private val ChipFocusBorder = Border(
    border = BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing),
    shape = ChipShape,
)

/**
 * D-pad focusable button. Primary = yellow, Secondary = outline (fills white on focus). Focus is
 * shown by a white 2 dp border and a 1.05x `graphicsLayer` scale (no shadow). [icon] sits at the
 * start of the label (right in RTL). Min height 40 dp.
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: TvButtonStyle = TvButtonStyle.Primary,
    enabled: Boolean = true,
) {
    val primary = style == TvButtonStyle.Primary
    val danger = style == TvButtonStyle.Danger
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(ButtonShape),
        colors = if (danger) {
            ClickableSurfaceDefaults.colors(
                containerColor = BolbolColors.Error,
                contentColor = BolbolColors.Background,
                focusedContainerColor = BolbolColors.Error,
                focusedContentColor = BolbolColors.Background,
                pressedContainerColor = BolbolColors.Error,
                pressedContentColor = BolbolColors.Background,
                disabledContainerColor = BolbolColors.SurfaceHigh,
                disabledContentColor = BolbolColors.TextSecondary,
            )
        } else if (primary) {
            ClickableSurfaceDefaults.colors(
                containerColor = BolbolColors.Accent,
                contentColor = BolbolColors.OnAccent,
                focusedContainerColor = BolbolColors.Accent,
                focusedContentColor = BolbolColors.OnAccent,
                pressedContainerColor = BolbolColors.Accent,
                pressedContentColor = BolbolColors.OnAccent,
                disabledContainerColor = BolbolColors.SurfaceHigh,
                disabledContentColor = BolbolColors.TextSecondary,
            )
        } else {
            ClickableSurfaceDefaults.colors(
                containerColor = BolbolColors.Background,
                contentColor = BolbolColors.TextPrimary,
                focusedContainerColor = BolbolColors.TextPrimary,
                focusedContentColor = BolbolColors.Background,
                pressedContainerColor = BolbolColors.TextPrimary,
                pressedContentColor = BolbolColors.Background,
                disabledContainerColor = BolbolColors.Background,
                disabledContentColor = BolbolColors.TextSecondary,
            )
        },
        scale = ClickableSurfaceDefaults.scale(focusedScale = BolbolSpacing.FOCUS_SCALE_SMALL),
        border = ClickableSurfaceDefaults.border(
            border = if (primary || danger) Border.None else Border(BorderStroke(1.dp, BolbolColors.Outline), shape = ButtonShape),
            focusedBorder = FocusBorder,
        ),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            BolbolText(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * Pill for pickers (audio, quality, season...). Selected = yellow fill; unselected = dark fill;
 * focused = white border + 1.05x scale (and a white fill when not selected).
 */
@Composable
fun SelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(ChipShape),
        colors = if (selected) {
            ClickableSurfaceDefaults.colors(
                containerColor = BolbolColors.Accent,
                contentColor = BolbolColors.OnAccent,
                focusedContainerColor = BolbolColors.Accent,
                focusedContentColor = BolbolColors.OnAccent,
                pressedContainerColor = BolbolColors.Accent,
                pressedContentColor = BolbolColors.OnAccent,
            )
        } else {
            ClickableSurfaceDefaults.colors(
                containerColor = BolbolColors.SurfaceHigh,
                contentColor = BolbolColors.TextPrimary,
                focusedContainerColor = BolbolColors.TextPrimary,
                focusedContentColor = BolbolColors.Background,
                pressedContainerColor = BolbolColors.TextPrimary,
                pressedContentColor = BolbolColors.Background,
            )
        },
        scale = ClickableSurfaceDefaults.scale(focusedScale = BolbolSpacing.FOCUS_SCALE_SMALL),
        border = ClickableSurfaceDefaults.border(focusedBorder = ChipFocusBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) Icon(imageVector = BolbolIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            BolbolText(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * A row of [SelectableChip]s (exactly one selected). Focus enters on the selected chip and
 * returns to the last focused one. [onSelect] gets the index of the clicked chip.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChipGroup(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedRequester = remember { FocusRequester() }
    Row(
        modifier = modifier.focusRestorer(
            // Only point at the selected chip when it exists (an unattached requester would throw).
            if (selectedIndex in options.indices) selectedRequester else FocusRequester.Default,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            key(index) {
                SelectableChip(
                    text = label,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = if (index == selectedIndex) Modifier.focusRequester(selectedRequester) else Modifier,
                )
            }
        }
    }
}
