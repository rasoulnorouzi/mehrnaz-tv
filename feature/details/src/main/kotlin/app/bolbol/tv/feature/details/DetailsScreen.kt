package app.bolbol.tv.feature.details

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import app.bolbol.tv.core.designsystem.BolbolIcons
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.ChipGroup
import app.bolbol.tv.core.designsystem.ContentRow
import app.bolbol.tv.core.designsystem.ErrorState
import app.bolbol.tv.core.designsystem.HeroBackdrop
import app.bolbol.tv.core.designsystem.LoadingState
import app.bolbol.tv.core.designsystem.MetaLine
import app.bolbol.tv.core.designsystem.PosterCard
import app.bolbol.tv.core.designsystem.ProgressLine
import app.bolbol.tv.core.designsystem.SelectableChip
import app.bolbol.tv.core.designsystem.TvButton
import app.bolbol.tv.core.designsystem.TvButtonStyle
import app.bolbol.tv.core.designsystem.formatDurationFa
import app.bolbol.tv.core.designsystem.formatEpisodeFa
import app.bolbol.tv.core.designsystem.formatImdb
import app.bolbol.tv.core.designsystem.formatRemainingFa
import app.bolbol.tv.core.designsystem.formatRuntimeFa
import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.Person
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.WatchKey
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch

/** The info / episodes block fills this much of the screen; the rest peeks the next row as a scroll cue. */
private const val HEADER_FRACTION = 0.86f

/**
 * Movie or series details: backdrop, title, meta, plot, play buttons, audio / quality chips; for
 * series also season tabs and the episode list; below the fold the cast and "مشابه" rows.
 *
 * [onPlay] gets the exact start position (0 = from the beginning); the player does not ask again.
 *
 * Extra actions: the "+ لیست من" button next to the play buttons (when the ViewModel has My List
 * actions) and, on an episode row, long-press OK / MENU toggles watched.
 */
@Composable
fun DetailsScreen(
    viewModel: DetailsViewModel,
    onPlay: (key: WatchKey, startPositionMs: Long) -> Unit,
    onOpenTitle: (TitleSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize().background(BolbolColors.Background)) {
        when (val current = state) {
            DetailsUiState.Loading -> LoadingState()
            is DetailsUiState.Error -> ErrorState(
                message = stringResource(current.error.messageRes()),
                onRetry = { viewModel.retry() },
            )
            is DetailsUiState.Content -> DetailsContent(
                state = current,
                onSeasonSelected = { viewModel.onSeasonSelected(it) },
                onAudioSelected = { viewModel.onAudioSelected(it) },
                onQualitySelected = { viewModel.onQualitySelected(it) },
                onToggleMyList = { viewModel.onToggleMyList() },
                onSetWatched = { key, watched -> viewModel.onSetWatched(key, watched) },
                onPlay = onPlay,
                onOpenTitle = onOpenTitle,
            )
        }
    }
}

@StringRes
private fun DetailsError.messageRes(): Int = when (this) {
    DetailsError.OFFLINE -> R.string.details_error_offline
    DetailsError.SERVER_DOWN -> R.string.details_error_server_down
    DetailsError.NOT_FOUND -> R.string.details_error_not_found
    DetailsError.GENERIC -> R.string.details_error_generic
}

/**
 * Lets the primary button jump straight to the episode it would play (D-pad towards the list),
 * instead of landing on whichever row is geometrically nearest. [attached] is true only while that
 * row is composed, because an unattached FocusRequester must never be used.
 */
private class EpisodeFocusTarget {
    val requester = FocusRequester()
    var attached = false
}

/**
 * The page's vertical scroll is moved only by [DetailsContent]'s own focus handling, never by focus
 * bring-into-view: the info block must stay exactly where it was laid out (title fully visible)
 * when a header button takes focus. Every scrollable below the header that needs the normal
 * behaviour (the episode list, the season tabs) gets the default spec back.
 */
@OptIn(ExperimentalFoundationApi::class)
private object NoAutoScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailsContent(
    state: DetailsUiState.Content,
    onSeasonSelected: (Int) -> Unit,
    onAudioSelected: (AudioType) -> Unit,
    onQualitySelected: (Int) -> Unit,
    onToggleMyList: () -> Unit,
    onSetWatched: (WatchKey, Boolean) -> Unit,
    onPlay: (WatchKey, Long) -> Unit,
    onOpenTitle: (TitleSummary) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val defaultSpec = LocalBringIntoViewSpec.current
    // Focus entering a section scrolls that section to the top (the header: back to offset 0).
    val scrollTo = remember(listState, scope) {
        { index: Int -> scope.launch { listState.animateScrollToItem(index) } }
    }
    val hasCast = state.cast.isNotEmpty()
    val castIndex = 1
    val relatedIndex = if (hasCast) 2 else 1

    CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScrollSpec) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item(key = "header", contentType = "header") {
                DetailsHeader(
                    state = state,
                    defaultSpec = defaultSpec,
                    onSeasonSelected = onSeasonSelected,
                    onAudioSelected = onAudioSelected,
                    onQualitySelected = onQualitySelected,
                    onToggleMyList = onToggleMyList,
                    onSetWatched = onSetWatched,
                    onPlay = onPlay,
                    modifier = Modifier
                        .fillParentMaxHeight(HEADER_FRACTION)
                        .fillMaxWidth()
                        .onFocusChanged { if (it.hasFocus) scrollTo(0) },
                )
            }
            if (hasCast) {
                item(key = "cast", contentType = "cast") {
                    Box(Modifier.onFocusChanged { if (it.hasFocus) scrollTo(castIndex) }) { CastRow(state.cast) }
                }
            }
            if (state.related.isNotEmpty()) {
                item(key = "related", contentType = "related") {
                    Box(Modifier.onFocusChanged { if (it.hasFocus) scrollTo(relatedIndex) }) {
                        ContentRow(
                            title = stringResource(R.string.details_related),
                            items = state.related,
                            key = { it.id },
                            contentType = { "poster" },
                        ) { title ->
                            PosterCard(item = title, onClick = { onOpenTitle(title) })
                        }
                    }
                }
            }
            item(key = "end", contentType = "end") { Spacer(Modifier.height(BolbolSpacing.SafeVertical)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailsHeader(
    state: DetailsUiState.Content,
    defaultSpec: BringIntoViewSpec,
    onSeasonSelected: (Int) -> Unit,
    onAudioSelected: (AudioType) -> Unit,
    onQualitySelected: (Int) -> Unit,
    onToggleMyList: () -> Unit,
    onSetWatched: (WatchKey, Boolean) -> Unit,
    onPlay: (WatchKey, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = state.details.summary
    val episodeTarget = remember { EpisodeFocusTarget() }
    HeroBackdrop(imageUrl = summary.backdropUrl, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = BolbolSpacing.SafeHorizontal, vertical = BolbolSpacing.SafeVertical),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            InfoPane(
                state = state,
                episodeTarget = episodeTarget,
                onAudioSelected = onAudioSelected,
                onQualitySelected = onQualitySelected,
                onToggleMyList = onToggleMyList,
                onPlay = onPlay,
                modifier = Modifier.weight(if (state.isSeries) 0.46f else 0.64f).fillMaxHeight(),
            )
            if (state.isSeries) {
                EpisodePane(
                    state = state,
                    episodeTarget = episodeTarget,
                    defaultSpec = defaultSpec,
                    onSeasonSelected = onSeasonSelected,
                    onSetWatched = onSetWatched,
                    onPlay = onPlay,
                    modifier = Modifier.weight(0.54f).fillMaxHeight(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Info pane: title, meta, plot, buttons, chips
// ---------------------------------------------------------------------------------------------

@Composable
private fun InfoPane(
    state: DetailsUiState.Content,
    episodeTarget: EpisodeFocusTarget,
    onAudioSelected: (AudioType) -> Unit,
    onQualitySelected: (Int) -> Unit,
    onToggleMyList: () -> Unit,
    onPlay: (WatchKey, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val details = state.details
    val summary = details.summary
    // Anchored to the bottom so the buttons stay put however long the title and plot are.
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
    ) {
        // The buttons and chips below are measured first; the texts get what is left. The block
        // is top-aligned inside its own box, so if the space ever runs short the plot (the last
        // line, which shrinks first) is cut at the bottom, never the title at the top edge.
        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BolbolText(
                text = summary.faTitle.ifBlank { summary.title },
                style = MaterialTheme.typography.headlineLarge,
                color = BolbolColors.TextPrimary,
                maxLines = 2,
            )
            if (summary.faTitle.isNotBlank() && summary.title.isNotBlank()) {
                BolbolText(
                    text = summary.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = BolbolColors.TextSecondary,
                    maxLines = 1,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaLine(
                    parts = metaParts(details),
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 2,
                )
                AudioBadge(dubbed = summary.hasDub, subtitled = summary.hasSubtitle)
            }
            if (details.plot.isNotBlank()) {
                BolbolText(
                    text = details.plot,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BolbolColors.TextPrimary,
                    maxLines = 3,
                )
            }
        }
        PlayButtons(state = state, episodeTarget = episodeTarget, onToggleMyList = onToggleMyList, onPlay = onPlay)
        ChoicePickers(state.choices, onAudioSelected, onQualitySelected)
    }
}

@Composable
private fun metaParts(details: TitleDetails): List<String> {
    val summary = details.summary
    val imdb = summary.imdbRating?.takeIf { it > 0f }
    return listOf(
        summary.year?.toString().orEmpty(),
        details.runtimeMinutes?.takeIf { it > 0 }?.let { formatRuntimeFa(it) }.orEmpty(),
        ageLabel(details.ageRating),
        if (imdb != null) stringResource(R.string.details_imdb, formatImdb(imdb)) else "",
        details.genres.take(MAX_GENRES).joinToString(stringResource(R.string.details_genre_separator)),
    )
}

private const val MAX_GENRES = 3

/** "12" -> "رده سنی ۱۲"; ratings like "PG-13" or "TV-Y7" are shown as they are. */
@Composable
private fun ageLabel(raw: String?): String {
    val age = raw?.trim().orEmpty()
    return when {
        age.isEmpty() -> ""
        age.all { it.isDigit() } -> stringResource(R.string.details_age, age.toPersianDigits())
        else -> age
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayButtons(
    state: DetailsUiState.Content,
    episodeTarget: EpisodeFocusTarget,
    onToggleMyList: () -> Unit,
    onPlay: (WatchKey, Long) -> Unit,
) {
    val primary = state.primary
    val inMyList = state.inMyList
    if (primary == null) {
        BolbolText(
            text = stringResource(R.string.details_unavailable),
            style = MaterialTheme.typography.titleSmall,
            color = BolbolColors.TextSecondary,
            maxLines = 2,
        )
        if (inMyList == null) return
    }

    val firstFocus = remember { FocusRequester() }
    // Initial focus is the first button (the main one). The effect lives next to the buttons so the requester is attached.
    LaunchedEffect(Unit) { firstFocus.requestFocus() }

    // Towards the episode list (D-pad left in RTL): from the LAST button of the row, so the
    // other buttons stay reachable. Series only; movies have no list.
    val towardsEpisodes = if (state.isSeries) {
        Modifier.focusProperties {
            end = if (episodeTarget.attached) episodeTarget.requester else FocusRequester.Default
        }
    } else {
        Modifier
    }
    val showRestart = primary != null && primary.resume

    // A series that can resume has three buttons, which do not fit the narrow info pane on one line.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (primary != null) {
            TvButton(
                text = primaryLabel(primary, state.isSeries),
                onClick = { onPlay(primary.key, primary.startPositionMs) },
                modifier = Modifier
                    .focusRequester(firstFocus)
                    .then(if (showRestart || inMyList != null) Modifier else towardsEpisodes),
                icon = BolbolIcons.Play,
            )
        }
        if (showRestart) {
            TvButton(
                text = stringResource(R.string.details_restart),
                onClick = { onPlay(primary.key, 0L) },
                modifier = if (inMyList != null) Modifier else towardsEpisodes,
                icon = BolbolIcons.Replay,
                style = TvButtonStyle.Secondary,
            )
        }
        if (inMyList != null) {
            TvButton(
                text = stringResource(if (inMyList) R.string.details_in_my_list else R.string.details_my_list),
                onClick = onToggleMyList,
                modifier = (if (primary == null) Modifier.focusRequester(firstFocus) else Modifier).then(towardsEpisodes),
                icon = if (inMyList) BolbolIcons.Check else BolbolIcons.Add,
                style = TvButtonStyle.Secondary,
            )
        }
    }
}

@Composable
private fun primaryLabel(action: PlayAction, isSeries: Boolean): String {
    val key = action.key
    return when {
        isSeries && action.resume -> stringResource(
            R.string.details_resume_episode,
            key.season.toPersianDigits(),
            key.episode.toPersianDigits(),
        )
        isSeries -> stringResource(
            R.string.details_start_episode,
            key.season.toPersianDigits(),
            key.episode.toPersianDigits(),
        )
        action.resume -> stringResource(R.string.details_resume_from, formatDurationFa(action.startPositionMs))
        else -> stringResource(R.string.details_play)
    }
}

@Composable
private fun ChoicePickers(
    choices: StreamChoices,
    onAudioSelected: (AudioType) -> Unit,
    onQualitySelected: (Int) -> Unit,
) {
    if (choices.audioOptions.size > 1) {
        val dubbed = stringResource(R.string.details_audio_dubbed)
        val subtitled = stringResource(R.string.details_audio_subtitled)
        PickerRow(label = stringResource(R.string.details_audio_label)) {
            ChipGroup(
                options = choices.audioOptions.map { if (it == AudioType.DUBBED) dubbed else subtitled },
                selectedIndex = choices.audioOptions.indexOf(choices.selectedAudio),
                onSelect = { onAudioSelected(choices.audioOptions[it]) },
            )
        }
    }
    if (choices.qualityOptions.size > 1) {
        PickerRow(label = stringResource(R.string.details_quality_label)) {
            ChipGroup(
                options = choices.qualityOptions.map { it.toPersianDigits() },
                selectedIndex = choices.qualityOptions.indexOf(choices.selectedQuality),
                onSelect = { onQualitySelected(choices.qualityOptions[it]) },
            )
        }
    }
}

@Composable
private fun PickerRow(label: String, chips: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        BolbolText(
            text = label,
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.labelLarge,
            color = BolbolColors.TextSecondary,
            maxLines = 1,
        )
        chips()
    }
}

// ---------------------------------------------------------------------------------------------
// Series: season tabs + episode list
// ---------------------------------------------------------------------------------------------

private val EpisodeRowHeight = 60.dp
private val EpisodeShape = RoundedCornerShape(BolbolSpacing.CardRadius)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodePane(
    state: DetailsUiState.Content,
    episodeTarget: EpisodeFocusTarget,
    defaultSpec: BringIntoViewSpec,
    onSeasonSelected: (Int) -> Unit,
    onSetWatched: (WatchKey, Boolean) -> Unit,
    onPlay: (WatchKey, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetKey = state.primary?.key
    // The list opens one row above the episode "continue / start" points at, for context.
    val targetIndex = if (targetKey != null && targetKey.season == state.selectedSeason) {
        state.episodes.indexOfFirst { it.key == targetKey }
    } else {
        -1
    }
    val listState = remember(state.selectedSeason) {
        LazyListState(firstVisibleItemIndex = (targetIndex - 1).coerceAtLeast(0))
    }

    // The page itself does not auto-scroll on focus (see NoAutoScrollSpec), but these two scrollables must.
    CompositionLocalProvider(LocalBringIntoViewSpec provides defaultSpec) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.seasons.size > 1) {
                SeasonChips(state.seasons, state.selectedSeason, onSeasonSelected)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = state.episodes, key = { it.number }, contentType = { "episode" }) { episode ->
                    val isTarget = episode.key == targetKey
                    if (isTarget) {
                        DisposableEffect(Unit) {
                            episodeTarget.attached = true
                            onDispose { episodeTarget.attached = false }
                        }
                    }
                    EpisodeRow(
                        item = episode,
                        onClick = { onPlay(episode.key, episode.startPositionMs) },
                        onToggleWatched = { onSetWatched(episode.key, !episode.completed) },
                        modifier = if (isTarget) Modifier.focusRequester(episodeTarget.requester) else Modifier,
                    )
                }
            }
        }
    }
}

/** Season tabs: scrolls sideways when there are many; focus enters on the selected tab. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeasonChips(seasons: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    val selectedRequester = remember { FocusRequester() }
    val hasSelected = selected in seasons
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .focusRestorer(if (hasSelected) selectedRequester else FocusRequester.Default)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        seasons.forEach { season ->
            key(season) {
                SelectableChip(
                    text = stringResource(R.string.details_season, season.toPersianDigits()),
                    selected = season == selected,
                    onClick = { onSelect(season) },
                    modifier = if (season == selected) Modifier.focusRequester(selectedRequester) else Modifier,
                )
            }
        }
    }
}

/**
 * One episode: "قسمت ۳" (+ the title when the server has a real one), time left and a progress
 * bar when started, a check when finished. Solid surface, focus = lighter fill + white border.
 * Long-pressing OK (or MENU) calls [onToggleWatched]: the check appears or disappears.
 */
@Composable
private fun EpisodeRow(
    item: EpisodeItem,
    onClick: () -> Unit,
    onToggleWatched: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitle = remember(item.title) { episodeSubtitle(item.title) }
    Surface(
        onClick = onClick,
        enabled = item.playable,
        modifier = modifier
            .fillMaxWidth()
            .height(EpisodeRowHeight)
            .longPressOrMenu { onToggleWatched() },
        shape = ClickableSurfaceDefaults.shape(EpisodeShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = BolbolColors.Surface,
            contentColor = BolbolColors.TextPrimary,
            focusedContainerColor = BolbolColors.SurfaceHigh,
            focusedContentColor = BolbolColors.TextPrimary,
            pressedContainerColor = BolbolColors.SurfaceHigh,
            pressedContentColor = BolbolColors.TextPrimary,
            disabledContainerColor = BolbolColors.Surface,
            disabledContentColor = BolbolColors.TextSecondary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing), shape = EpisodeShape),
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BolbolText(
                    text = formatEpisodeFa(item.key.season, item.number, includeSeason = false),
                    modifier = Modifier.widthIn(min = 64.dp),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Column(Modifier.weight(1f)) {
                    if (subtitle != null) {
                        BolbolText(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = BolbolColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    if (item.progressLabel != null) {
                        BolbolText(
                            text = item.progressLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = BolbolColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
                if (item.completed) {
                    Icon(
                        imageVector = BolbolIcons.Check,
                        contentDescription = stringResource(R.string.details_episode_watched),
                        modifier = Modifier.size(22.dp),
                        tint = BolbolColors.Accent,
                    )
                }
            }
            if (item.fraction > 0f) {
                ProgressLine(visibleProgress(item.fraction), Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cast
// ---------------------------------------------------------------------------------------------

private val CastPhotoSize = 72.dp
private val CastCardWidth = 96.dp

@Composable
private fun CastRow(cast: List<Person>) {
    ContentRow(
        title = stringResource(R.string.details_cast),
        items = cast,
        key = { it.id },
        contentType = { "person" },
    ) { person ->
        CastCard(person)
    }
}

/** Round photo + name. Focusable so the D-pad can reach (and scroll to) the row; no action yet. */
@Composable
private fun CastCard(person: Person) {
    Column(
        modifier = Modifier.width(CastCardWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            onClick = {},
            modifier = Modifier.size(CastPhotoSize),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
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
                focusedBorder = Border(BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing), shape = CircleShape),
            ),
        ) {
            if (!person.photoUrl.isNullOrBlank()) {
                PersonPhoto(person.photoUrl!!)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BolbolText(
                        text = person.name.trim().take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = BolbolColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
        BolbolText(
            text = person.name,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = BolbolColors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Photo decoded at exactly its on-screen size (the 2 GB TV has no room for full-size portraits). */
@Composable
private fun PersonPhoto(url: String) {
    val context = LocalPlatformContext.current
    val sidePx = with(LocalDensity.current) { CastPhotoSize.roundToPx() }
    val request = remember(url, sidePx, context) {
        ImageRequest.Builder(context)
            .data(url)
            .size(sidePx, sidePx)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
}
