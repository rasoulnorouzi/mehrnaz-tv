package app.bolbol.tv.core.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

/** Defaults of [ContentRow]. */
object ContentRowDefaults {
    /**
     * Where the focused item's start edge is parked, as a fraction of the row width. 0.1 of a
     * 960 dp row = 96 dp = the 48 dp safe margin plus a 48 dp peek of the previous item.
     */
    const val PivotFraction = 0.1f

    /**
     * Room above/below the items so the 1.08x focus scale and the border are not clipped by the
     * LazyRow (a 210 dp poster grows by ~8 dp on each side).
     */
    val VerticalPadding = 12.dp

    val ContentPadding = PaddingValues(
        horizontal = BolbolSpacing.SafeHorizontal,
        vertical = VerticalPadding,
    )
}

/**
 * Keeps the focused child near the **start** edge of the scrollable instead of centring it:
 * the child's start edge goes to `pivotFraction * containerSize`. Lazy lists clamp at both ends,
 * so the first items stay in place and the last ones stay at the end.
 *
 * `calculateScrollDistance` receives physical, left-based coordinates (offset = child's left
 * edge from the container's left edge) and must return how far the content has to move left
 * (positive) or right (negative). In RTL the start edge is the right edge, so the target is
 * measured from the right.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class StartPivotBringIntoViewSpec(
    private val rtl: Boolean,
    private val pivotFraction: Float,
) : BringIntoViewSpec {

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val pivot = pivotFraction * containerSize
        return if (rtl) {
            // gap between the child's right edge and the container's right edge
            val gapFromStart = containerSize - (offset + size)
            pivot - gapFromStart
        } else {
            offset - pivot
        }
    }
}

/** Provides [StartPivotBringIntoViewSpec] to everything inside [content] (e.g. a LazyRow). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StartPivotBringIntoView(
    pivotFraction: Float = ContentRowDefaults.PivotFraction,
    content: @Composable () -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val spec = remember(rtl, pivotFraction) { StartPivotBringIntoViewSpec(rtl, pivotFraction) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Titled horizontal row (`Title 18 sp` header + [LazyRow]) for posters, wide cards, people...
 *
 * - Flows start to end (right to left in RTL), content padding = the 48 dp safe margin, items
 *   spaced by [BolbolSpacing.ItemGap].
 * - The focused item is kept near the start edge ([StartPivotBringIntoView]).
 * - `focusRestorer()`: when focus leaves the row and comes back (D-pad up/down, or Back), it
 *   returns to the item that had it, not item 0.
 * - Add items with the usual `item {}` / `items(list, key = ..., contentType = ...) {}`; give every
 *   item a stable `key` and a `contentType`. Hoist [state] to survive navigation.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContentRow(
    title: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = ContentRowDefaults.ContentPadding,
    itemSpacing: Dp = BolbolSpacing.ItemGap,
    pivotFraction: Float = ContentRowDefaults.PivotFraction,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier) {
        BolbolText(
            text = title,
            modifier = Modifier.padding(horizontal = BolbolSpacing.SafeHorizontal),
            style = MaterialTheme.typography.titleMedium,
            color = BolbolColors.TextPrimary,
            maxLines = 1,
        )
        StartPivotBringIntoView(pivotFraction) {
            LazyRow(
                state = state,
                modifier = Modifier.focusRestorer(),
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                content = content,
            )
        }
    }
}

/**
 * Typed [ContentRow]: one item composable per element of [items].
 * [key] must be unique and stable (a Long/String id, never the index of a mutable list);
 * [contentType] lets Compose reuse item compositions of the same kind.
 */
@Composable
fun <T> ContentRow(
    title: String,
    items: List<T>,
    key: (item: T) -> Any,
    modifier: Modifier = Modifier,
    contentType: (item: T) -> Any? = { "item" },
    state: LazyListState = rememberLazyListState(),
    itemContent: @Composable (item: T) -> Unit,
) {
    ContentRow(title = title, modifier = modifier, state = state) {
        items(items = items, key = key, contentType = contentType) { item -> itemContent(item) }
    }
}
