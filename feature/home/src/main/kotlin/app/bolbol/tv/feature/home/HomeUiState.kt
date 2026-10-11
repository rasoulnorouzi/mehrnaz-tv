package app.bolbol.tv.feature.home

import androidx.compose.runtime.Immutable
import app.bolbol.tv.core.designsystem.formatContinueSubtitleFa
import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.HomeSection
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.TitleSummary

/** Where the top bar sends the user. Home itself is the selected tab and needs no destination. */
enum class HomeDestination { SEARCH, MOVIES, SERIES, MY_LIST, PROFILE, SETTINGS }

/** Why the feed could not be loaded; the screen maps it to a Persian message. */
enum class HomeError { OFFLINE, SERVER_DOWN, GENERIC }

/** One row of the home list. [id] is unique and stable (LazyColumn key and focus-memory key). */
@Immutable
sealed interface HomeRow {
    val id: String

    /** Number of cards in the row. */
    val size: Int

    /** The title behind card [index] (used for the hero and for opening details). */
    fun titleAt(index: Int): TitleSummary

    /** Extra hero line for card [index] (Continue Watching: where the viewer stopped), else null. */
    fun captionAt(index: Int): String? = null

    /** "ادامه تماشا": one wide card per started title, most recent first. */
    @Immutable
    data class ContinueWatching(val items: List<ContinueWatchingItem>) : HomeRow {
        override val id: String get() = CONTINUE_ROW_ID
        override val size: Int get() = items.size
        override fun titleAt(index: Int): TitleSummary = items[index].title
        override fun captionAt(index: Int): String = formatContinueSubtitleFa(items[index].progress)
    }

    /** A server row of posters. A non-null [viewAll] adds a last "مشاهده همه" card that opens that list. */
    @Immutable
    data class Titles(
        override val id: String,
        val title: String,
        val items: List<TitleSummary>,
        val viewAll: ListQuery? = null,
    ) : HomeRow {
        override val size: Int get() = items.size
        override fun titleAt(index: Int): TitleSummary = items[index]
    }

    companion object {
        const val CONTINUE_ROW_ID = "continue_watching"
    }
}

/** The single immutable state of the home screen. */
@Immutable
sealed interface HomeUiState {
    /** Nothing cached yet (first launch): the screen shows the static loading placeholder. */
    data object Loading : HomeUiState

    /** Loading failed and there is nothing to show: the screen shows a focused retry. */
    data class Error(val error: HomeError) : HomeUiState

    /** Rows to show (cached rows paint first, fresh rows replace them silently). Never empty. */
    data class Content(val rows: List<HomeRow>) : HomeUiState
}

/** Progress of `CatalogRepository.homeFeed()` inside the ViewModel. */
internal sealed interface FeedState {
    data object Loading : FeedState
    data class Loaded(val sections: List<HomeSection>) : FeedState
    data class Failed(val error: HomeError) : FeedState
}

/**
 * Which card had focus, remembered by the ViewModel so Back from details (or from a «مشاهده همه» grid) restores it.
 * [titleId] is [VIEW_ALL_FOCUS_ID] for the row's trailing «مشاهده همه» card, which is not a title.
 */
data class FocusTarget(val rowId: String, val titleId: Long) {
    val isViewAll: Boolean get() = titleId == VIEW_ALL_FOCUS_ID
}

/** [FocusTarget.titleId] of a row's «مشاهده همه» card (real title ids are positive). */
const val VIEW_ALL_FOCUS_ID: Long = -1L

/**
 * [FocusTarget] resolved against the current rows (the indexes the lazy lists need). [viewAll] is true when focus
 * belongs on the row's «مشاهده همه» card: [itemIndex] is then the row's size (the card after the last title) and
 * [title] stays the row's last title, which keeps the hero as it was when focus left it.
 */
internal data class InitialFocus(
    val rowIndex: Int,
    val itemIndex: Int,
    val rowId: String,
    val title: TitleSummary,
    val viewAll: Boolean = false,
    /** Extra hero line of the focused card, see [HomeRow.captionAt]. */
    val caption: String? = null,
) {
    /** The card that must receive the first focus request. */
    val target: FocusTarget get() = FocusTarget(rowId, if (viewAll) VIEW_ALL_FOCUS_ID else title.id)
}

internal fun Throwable.toHomeError(): HomeError = when (this) {
    is CatalogException.Offline -> HomeError.OFFLINE
    is CatalogException.AllMirrorsDown -> HomeError.SERVER_DOWN
    else -> HomeError.GENERIC
}

private const val SECTION_ROW_PREFIX = "section:"

/**
 * Joins the server rows and the local Continue Watching list into what the screen shows.
 * - Continue Watching comes first and is hidden when empty.
 * - Empty server rows are dropped, and duplicate ids are removed (LazyList keys must be unique).
 * - A failed feed is an error even if Continue Watching has entries: without the catalogue nothing can play.
 */
internal fun buildHomeUiState(feed: FeedState, continueWatching: List<ContinueWatchingItem>): HomeUiState =
    when (feed) {
        FeedState.Loading -> HomeUiState.Loading
        is FeedState.Failed -> HomeUiState.Error(feed.error)
        is FeedState.Loaded -> {
            val rows = ArrayList<HomeRow>(feed.sections.size + 1)
            val resumable = continueWatching.distinctBy { it.title.id }
            if (resumable.isNotEmpty()) rows += HomeRow.ContinueWatching(resumable)
            val seen = HashSet<String>()
            for (section in feed.sections) {
                val rowId = SECTION_ROW_PREFIX + section.id
                val items = section.items.distinctBy { it.id }
                if (items.isNotEmpty() && seen.add(rowId)) rows += HomeRow.Titles(rowId, section.title, items, section.viewAll)
            }
            if (rows.isEmpty()) HomeUiState.Error(HomeError.GENERIC) else HomeUiState.Content(rows)
        }
    }

/**
 * Where focus starts: the remembered card if it still exists, else the first card of the
 * remembered row, else the first card of the first row. Null only when there are no cards.
 * A remembered «مشاهده همه» card ([FocusTarget.isViewAll]) is restored while its row still has one.
 */
internal fun List<HomeRow>.resolveInitialFocus(remembered: FocusTarget?): InitialFocus? {
    if (remembered != null) {
        val rowIndex = indexOfFirst { it.id == remembered.rowId }
        if (rowIndex >= 0 && this[rowIndex].size > 0) {
            val row = this[rowIndex]
            if (remembered.isViewAll && row is HomeRow.Titles && row.viewAll != null) {
                val last = row.size - 1
                return InitialFocus(rowIndex, row.size, row.id, row.titleAt(last), viewAll = true)
            }
            val itemIndex = (0 until row.size).firstOrNull { row.titleAt(it).id == remembered.titleId } ?: 0
            return InitialFocus(rowIndex, itemIndex, row.id, row.titleAt(itemIndex), caption = row.captionAt(itemIndex))
        }
    }
    val rowIndex = indexOfFirst { it.size > 0 }
    if (rowIndex < 0) return null
    val row = this[rowIndex]
    return InitialFocus(rowIndex, 0, row.id, row.titleAt(0), caption = row.captionAt(0))
}

/** The hero image of [this]: the backdrop, else the poster (Continue Watching snapshots often lack a backdrop). */
internal fun TitleSummary.heroImageUrl(): String? =
    backdropUrl?.takeIf { it.isNotBlank() } ?: posterUrl?.takeIf { it.isNotBlank() }

/**
 * Cards are never shorter than this share of the progress bar: a 1 minute watch of a 3 hour film
 * is 0.6 % (under one pixel row of the 4 dp bar), which looks like "not started".
 */
internal const val MIN_VISIBLE_PROGRESS = 0.03f

/** The fraction to draw: 0 stays 0 (not started), anything above it is at least [MIN_VISIBLE_PROGRESS]. */
internal fun visibleProgress(fraction: Float): Float =
    if (fraction.isNaN() || fraction <= 0f) 0f else fraction.coerceIn(MIN_VISIBLE_PROGRESS, 1f)

/**
 * The bar of a Continue Watching card: [visibleProgress] of the saved fraction, or a [MIN_VISIBLE_PROGRESS] sliver when
 * the length is unknown (progress synced from the website has no reliable duration, so the fraction is 0). A
 * completed entry is a full bar.
 */
internal fun continueBarFraction(progress: PlaybackProgress): Float = when {
    progress.completed -> 1f
    progress.durationMs <= 0L -> MIN_VISIBLE_PROGRESS
    else -> visibleProgress(progress.fraction)
}

/**
 * Where focus goes when the card [gone] disappears (marked as watched or removed): the card that
 * slides into its place (the next one in the row), else the one before it, else the first card of
 * the nearest other row (below first). Null when no card is left.
 */
internal fun List<HomeRow>.neighbourFocus(gone: FocusTarget): FocusTarget? {
    val rowIndex = indexOfFirst { it.id == gone.rowId }
    if (rowIndex >= 0) {
        val row = this[rowIndex]
        val index = (0 until row.size).firstOrNull { row.titleAt(it).id == gone.titleId }
        if (index != null) {
            val sibling = when {
                index + 1 < row.size -> index + 1
                index > 0 -> index - 1
                else -> -1
            }
            if (sibling >= 0) return FocusTarget(row.id, row.titleAt(sibling).id)
        }
    }
    val below = indices.filter { it > rowIndex }
    val above = indices.filter { it < rowIndex }.reversed()
    for (i in below + above) {
        val row = this[i]
        if (row.size > 0) return FocusTarget(row.id, row.titleAt(0).id)
    }
    return null
}
