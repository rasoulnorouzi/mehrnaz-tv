package app.bolbol.tv.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolIcons
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.EmptyState
import app.bolbol.tv.core.designsystem.LoadingState
import app.bolbol.tv.core.designsystem.PosterCard
import app.bolbol.tv.core.designsystem.PosterCardDefaults
import app.bolbol.tv.core.designsystem.SelectableChip
import app.bolbol.tv.core.designsystem.TvButton
import app.bolbol.tv.core.model.TitleSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Grid columns. 4 x 140 dp posters would leave the keyboard under 240 dp on the 960 dp canvas,
 * so the results use 2 columns at full poster size and the keyboard gets ~50 % of the width (v1.4-5: bigger keys).
 */
internal const val GRID_COLUMNS = 2

private val GridGap = 14.dp
private val GridPadding = 12.dp

/** Exact width of [GRID_COLUMNS] posters plus gaps and padding (so the keyboard takes the rest). */
internal val ResultsWidth = PosterCardDefaults.Width * GRID_COLUMNS + GridGap * (GRID_COLUMNS - 1) + GridPadding * 2

/**
 * End side of the search screen: recent searches + hint (query too short), loading, the results
 * grid, "no results" and an error with retry. Takes plain values (not the whole UI state) so typing
 * in the query field does not recompose it.
 */
@Composable
internal fun ResultsPanel(
    phase: SearchPhase,
    items: List<TitleSummary>,
    loadingMore: Boolean,
    generation: Int,
    recents: List<String>,
    error: SearchError?,
    onOpen: (TitleSummary) -> Unit,
    onFocusedIndex: (Int) -> Unit,
    onRecent: (String) -> Unit,
    onRetry: () -> Unit,
    restoreId: Long?,
    onRestoreDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when (phase) {
            SearchPhase.Idle -> IdlePanel(recents = recents, onRecent = onRecent)
            SearchPhase.Loading ->
                if (items.isEmpty()) {
                    LoadingState(message = SearchStrings.Searching)
                } else {
                    // Keep the previous results on screen until the new ones arrive (no flicker while typing).
                    ResultsGrid(items, loadingMore, generation, onOpen, onFocusedIndex, restoreId, onRestoreDone)
                }

            SearchPhase.Results ->
                ResultsGrid(items, loadingMore, generation, onOpen, onFocusedIndex, restoreId, onRestoreDone)

            SearchPhase.Empty -> EmptyState(message = SearchStrings.NoResults)
            SearchPhase.Error -> SearchErrorPanel(error = error, onRetry = onRetry)
        }
    }
}

/** Query shorter than 2 characters: last searches as chips, and a hint. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdlePanel(recents: List<String>, onRecent: (String) -> Unit) {
    if (recents.isEmpty()) {
        EmptyState(message = SearchStrings.Hint + "\n" + SearchStrings.HintVoice)
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        BolbolText(
            text = SearchStrings.Recents,
            style = MaterialTheme.typography.titleMedium,
            color = BolbolColors.TextPrimary,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recents.forEach { query ->
                key(query) {
                    SelectableChip(text = query, selected = false, onClick = { onRecent(query) })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        BolbolText(
            text = SearchStrings.Hint,
            style = MaterialTheme.typography.bodyMedium,
            color = BolbolColors.TextSecondary,
        )
    }
}

@Composable
private fun ResultsGrid(
    items: List<TitleSummary>,
    loadingMore: Boolean,
    generation: Int,
    onOpen: (TitleSummary) -> Unit,
    onFocusedIndex: (Int) -> Unit,
    restoreId: Long?,
    onRestoreDone: () -> Unit,
) {
    // A new result set recreates the grid, so it starts at the top.
    key(generation) {
        val gridState = rememberLazyGridState()
        val restoreFocus = remember { FocusRequester() }

        if (restoreId != null) {
            // Coming back from the details screen: scroll to the title that was opened and focus it.
            LaunchedEffect(restoreId) {
                try {
                    val index = items.indexOfFirst { it.id == restoreId }
                    if (index >= 0) {
                        gridState.scrollToItem(index)
                        withTimeoutOrNull(1_000) {
                            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.any { it.key == restoreId } }
                                .first { it }
                        }
                        withFrameNanos { }
                        restoreFocus.tryRequestFocus()
                    }
                } finally {
                    onRestoreDone()
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = GridPadding, vertical = GridPadding),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> item.id },
                contentType = { _, _ -> "poster" },
            ) { index, item ->
                PosterCard(
                    item = item,
                    onClick = { onOpen(item) },
                    modifier = if (item.id == restoreId) Modifier.focusRequester(restoreFocus) else Modifier,
                    onFocused = { onFocusedIndex(index) },
                )
            }
            if (loadingMore) {
                item(key = "loading-more", span = { GridItemSpan(maxLineSpan) }, contentType = "loading") {
                    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                        BolbolText(
                            text = SearchStrings.LoadingMore,
                            style = MaterialTheme.typography.bodySmall,
                            color = BolbolColors.TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Same look as core:designsystem's `ErrorState`, but **without** grabbing focus: an error can show up
 * while the user is still typing, and focus must stay on the keyboard. The retry button is reached
 * with the D-pad (or RIGHT from the keyboard's results-side edge).
 */
@Composable
private fun SearchErrorPanel(error: SearchError?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(48.dp).background(BolbolColors.Error, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BolbolText(
                text = "!",
                style = MaterialTheme.typography.headlineSmall,
                color = BolbolColors.Background,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        BolbolText(
            text = (error ?: SearchError.Unknown).message,
            style = MaterialTheme.typography.titleMedium,
            color = BolbolColors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 4,
        )
        Spacer(Modifier.height(20.dp))
        TvButton(text = "تلاش دوباره", onClick = onRetry, icon = BolbolIcons.Refresh)
    }
}
