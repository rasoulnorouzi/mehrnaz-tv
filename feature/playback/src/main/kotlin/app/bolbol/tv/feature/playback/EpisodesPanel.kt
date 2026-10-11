package app.bolbol.tv.feature.playback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.bolbol.tv.core.designsystem.AudioBadge
import app.bolbol.tv.core.designsystem.BolbolColors
import app.bolbol.tv.core.designsystem.BolbolIcons
import app.bolbol.tv.core.designsystem.BolbolSpacing
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.ProgressLine
import app.bolbol.tv.core.designsystem.SelectableChip
import app.bolbol.tv.core.designsystem.formatEpisodeFa
import app.bolbol.tv.core.designsystem.toPersianDigits
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.Season
import app.bolbol.tv.core.model.WatchKey
import kotlinx.coroutines.flow.StateFlow

private val RowHeight = 56.dp
private val RowShape = RoundedCornerShape(BolbolSpacing.CardRadius)
private val CurrentMarkerWidth = 3.dp

/**
 * «قسمت‌ها» side panel (v1.1-4), the same end-edge panel as «تنظیمات پخش» ([PANEL_WIDTH]) while the video
 * keeps playing underneath. Season chips on top (only with more than one season), then the chosen season's
 * episodes: «قسمت ۳» + title, a «دوبله» badge, ✓ when watched, a thin bar when started. The playing episode
 * is highlighted; its season is preselected and it gets focus on open. OK on it calls [onClose]; OK on
 * another episode calls [onPick]. BACK is handled by the screen (closes the panel).
 *
 * [progress] is collected here, so Room is observed only while the panel is open and its updates
 * recompose the rows only (never the video layer). No images.
 */
@Composable
internal fun EpisodesPanel(
    seasons: List<Season>,
    current: WatchKey,
    progress: StateFlow<List<PlaybackProgress>>,
    onPick: (WatchKey) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val saved by progress.collectAsStateWithLifecycle()
    var selected by remember { mutableIntStateOf(initialPanelSeason(seasons, current) ?: 0) }
    val season = seasons.firstOrNull { it.number == selected }
    // Focus target on open: the playing episode (or the first row if it isn't in this season).
    val openFocus = remember { FocusRequester() }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(PANEL_WIDTH)
                .background(BolbolColors.Surface)
                .padding(horizontal = 24.dp, vertical = BolbolSpacing.SafeVertical),
        ) {
            Text(stringResource(R.string.player_episodes), style = MaterialTheme.typography.titleMedium, color = BolbolColors.TextPrimary)
            Spacer(Modifier.height(12.dp))
            if (seasons.size > 1) {
                SeasonChips(seasons, selected, onSelect = { selected = it })
                Spacer(Modifier.height(12.dp))
            } else if (season != null) {
                Text(
                    stringResource(R.string.player_season, season.number.toPersianDigits()),
                    style = MaterialTheme.typography.labelMedium,
                    color = BolbolColors.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (season != null) {
                // A fresh list (and scroll position) per season.
                key(season.number) {
                    EpisodeList(
                        season = season,
                        saved = saved,
                        current = current,
                        openFocus = openFocus,
                        onPick = onPick,
                        onClose = onClose,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // The row is composed by the LazyColumn's first measure; give it up to a few frames.
        repeat(FOCUS_ATTEMPTS) {
            if (openFocus.tryFocus()) return@LaunchedEffect
            withFrameNanos { }
        }
    }
}

private const val FOCUS_ATTEMPTS = 4

private fun FocusRequester.tryFocus(): Boolean = try {
    requestFocus()
    true
} catch (_: IllegalStateException) {
    false
}

/** Season tabs; scrolls sideways when many; focus enters on the selected one. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeasonChips(seasons: List<Season>, selected: Int, onSelect: (Int) -> Unit) {
    val selectedFocus = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRestorer(selectedFocus)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (season in seasons) {
            key(season.number) {
                val isSelected = season.number == selected
                SelectableChip(
                    text = stringResource(R.string.player_season, season.number.toPersianDigits()),
                    selected = isSelected,
                    onClick = { onSelect(season.number) },
                    modifier = if (isSelected) Modifier.focusRequester(selectedFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun EpisodeList(
    season: Season,
    saved: List<PlaybackProgress>,
    current: WatchKey,
    openFocus: FocusRequester,
    onPick: (WatchKey) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(season, saved, current) { episodeRows(current.postId, season, saved, current) }
    val currentIndex = remember(rows) { rows.indexOfFirst { it.current } }
    // Open one row above the playing episode, for context (it is then composed and focusable at once).
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
    val focusNumber = rows.getOrNull(if (currentIndex >= 0) currentIndex else 0)?.number
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = rows, key = { it.number }, contentType = { "episode" }) { row ->
            EpisodeRow(
                row = row,
                onClick = { if (row.current) onClose() else onPick(row.key) },
                modifier = if (row.number == focusNumber) Modifier.focusRequester(openFocus) else Modifier,
            )
        }
    }
}

/**
 * One episode: «قسمت ۳» · title (or «در حال پخش» for the playing one) · «دوبله» · ✓, and a 3 dp bar along
 * the bottom when started. The playing episode has a lighter fill and an accent marker at the start edge.
 * Solid colours only; focus = white border (no scale, so the narrow panel never clips).
 */
@Composable
private fun EpisodeRow(row: EpisodeRowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val container = if (row.current) BolbolColors.SurfaceHigh else BolbolColors.Background
    Surface(
        onClick = onClick,
        enabled = row.playable || row.current,
        modifier = modifier.fillMaxWidth().height(RowHeight),
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = BolbolColors.TextPrimary,
            focusedContainerColor = BolbolColors.SurfaceHigh,
            focusedContentColor = BolbolColors.TextPrimary,
            pressedContainerColor = BolbolColors.SurfaceHigh,
            pressedContentColor = BolbolColors.TextPrimary,
            disabledContainerColor = BolbolColors.Background,
            disabledContentColor = BolbolColors.TextSecondary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(BolbolSpacing.FocusBorder, BolbolColors.FocusRing), shape = RowShape),
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (row.current) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(CurrentMarkerWidth)
                        .fillMaxHeight()
                        .background(BolbolColors.Accent),
                )
            }
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BolbolText(
                    text = formatEpisodeFa(row.key.season, row.number, includeSeason = false),
                    modifier = Modifier.widthIn(min = 60.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (row.current) BolbolColors.Accent else Color.Unspecified,
                    maxLines = 1,
                )
                Column(Modifier.weight(1f)) {
                    if (row.title != null) {
                        BolbolText(
                            text = row.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = BolbolColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    if (row.current) {
                        Text(
                            stringResource(R.string.player_episode_now_playing),
                            style = MaterialTheme.typography.labelSmall,
                            color = BolbolColors.Accent,
                            maxLines = 1,
                        )
                    }
                }
                if (row.dubbed) AudioBadge(dubbed = true, subtitled = false)
                if (row.watched) {
                    Icon(
                        imageVector = BolbolIcons.Check,
                        contentDescription = stringResource(R.string.player_episode_watched),
                        modifier = Modifier.size(20.dp),
                        tint = BolbolColors.Accent,
                    )
                }
            }
            if (row.fraction > 0f) {
                ProgressLine(row.fraction, Modifier.align(Alignment.BottomStart), height = 3.dp)
            }
        }
    }
}
