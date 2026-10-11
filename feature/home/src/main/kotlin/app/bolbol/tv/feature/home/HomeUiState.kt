package app.bolbol.tv.feature.home

import androidx.compose.runtime.Immutable
import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.HomeSection
import app.bolbol.tv.core.model.TitleSummary

/** Where the top bar sends the user. Home itself is the selected tab and needs no destination. */
enum class HomeDestination { SEARCH, MOVIES, SERIES, SETTINGS }

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

    /** "ادامه تماشا": one wide card per started title, most recent first. */
    @Immutable
    data class ContinueWatching(val items: List<ContinueWatchingItem>) : HomeRow {
        override val id: String get() = CONTINUE_ROW_ID
        override val size: Int get() = items.size
        override fun titleAt(index: Int): TitleSummary = items[index].title
    }

    /** A server row of posters. */
    @Immutable
    data class Titles(override val id: String, val title: String, val items: List<TitleSummary>) : HomeRow {
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

/** Which card had focus, remembered by the ViewModel so Back from details restores it. */
data class FocusTarget(val rowId: String, val titleId: Long)

/** [FocusTarget] resolved against the current rows (the indexes the lazy lists need). */
internal data class InitialFocus(
    val rowIndex: Int,
    val itemIndex: Int,
    val rowId: String,
    val title: TitleSummary,
)

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
                if (items.isNotEmpty() && seen.add(rowId)) rows += HomeRow.Titles(rowId, section.title, items)
            }
            if (rows.isEmpty()) HomeUiState.Error(HomeError.GENERIC) else HomeUiState.Content(rows)
        }
    }

/**
 * Where focus starts: the remembered card if it still exists, else the first card of the
 * remembered row, else the first card of the first row. Null only when there are no cards.
 */
internal fun List<HomeRow>.resolveInitialFocus(remembered: FocusTarget?): InitialFocus? {
    if (remembered != null) {
        val rowIndex = indexOfFirst { it.id == remembered.rowId }
        if (rowIndex >= 0 && this[rowIndex].size > 0) {
            val row = this[rowIndex]
            val itemIndex = (0 until row.size).firstOrNull { row.titleAt(it).id == remembered.titleId } ?: 0
            return InitialFocus(rowIndex, itemIndex, row.id, row.titleAt(itemIndex))
        }
    }
    val rowIndex = indexOfFirst { it.size > 0 }
    if (rowIndex < 0) return null
    val row = this[rowIndex]
    return InitialFocus(rowIndex, 0, row.id, row.titleAt(0))
}
