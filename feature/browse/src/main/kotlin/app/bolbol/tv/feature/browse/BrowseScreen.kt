package app.bolbol.tv.feature.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolIcons
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.ChipGroup
import app.bolbol.tv.core.designsystem.EmptyState
import app.bolbol.tv.core.designsystem.ErrorState
import app.bolbol.tv.core.designsystem.LoadingState
import app.bolbol.tv.core.designsystem.PosterCard
import app.bolbol.tv.core.designsystem.TvButton
import app.bolbol.tv.core.designsystem.TvButtonStyle
import app.bolbol.tv.core.model.TitleSummary

/**
 * Poster grid for the Movies / Series tabs and every "view all" (docs/design-system.md, screen 5).
 *
 * Header (title, "صفحه ۲ از ۸۵۹", quick-filter chips) stays fixed; below it a 5-column
 * [LazyVerticalGrid] of [PosterCard]s whose focused row is parked at the top edge. The next page
 * loads while the user nears the end ([BrowseViewModel.onItemFocused]).
 *
 * Focus: on first appearance the first poster is focused (unless the chips already hold focus,
 * e.g. after picking a chip); coming back from details the poster that was focused is restored
 * ([BrowseViewModel.lastFocusedId]) and scrolled into place. Errors focus their retry button.
 *
 * Draws its own background; apply BolbolTheme (RTL) above it.
 */
@Composable
fun BrowseScreen(
    viewModel: BrowseViewModel,
    onOpenTitle: (TitleSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Written by the chips' focus callback, read only inside effects: never recomposes anything.
    val chipsHaveFocus = remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(BolbolColors.Background)
            .padding(top = BolbolSpacing.SafeVertical),
    ) {
        BrowseHeader(
            state = state,
            onSelectFilter = viewModel::selectFilter,
            chipsHaveFocus = chipsHaveFocus,
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state.phase) {
                BrowsePhase.Loading -> LoadingState()
                BrowsePhase.Error -> ErrorState(message = state.error ?: MESSAGE_UNKNOWN, onRetry = viewModel::retry)
                BrowsePhase.Empty -> EmptyState(message = "موردی برای نمایش وجود ندارد.")
                BrowsePhase.Content -> BrowseGrid(
                    items = state.items,
                    loadingMore = state.loadingMore,
                    loadMoreError = state.loadMoreError,
                    viewModel = viewModel,
                    onOpenTitle = onOpenTitle,
                    chipsHaveFocus = chipsHaveFocus,
                )
            }
        }
    }
}

/** Header text starts where the first poster's edge is (safe margin + half the slack of its grid cell). */
private val HeaderInset = BolbolSpacing.SafeHorizontal + 16.dp

/** Room above the first row and below the last for the 1.08x focus scale and the focus border. */
private val GridEdgePadding = 12.dp

@Composable
private fun BrowseHeader(
    state: BrowseUiState,
    onSelectFilter: (BrowseFilter) -> Unit,
    chipsHaveFocus: MutableState<Boolean>,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = HeaderInset, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            BolbolText(
                text = state.title,
                style = MaterialTheme.typography.headlineSmall,
                color = BolbolColors.TextPrimary,
                maxLines = 1,
            )
            BolbolText(
                text = pageCountLabel(state.phase, state.page, state.totalPages, state.items.size),
                modifier = Modifier.heightIn(min = 18.dp),
                style = MaterialTheme.typography.labelMedium,
                color = BolbolColors.TextSecondary,
                maxLines = 1,
            )
        }
        if (state.filters.isNotEmpty()) {
            Spacer(Modifier.width(32.dp))
            val labels = remember(state.filters) { state.filters.map { it.label } }
            ChipGroup(
                options = labels,
                selectedIndex = state.filters.indexOf(state.selected),
                onSelect = { index -> onSelectFilter(state.filters[index]) },
                modifier = Modifier.onFocusChanged { chipsHaveFocus.value = it.hasFocus },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseGrid(
    items: List<TitleSummary>,
    loadingMore: Boolean,
    loadMoreError: String?,
    viewModel: BrowseViewModel,
    onOpenTitle: (TitleSummary) -> Unit,
    chipsHaveFocus: State<Boolean>,
) {
    // Decided once, when the grid enters the composition: restore the poster that had focus before
    // we went to details, else start on the first one.
    val restoreIndex = remember {
        val id = viewModel.lastFocusedId
        if (id == null) -1 else items.indexOfFirst { it.id == id }
    }
    val targetIndex = restoreIndex.coerceAtLeast(0)
    val targetId = remember { items[targetIndex].id }
    val targetFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = targetIndex - targetIndex % GRID_COLUMNS)

    LaunchedEffect(Unit) {
        // Picking a chip reloads the grid; the chip must keep focus then.
        if (restoreIndex >= 0 || !chipsHaveFocus.value) targetFocus.requestFocusWhenAttached()
    }

    val pivot = with(LocalDensity.current) { GridEdgePadding.toPx() }
    val scrollSpec = remember(pivot) { TopPivotBringIntoViewSpec(pivot) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            contentPadding = PaddingValues(
                start = BolbolSpacing.SafeHorizontal,
                end = BolbolSpacing.SafeHorizontal,
                top = GridEdgePadding,
                bottom = BolbolSpacing.SafeVertical + GridEdgePadding,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> item.id },
                contentType = { _, _ -> "poster" },
            ) { index, item ->
                // 864 dp / 5 = 172.8 dp cells: centre each 140 dp card so the slack is shared evenly.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    PosterCard(
                        item = item,
                        onClick = { onOpenTitle(item) },
                        modifier = if (item.id == targetId) Modifier.focusRequester(targetFocus) else Modifier,
                        onFocused = { viewModel.onItemFocused(item.id, index) },
                    )
                }
            }
            if (loadingMore || loadMoreError != null) {
                item(key = FOOTER_KEY, span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
                    GridFooter(error = loadMoreError, onRetry = viewModel::loadMore)
                }
            }
        }
    }
}

private const val FOOTER_KEY = "footer"

/** End-of-grid row: static "loading" caption, or the failed-page message with a retry button. */
@Composable
private fun GridFooter(error: String?, onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (error == null) {
            BolbolText(
                text = "در حال بارگذاری…",
                style = MaterialTheme.typography.bodyMedium,
                color = BolbolColors.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BolbolText(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BolbolColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                TvButton(
                    text = "تلاش دوباره",
                    onClick = onRetry,
                    icon = BolbolIcons.Refresh,
                    style = TvButtonStyle.Secondary,
                )
            }
        }
    }
}

/**
 * Vertical counterpart of the designsystem's start-pivot spec: the focused item's top edge is
 * parked at [pivotPx] from the viewport top, so each D-pad down moves the grid one row and the
 * next row peeks in below. Lazy grids clamp at both ends, so the first/last rows stay in place.
 */
@OptIn(ExperimentalFoundationApi::class)
private class TopPivotBringIntoViewSpec(private val pivotPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - pivotPx
}

/**
 * Requests focus once the target is attached: right after the grid first composes the item may
 * not be laid out yet (requestFocus then throws), so retry over the next few frames.
 */
private suspend fun FocusRequester.requestFocusWhenAttached() {
    repeat(FOCUS_ATTEMPTS) {
        try {
            requestFocus()
            return
        } catch (_: IllegalStateException) {
            withFrameNanos { }
        }
    }
}

private const val FOCUS_ATTEMPTS = 5
