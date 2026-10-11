package app.bolbol.tv.feature.home

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import app.bolbol.tv.core.designsystem.AudioBadge
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.ContentRow
import app.bolbol.tv.core.designsystem.ErrorState
import app.bolbol.tv.core.designsystem.HeroBackdrop
import app.bolbol.tv.core.designsystem.LoadingState
import app.bolbol.tv.core.designsystem.MetaLine
import app.bolbol.tv.core.designsystem.PosterCard
import app.bolbol.tv.core.designsystem.TopBar
import app.bolbol.tv.core.designsystem.WideCard
import app.bolbol.tv.core.designsystem.formatImdb
import app.bolbol.tv.core.designsystem.rememberDebouncedValue
import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.TitleSummary
import kotlinx.coroutines.launch

/** Share of the screen height taken by the hero (backdrop, top bar overlay, title and meta). */
private const val HERO_FRACTION = 0.44f

/** The top bar's selected tab: خانه. */
private const val HOME_TAB_INDEX = 1

/** What each top bar tab does, in the order of `R.array.home_tabs`; null = this screen. */
private val TabDestinations: List<HomeDestination?> = listOf(
    HomeDestination.SEARCH,
    null,
    HomeDestination.MOVIES,
    HomeDestination.SERIES,
    HomeDestination.SETTINGS,
)

/**
 * Home: top bar, a fixed-height hero that follows the focused card (debounced), and lazy rows
 * (Continue Watching first). Paints cached rows instantly; the ViewModel refreshes silently.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTitle: (TitleSummary) -> Unit,
    onResume: (ContinueWatchingItem) -> Unit,
    onNavigate: (HomeDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize().background(BolbolColors.Background)) {
        when (val current = state) {
            HomeUiState.Loading -> HomeMessageLayout(onNavigate) { LoadingState() }
            is HomeUiState.Error -> HomeMessageLayout(onNavigate) {
                ErrorState(
                    message = stringResource(current.error.messageRes()),
                    onRetry = { viewModel.retry() },
                )
            }
            is HomeUiState.Content -> {
                // Resolved once, when the rows first appear: the remembered card (Back from details) or the first one.
                val initial = remember { current.rows.resolveInitialFocus(viewModel.lastFocus) }
                if (initial != null) {
                    HomeContent(
                        rows = current.rows,
                        initial = initial,
                        onCardFocused = { rowId, titleId -> viewModel.onCardFocused(rowId, titleId) },
                        onOpenTitle = onOpenTitle,
                        onResume = onResume,
                        onNavigate = onNavigate,
                    )
                }
            }
        }
    }
}

@StringRes
private fun HomeError.messageRes(): Int = when (this) {
    HomeError.OFFLINE -> R.string.home_error_offline
    HomeError.SERVER_DOWN -> R.string.home_error_server_down
    HomeError.GENERIC -> R.string.home_error_generic
}

/** Top bar above a loading or error placeholder (the bar stays reachable, e.g. to open settings). */
@Composable
private fun HomeMessageLayout(onNavigate: (HomeDestination) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        HomeTopBar(onNavigate, Modifier.padding(top = BolbolSpacing.SafeVertical))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun HomeTopBar(onNavigate: (HomeDestination) -> Unit, modifier: Modifier = Modifier) {
    val labels = stringArrayResource(R.array.home_tabs).toList()
    TopBar(
        items = labels,
        selectedIndex = HOME_TAB_INDEX,
        onSelect = { index -> TabDestinations.getOrNull(index)?.let(onNavigate) },
        modifier = modifier,
    )
}

/**
 * Focus grabbed once, when the rows first appear. [pending] makes sure a card that scrolls out and
 * back in does not steal focus again.
 */
private class InitialFocusGate {
    val requester = FocusRequester()
    private var pending = true

    fun requestOnce() {
        if (pending) {
            pending = false
            requester.requestFocus()
        }
    }
}

/** Row that last had focus; a change means the list must scroll that row under the hero. */
private class FocusedRowTracker(var rowId: String)

/**
 * The rows' LazyColumn is moved by [androidx.compose.foundation.lazy.LazyListState.animateScrollToItem]
 * when the focused row changes, not by focus bring-into-view: this spec turns the automatic
 * vertical scroll off so the two never fight (the horizontal LazyRows have their own spec).
 */
@OptIn(ExperimentalFoundationApi::class)
private object NoAutoScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeContent(
    rows: List<HomeRow>,
    initial: InitialFocus,
    onCardFocused: (rowId: String, titleId: Long) -> Unit,
    onOpenTitle: (TitleSummary) -> Unit,
    onResume: (ContinueWatchingItem) -> Unit,
    onNavigate: (HomeDestination) -> Unit,
) {
    // Written on every focus change, read only by HeroSection: rows never recompose because of it.
    val heroTitle = remember { mutableStateOf<TitleSummary?>(initial.title) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial.rowIndex)
    val scope = rememberCoroutineScope()
    val currentRows by rememberUpdatedState(rows)
    val currentOnCardFocused by rememberUpdatedState(onCardFocused)
    val tracker = remember { FocusedRowTracker(initial.rowId) }
    val gate = remember { InitialFocusGate() }

    val onFocused = remember(listState, scope, tracker) {
        { rowId: String, title: TitleSummary ->
            heroTitle.value = title
            currentOnCardFocused(rowId, title.id)
            if (tracker.rowId != rowId) {
                tracker.rowId = rowId
                val index = currentRows.indexOfFirst { it.id == rowId }
                if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(HERO_FRACTION)) {
            HeroSection(heroTitle, Modifier.fillMaxSize())
            HomeTopBar(onNavigate, Modifier.padding(top = BolbolSpacing.SafeVertical))
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScrollSpec) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(
                    items = rows,
                    key = { it.id },
                    contentType = { row ->
                        when (row) {
                            is HomeRow.ContinueWatching -> CONTENT_TYPE_WIDE_ROW
                            is HomeRow.Titles -> CONTENT_TYPE_POSTER_ROW
                        }
                    },
                ) { row ->
                    HomeRowItem(
                        row = row,
                        initial = initial,
                        gate = gate,
                        onFocused = onFocused,
                        onOpenTitle = onOpenTitle,
                        onResume = onResume,
                    )
                }
            }
        }
    }
}

private const val CONTENT_TYPE_WIDE_ROW = "wide_row"
private const val CONTENT_TYPE_POSTER_ROW = "poster_row"

@Composable
private fun HomeRowItem(
    row: HomeRow,
    initial: InitialFocus,
    gate: InitialFocusGate,
    onFocused: (rowId: String, title: TitleSummary) -> Unit,
    onOpenTitle: (TitleSummary) -> Unit,
    onResume: (ContinueWatchingItem) -> Unit,
) {
    // Only the row focus restoration points at starts scrolled to the remembered card.
    val rowState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (row.id == initial.rowId) initial.itemIndex else 0,
    )
    when (row) {
        is HomeRow.ContinueWatching -> ContentRow(
            title = stringResource(R.string.home_continue_watching),
            items = row.items,
            key = { it.title.id },
            contentType = { CONTENT_TYPE_WIDE_CARD },
            state = rowState,
        ) { item ->
            val isInitial = row.id == initial.rowId && item.title.id == initial.title.id
            if (isInitial) LaunchedEffect(Unit) { gate.requestOnce() }
            WideCard(
                item = item,
                onClick = { onResume(item) },
                modifier = if (isInitial) Modifier.focusRequester(gate.requester) else Modifier,
                onFocused = { onFocused(row.id, item.title) },
            )
        }

        is HomeRow.Titles -> ContentRow(
            title = row.title,
            items = row.items,
            key = { it.id },
            contentType = { CONTENT_TYPE_POSTER_CARD },
            state = rowState,
        ) { item ->
            val isInitial = row.id == initial.rowId && item.id == initial.title.id
            if (isInitial) LaunchedEffect(Unit) { gate.requestOnce() }
            PosterCard(
                item = item,
                onClick = { onOpenTitle(item) },
                modifier = if (isInitial) Modifier.focusRequester(gate.requester) else Modifier,
                onFocused = { onFocused(row.id, item) },
            )
        }
    }
}

private const val CONTENT_TYPE_WIDE_CARD = "wide_card"
private const val CONTENT_TYPE_POSTER_CARD = "poster_card"

/**
 * Backdrop + title + meta of the focused card. The title and the backdrop both go through the same
 * 300 ms debounce, so fast D-pad scrolling changes the hero once. The area is a fixed fraction of
 * the screen, so nothing below it moves when the text changes.
 */
@Composable
private fun HeroSection(focusedTitle: State<TitleSummary?>, modifier: Modifier = Modifier) {
    val shown = rememberDebouncedValue(focusedTitle.value)
    HeroBackdrop(imageUrl = shown?.backdropUrl, modifier = modifier) {
        if (shown != null) {
            HeroText(
                title = shown,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = BolbolSpacing.SafeHorizontal, end = BolbolSpacing.SafeHorizontal, bottom = 14.dp),
            )
        }
    }
}

@Composable
private fun HeroText(title: TitleSummary, modifier: Modifier = Modifier) {
    val imdb = title.imdbRating?.takeIf { it > 0f }
    val metaParts = listOf(
        title.year?.toString().orEmpty(),
        if (imdb != null) stringResource(R.string.home_imdb, formatImdb(imdb)) else "",
    )
    Column(
        modifier = modifier.widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BolbolText(
            text = title.faTitle.ifBlank { title.title },
            style = MaterialTheme.typography.displaySmall,
            color = BolbolColors.TextPrimary,
            maxLines = 2,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaLine(parts = metaParts, color = BolbolColors.TextSecondary)
            AudioBadge(dubbed = title.hasDub, subtitled = title.hasSubtitle)
        }
    }
}
