package app.bolbol.tv.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface

private val TabShape = RoundedCornerShape(50)
private val TabFocusBorder = Border(
    border = BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing),
    shape = TabShape,
)

/**
 * Home top bar: horizontal text tabs ("جستجو · خانه · فیلم · سریال · ..."), first item at the
 * start (right in RTL).
 *
 * - Focused tab: white 2 dp pill border + lighter fill. Selected tab: accent text + underline.
 * - [onSelect] fires on OK/Enter; with [selectOnFocus] it also fires as focus lands on a tab
 *   (switch content while browsing the bar, like a TabRow).
 * - When focus enters the bar from below it lands on the selected tab, and returns to the last
 *   focused one after leaving and coming back.
 * - Height 44 dp; horizontal padding = the 48 dp safe margin. Top safe margin is the caller's job.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopBar(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectOnFocus: Boolean = false,
) {
    val selectedRequester = remember { FocusRequester() }
    val currentOnSelect = rememberUpdatedState(onSelect)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = BolbolSpacing.SafeHorizontal)
            .focusRestorer(
                // Only point at the selected tab when it exists (an unattached requester would throw).
                if (selectedIndex in items.indices) selectedRequester else FocusRequester.Default,
            ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, label ->
            key(index) {
                TopBarTab(
                    text = label,
                    selected = index == selectedIndex,
                    onClick = { currentOnSelect.value(index) },
                    modifier = Modifier
                        .then(if (index == selectedIndex) Modifier.focusRequester(selectedRequester) else Modifier)
                        .onFocusChanged { if (selectOnFocus && it.isFocused) currentOnSelect.value(index) },
                )
            }
        }
    }
}

@Composable
private fun TopBarTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(TabShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) BolbolColors.Accent else BolbolColors.TextSecondary,
            focusedContainerColor = BolbolColors.SurfaceHigh,
            focusedContentColor = if (selected) BolbolColors.Accent else BolbolColors.TextPrimary,
            pressedContainerColor = BolbolColors.SurfaceHigh,
            pressedContentColor = BolbolColors.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(focusedBorder = TabFocusBorder),
    ) {
        Box(
            Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .drawBehind {
                    if (selected) {
                        val thickness = 3.dp.toPx()
                        drawRoundRect(
                            color = BolbolColors.Accent,
                            topLeft = Offset(0f, size.height - thickness),
                            size = Size(size.width, thickness),
                            cornerRadius = CornerRadius(thickness / 2f),
                        )
                    }
                },
        ) {
            BolbolText(
                text = text,
                modifier = Modifier.padding(bottom = 6.dp),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
        }
    }
}
