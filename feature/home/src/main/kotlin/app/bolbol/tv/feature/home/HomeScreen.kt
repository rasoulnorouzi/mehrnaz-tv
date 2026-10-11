package app.bolbol.tv.feature.home

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
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
import app.bolbol.tv.core.designsystem.PosterCardDefaults
import app.bolbol.tv.core.designsystem.TopBar
import app.bolbol.tv.core.designsystem.WideCard
import app.bolbol.tv.core.designsystem.formatEpisodeFa
import app.bolbol.tv.core.designsystem.formatImdb
import app.bolbol.tv.core.designsystem.formatRemainingFa
import app.bolbol.tv.core.designsystem.rememberDebouncedValue
import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.ListQuery
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
 *
 * - Every server row that has a `viewAll` query ends with a "مشاهده همه" card that calls [onViewAll]
 *   with that query and the row's title.
 * - Long-pressing OK (or MENU) on a Continue Watching card opens a menu: resume, mark as watched,
 *   remove from the row, cancel.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTitle: (TitleSummary) -> Unit,
    onResume: (ContinueWatchingItem) -> Unit,
    onNavigate: (HomeDestination) -> Unit,
    modifier: Modifier = Modifier,
    onViewAll: (ListQuery, title: String) -> Unit = { _, _ -> },
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
                        onViewAll = onViewAll,
                        onMarkWatched = { viewModel.markWatched(it) },
                        onRemove = { viewModel.removeFromContinueWatching(it) },
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

/** A request to put focus on one card. [token] makes a repeated request for the same card a new one. */
private data class FocusRequestToken(val target: FocusTarget, val token: Int)

/**
 * Programmatic focus: once when the rows first appear, and again after the Continue Watching menu
 * closes (back on the same card, or on its neighbour when the card is gone). Exactly one card owns
 * [requester] (the one named by [request]) and asks for focus from its own effect, so the requester
 * is attached by construction. A request is dropped as soon as the user focuses anything
 * ([onUserFocus]), so a card that scrolls into view later never steals focus.
 */
@Stable
private class FocusGate(initial: FocusTarget) {
    val requester = FocusRequester()
    var request by mutableStateOf(FocusRequestToken(initial, 0))
        private set
    private var performed = -1

    fun moveTo(target: FocusTarget) {
        request = FocusRequestToken(target, request.token + 1)
    }

    fun owns(rowId: String, titleId: Long): Boolean =
        request.target.rowId == rowId && request.target.titleId == titleId

    fun perform(token: FocusRequestToken) {
        if (performed < token.token) {
            performed = token.token
            requester.requestFocus()
        }
    }

    fun onUserFocus() {
        performed = request.token
    }
}

/** Row that last had focus; a change means the list must scroll that row under the hero. */
private class FocusedRowTracker(var rowId: String)

/** The Continue Watching card whose menu is open, and whether OK is still held down from opening it. */
private data class OpenMenu(val item: ContinueWatchingItem, val heldSelect: Boolean)

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
    onViewAll: (ListQuery, String) -> Unit,
    onMarkWatched: (ContinueWatchingItem) -> Unit,
    onRemove: (ContinueWatchingItem) -> Unit,
    onNavigate: (HomeDestination) -> Unit,
) {
    // Written on every focus change, read only by HeroSection: rows never recompose because of it.
    val heroTitle = remember { mutableStateOf<TitleSummary?>(initial.title) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial.rowIndex)
    val scope = rememberCoroutineScope()
    val currentRows by rememberUpdatedState(rows)
    val currentOnCardFocused by rememberUpdatedState(onCardFocused)
    val tracker = remember { FocusedRowTracker(initial.rowId) }
    val gate = remember { FocusGate(FocusTarget(initial.rowId, initial.title.id)) }
    var menu by remember { mutableStateOf<OpenMenu?>(null) }

    val scrollToRow = remember(listState, scope, tracker) {
        { rowId: String ->
            if (tracker.rowId != rowId) {
                tracker.rowId = rowId
                val index = currentRows.indexOfFirst { it.id == rowId }
                if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
            }
        }
    }
    val onFocused = remember(scrollToRow, gate) {
        { rowId: String, title: TitleSummary ->
            heroTitle.value = title
            currentOnCardFocused(rowId, title.id)
            gate.onUserFocus()
            scrollToRow(rowId)
        }
    }
    // The "view all" card is not a title: the hero keeps the row's last title, only the scroll follows.
    val onViewAllFocused = remember(scrollToRow, gate) {
        { rowId: String ->
            gate.onUserFocus()
            scrollToRow(rowId)
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                            onViewAllFocused = onViewAllFocused,
                            onOpenTitle = onOpenTitle,
                            onResume = onResume,
                            onViewAll = onViewAll,
                            onOpenMenu = { item, trigger ->
                                menu = OpenMenu(item, heldSelect = trigger == ActionTrigger.LONG_PRESS)
                            },
                        )
                    }
                }
            }
        }

        menu?.let { open ->
            val item = open.item
            val cardFocus = FocusTarget(HomeRow.CONTINUE_ROW_ID, item.title.id)
            ContinueMenu(
                title = item.title.faTitle.ifBlank { item.title.title },
                openedByHold = open.heldSelect,
                onChoose = { action ->
                    menu = null
                    when (action) {
                        // Leaving for the player: nothing to restore here.
                        ContinueAction.RESUME -> onResume(item)
                        ContinueAction.CANCEL -> gate.moveTo(cardFocus)
                        ContinueAction.MARK_WATCHED -> {
                            currentRows.neighbourFocus(cardFocus)?.let(gate::moveTo)
                            onMarkWatched(item)
                        }
                        ContinueAction.REMOVE -> {
                            currentRows.neighbourFocus(cardFocus)?.let(gate::moveTo)
                            onRemove(item)
                        }
                    }
                },
            )
        }
    }
}

private const val CONTENT_TYPE_WIDE_ROW = "wide_row"
private const val CONTENT_TYPE_POSTER_ROW = "poster_row"

@Composable
private fun HomeRowItem(
    row: HomeRow,
    initial: InitialFocus,
    gate: FocusGate,
    onFocused: (rowId: String, title: TitleSummary) -> Unit,
    onViewAllFocused: (rowId: String) -> Unit,
    onOpenTitle: (TitleSummary) -> Unit,
    onResume: (ContinueWatchingItem) -> Unit,
    onViewAll: (ListQuery, String) -> Unit,
    onOpenMenu: (ContinueWatchingItem, ActionTrigger) -> Unit,
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
            val owner = gate.owns(row.id, item.title.id)
            if (owner) {
                val request = gate.request
                LaunchedEffect(request) { gate.perform(request) }
            }
            ContinueCard(
                item = item,
                onClick = { onResume(item) },
                modifier = Modifier
                    .longPressOrMenu { trigger -> onOpenMenu(item, trigger) }
                    .then(if (owner) Modifier.focusRequester(gate.requester) else Modifier),
                onFocused = { onFocused(row.id, item.title) },
            )
        }

        is HomeRow.Titles -> ContentRow(title = row.title, state = rowState) {
            items(items = row.items, key = { it.id }, contentType = { CONTENT_TYPE_POSTER_CARD }) { item ->
                val owner = gate.owns(row.id, item.id)
                if (owner) {
                    val request = gate.request
                    LaunchedEffect(request) { gate.perform(request) }
                }
                PosterCard(
                    item = item,
                    onClick = { onOpenTitle(item) },
                    modifier = if (owner) Modifier.focusRequester(gate.requester) else Modifier,
                    onFocused = { onFocused(row.id, item) },
                )
            }
            val viewAll = row.viewAll
            if (viewAll != null) {
                item(key = VIEW_ALL_KEY, contentType = CONTENT_TYPE_VIEW_ALL) {
                    ViewAllCard(
                        onClick = { onViewAll(viewAll, row.title) },
                        onFocused = { onViewAllFocused(row.id) },
                    )
                }
            }
        }
    }
}

private const val CONTENT_TYPE_WIDE_CARD = "wide_card"
private const val CONTENT_TYPE_POSTER_CARD = "poster_card"
private const val CONTENT_TYPE_VIEW_ALL = "view_all_card"
private const val VIEW_ALL_KEY = "view_all"

/**
 * [WideCard] for a Continue Watching entry, same texts as `WideCard(item)` but with a progress bar
 * that is always visible once the title was started ([visibleProgress]).
 */
@Composable
private fun ContinueCard(
    item: ContinueWatchingItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
) {
    val progress = item.progress
    val key = progress.key
    val subtitle = remember(key, progress.positionMs, progress.durationMs) {
        val remaining = formatRemainingFa(progress.remainingMs)
        if (key.isEpisode) {
            formatEpisodeFa(key.season, key.episode, includeSeason = key.season > 1) + " · " + remaining
        } else {
            remaining
        }
    }
    WideCard(
        title = item.title.faTitle.ifBlank { item.title.title },
        imageUrl = item.title.backdropUrl ?: item.title.posterUrl,
        onClick = onClick,
        modifier = modifier,
        subtitle = subtitle,
        progress = visibleProgress(progress.fraction),
        onFocused = onFocused,
    )
}

private val ViewAllShape = RoundedCornerShape(BolbolSpacing.CardRadius)

/**
 * The last card of a server row: poster-sized, an arrow and "مشاهده همه". It is not a title, so
 * it does not drive the hero; [onFocused] only lets the list scroll its row into place.
 */
@Composable
private fun ViewAllCard(onClick: () -> Unit, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.home_view_all)
    val currentOnFocused by rememberUpdatedState(onFocused)
    Column(modifier.width(PosterCardDefaults.Width)) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .size(PosterCardDefaults.Width, PosterCardDefaults.Height)
                .onFocusChanged { if (it.isFocused) currentOnFocused() }
                .semantics { contentDescription = label },
            shape = ClickableSurfaceDefaults.shape(ViewAllShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = BolbolColors.Surface,
                contentColor = BolbolColors.TextPrimary,
                focusedContainerColor = BolbolColors.SurfaceHigh,
                focusedContentColor = BolbolColors.TextPrimary,
                pressedContainerColor = BolbolColors.SurfaceHigh,
                pressedContentColor = BolbolColors.TextPrimary,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = BolbolSpacing.FOCUS_SCALE),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing), shape = ViewAllShape),
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = ArrowOnwards,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = BolbolColors.Accent,
                )
                Spacer(Modifier.height(10.dp))
                BolbolText(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.height(PosterCardDefaults.CaptionHeight))
    }
}

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
