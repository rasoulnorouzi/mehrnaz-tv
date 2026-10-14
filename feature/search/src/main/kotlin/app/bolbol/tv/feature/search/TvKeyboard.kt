package app.bolbol.tv.feature.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.SelectableChip
import app.bolbol.tv.core.designsystem.TvButton
import app.bolbol.tv.core.designsystem.TvButtonStyle

private val PanelShape = RoundedCornerShape(12.dp)
private val KeyShape = RoundedCornerShape(6.dp)
private val KeyHeight = 44.dp
private val PredictionHeight = 36.dp
private val KeyGap = 4.dp

/** Row slots reserved for focus-wrap requesters (Persian needs 6 rows incl. the function row). */
private const val MAX_ROWS = 8

/** Requests focus; ignores "not attached yet / already gone". */
internal fun FocusRequester.tryRequestFocus() {
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        // Target not in the composition (yet); nothing to focus.
    }
}

/** Redirects a D-pad [key] press to [target] (focus wrap). Consumes the key down and up. */
private fun Modifier.redirectKey(key: Key, target: FocusRequester): Modifier = onPreviewKeyEvent { event ->
    if (event.key != key) return@onPreviewKeyEvent false
    if (event.type == KeyEventType.KeyDown) target.tryRequestFocus()
    true
}

/**
 * The keyboard panel (start side of the search screen): query field, the key grid of the current
 * [language] layout, and the voice / phone buttons.
 *
 * D-pad: all keys are ordinary focus targets (geometric search). On top of that, focus **wraps**:
 * past the screen-side edge of a row to its other end, up from the top row to the bottom buttons
 * and down from the bottom back to the top, and (only when the results panel has nothing to
 * focus, [wrapLeftEdge]) past the results-side edge as well, so the cursor never hits a dead end.
 *
 * Keys keep their Compose identity across layouts (ids are "row.column"), so focus stays put when
 * the switch key is pressed.
 */
@Composable
internal fun KeyboardPanel(
    query: String,
    language: KeyboardLanguage,
    onAction: (KeyAction) -> Unit,
    voiceAvailable: Boolean,
    onVoice: () -> Unit,
    micFocus: FocusRequester,
    phoneAvailable: Boolean,
    onPhone: () -> Unit,
    wrapLeftEdge: Boolean,
    initialFocus: FocusRequester,
    predictions: List<String>,
    onPrediction: (String) -> Unit,
    initialKeyId: String,
    onKeyFocused: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = remember(language) { KeyboardLayouts.of(language) }
    // The key that gets the initial focus: the one focused last (kept by the ViewModel), if this layout has it.
    val focusKeyId = remember(layout, initialKeyId) {
        if (layout.rows.any { row -> row.any { it.id == initialKeyId } }) initialKeyId else SearchViewModel.DEFAULT_KEY_ID
    }
    val hasPredictions = predictions.isNotEmpty()
    // Physical right / left edge keys of each row, for wrapping.
    val rightEdges = remember { List(MAX_ROWS) { FocusRequester() } }
    val leftEdges = remember { List(MAX_ROWS) { FocusRequester() } }
    val phoneFocus = remember { FocusRequester() }
    val hasButtons = voiceAvailable || phoneAvailable
    val lastRow = layout.rows.lastIndex

    Column(
        modifier = modifier
            .background(BolbolColors.Surface, PanelShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        QueryField(query = query, language = language)
        PredictionRow(
            predictions = predictions,
            onPrediction = onPrediction,
            upTarget = if (voiceAvailable) micFocus else if (phoneAvailable) phoneFocus else rightEdges[lastRow],
        )

        // Keys are laid out left-to-right like every Persian keyboard (ض at the left of the first letter
        // row, ۱ at the left of the digits), even though the screen itself is right-to-left.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            layout.rows.forEachIndexed { r, keys ->
                key(if (r == lastRow) "function" else "row$r") {
                    // Top row: UP wraps to the bottom buttons (or the function row when there are none).
                    val upTarget = when {
                        r != 0 || hasPredictions -> null
                        voiceAvailable -> micFocus
                        phoneAvailable -> phoneFocus
                        else -> rightEdges[lastRow]
                    }
                    // Bottom-most key row (when there are no buttons): DOWN wraps to the top row.
                    val downTarget = if (!hasButtons && r == lastRow) rightEdges[0] else null
                    KeyRow(
                        keys = keys,
                        onAction = onAction,
                        focusKeyId = focusKeyId,
                        onKeyFocused = onKeyFocused,
                        rightEdge = rightEdges[r],
                        leftEdge = leftEdges[r],
                        wrapLeftEdge = wrapLeftEdge,
                        initialFocus = initialFocus,
                        upTarget = upTarget,
                        downTarget = downTarget,
                    )
                }
            }
        }

        if (hasButtons) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().redirectKey(Key.DirectionDown, rightEdges[0]),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (voiceAvailable) {
                    TvButton(
                        text = SearchStrings.Voice,
                        onClick = onVoice,
                        modifier = Modifier.weight(1f).focusRequester(micFocus),
                        icon = SearchIcons.Mic,
                        style = TvButtonStyle.Secondary,
                    )
                }
                if (phoneAvailable) {
                    TvButton(
                        text = SearchStrings.Phone,
                        onClick = onPhone,
                        modifier = Modifier.weight(1f).focusRequester(phoneFocus),
                        icon = SearchIcons.Phone,
                        style = TvButtonStyle.Secondary,
                    )
                }
            }
        }
    }
}

/** The text field-like display of the query with a (static, non-blinking) caret. */
@Composable
private fun QueryField(query: String, language: KeyboardLanguage, modifier: Modifier = Modifier) {
    // Latin text reads left-to-right with the caret on its right; Persian the other way round.
    // The group itself always sits at the start (right) edge, like a Persian text field.
    val direction =
        if (query.isEmpty() || isRtlQuery(query, language)) LayoutDirection.Rtl else LayoutDirection.Ltr
    val shape = RoundedCornerShape(BolbolSpacing.CardRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(BolbolColors.Background, shape)
            .border(1.dp, BolbolColors.Outline, shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (query.isEmpty()) {
                    Caret()
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = SearchStrings.QueryPlaceholder,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Rtl),
                        color = BolbolColors.TextSecondary,
                        maxLines = 1,
                    )
                } else {
                    Text(
                        text = query,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall.copy(
                            textDirection = if (direction == LayoutDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr,
                        ),
                        color = BolbolColors.TextPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                    Spacer(Modifier.width(2.dp))
                    Caret()
                }
            }
        }
    }
}

@Composable
private fun Caret() {
    Box(Modifier.size(width = 2.dp, height = 22.dp).background(BolbolColors.Accent))
}

@Composable
private fun KeyRow(
    keys: List<KeyboardKey>,
    onAction: (KeyAction) -> Unit,
    focusKeyId: String,
    onKeyFocused: (String) -> Unit,
    rightEdge: FocusRequester,
    leftEdge: FocusRequester,
    wrapLeftEdge: Boolean,
    initialFocus: FocusRequester,
    upTarget: FocusRequester?,
    downTarget: FocusRequester?,
) {
    // Rows are always laid out left-to-right (see KeyboardPanel): keys[0] is the physical left edge.
    val rightIndex = keys.lastIndex
    val leftIndex = 0
    var rowModifier = Modifier.fillMaxWidth()
    if (upTarget != null) rowModifier = rowModifier.redirectKey(Key.DirectionUp, upTarget)
    if (downTarget != null) rowModifier = rowModifier.redirectKey(Key.DirectionDown, downTarget)
    Row(modifier = rowModifier, horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
        keys.forEachIndexed { index, k ->
            key(k.id) {
                var keyModifier: Modifier = Modifier.weight(k.weight)
                if (index == rightIndex) {
                    // Past the right edge: wrap to the other end of the row.
                    keyModifier = keyModifier.focusRequester(rightEdge).redirectKey(Key.DirectionRight, leftEdge)
                }
                if (index == leftIndex) {
                    keyModifier = keyModifier.focusRequester(leftEdge)
                    // Past the left edge: normally the D-pad leaves to the results; wrap only if there are none.
                    if (wrapLeftEdge) keyModifier = keyModifier.redirectKey(Key.DirectionLeft, rightEdge)
                }
                // The key focused last (or the default one) takes the initial focus.
                if (k.id == focusKeyId) keyModifier = keyModifier.focusRequester(initialFocus)
                KeyCap(key = k, onAction = onAction, onFocused = onKeyFocused, modifier = keyModifier)
            }
        }
    }
}

@Composable
private fun KeyCap(key: KeyboardKey, onAction: (KeyAction) -> Unit, onFocused: (String) -> Unit, modifier: Modifier) {
    val function = key.kind == KeyKind.Function
    val longPress = key.longPress
    Surface(
        onClick = { onAction(key.action) },
        modifier = modifier.height(KeyHeight).onFocusChanged { if (it.isFocused) onFocused(key.id) },
        onLongClick = if (longPress != null) ({ onAction(longPress) }) else null,
        shape = ClickableSurfaceDefaults.shape(KeyShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (function) BolbolColors.ProgressTrack else BolbolColors.SurfaceHigh,
            contentColor = if (key.action == KeyAction.SwitchLanguage) BolbolColors.Accent else BolbolColors.TextPrimary,
            focusedContainerColor = BolbolColors.TextPrimary,
            focusedContentColor = BolbolColors.Background,
            pressedContainerColor = BolbolColors.Accent,
            pressedContentColor = BolbolColors.OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = BolbolSpacing.FOCUS_SCALE),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing), shape = KeyShape),
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (key.action == KeyAction.Backspace) {
                Icon(imageVector = SearchIcons.Backspace, contentDescription = "حذف", modifier = Modifier.size(20.dp))
            } else {
                Text(
                    text = key.label,
                    style = if (function) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            val hint = key.hint
            if (hint != null) {
                Text(
                    text = hint,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 3.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                )
            }
        }
    }
}

/**
 * Prediction chips above the keys (phone-keyboard style), best first. The row always keeps its height so the
 * keys do not jump while the list comes and goes. UP from a chip wraps to the bottom buttons; DOWN reaches the keys.
 */
@Composable
private fun PredictionRow(
    predictions: List<String>,
    onPrediction: (String) -> Unit,
    upTarget: FocusRequester,
) {
    Box(Modifier.fillMaxWidth().height(PredictionHeight), contentAlignment = Alignment.CenterStart) {
        if (predictions.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().redirectKey(Key.DirectionUp, upTarget),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(predictions, key = { it }, contentType = { "prediction" }) { text ->
                    SelectableChip(text = text, selected = false, onClick = { onPrediction(text) })
                }
            }
        }
    }
}
