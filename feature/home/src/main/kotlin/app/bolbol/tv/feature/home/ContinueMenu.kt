package app.bolbol.tv.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText

/** What the Continue Watching menu can do. */
internal enum class ContinueAction { RESUME, MARK_WATCHED, REMOVE, CANCEL }

private val PanelWidth = 380.dp
private val PanelShape = RoundedCornerShape(12.dp)
private val ItemShape = RoundedCornerShape(BolbolSpacing.CardRadius)
private val ItemHeight = 48.dp

/**
 * The small popup menu of a Continue Watching card, drawn over the home screen (same window, so
 * the focus handling below is fully ours): focus starts on "ادامه پخش", D-pad up/down moves
 * between the four entries, nothing can leave the panel, and Back cancels.
 *
 * [openedByHold] is true when OK is still physically held down as the menu opens: that hold's
 * remaining auto-repeats and its release are swallowed, otherwise the release would "click" the
 * entry that just got focus.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ContinueMenu(
    title: String,
    openedByHold: Boolean,
    onChoose: (ContinueAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstItem = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstItem.requestFocus() }
    var swallowHeldSelect by remember { mutableStateOf(openedByHold) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BolbolColors.Background.copy(alpha = 0.82f))
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.Back -> {
                        if (event.type == KeyEventType.KeyUp) onChoose(ContinueAction.CANCEL)
                        true
                    }
                    swallowHeldSelect && event.remoteKey() == RemoteKey.SELECT -> {
                        if (event.isDown && event.nativeKeyEvent.repeatCount == 0) {
                            swallowHeldSelect = false // a fresh press: the hold is over
                            false
                        } else {
                            if (!event.isDown) swallowHeldSelect = false
                            true
                        }
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(PanelWidth)
                .background(BolbolColors.Surface, PanelShape)
                .padding(20.dp)
                // Focus cannot leave the panel in any direction.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BolbolText(
                text = title,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                color = BolbolColors.TextPrimary,
                maxLines = 1,
            )
            MenuItem(
                text = stringResource(R.string.home_menu_resume),
                onClick = { onChoose(ContinueAction.RESUME) },
                modifier = Modifier.focusRequester(firstItem),
            )
            MenuItem(
                text = stringResource(R.string.home_menu_mark_watched),
                onClick = { onChoose(ContinueAction.MARK_WATCHED) },
            )
            MenuItem(
                text = stringResource(R.string.home_menu_remove),
                onClick = { onChoose(ContinueAction.REMOVE) },
            )
            MenuItem(
                text = stringResource(R.string.home_menu_cancel),
                onClick = { onChoose(ContinueAction.CANCEL) },
            )
        }
    }
}

/** Full-width entry: dark fill, white fill + white border when focused (like the secondary buttons). */
@Composable
private fun MenuItem(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(ItemHeight),
        shape = ClickableSurfaceDefaults.shape(ItemShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = BolbolColors.SurfaceHigh,
            contentColor = BolbolColors.TextPrimary,
            focusedContainerColor = BolbolColors.TextPrimary,
            focusedContentColor = BolbolColors.Background,
            pressedContainerColor = BolbolColors.TextPrimary,
            pressedContentColor = BolbolColors.Background,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing), shape = ItemShape),
        ),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            BolbolText(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
        }
    }
}
