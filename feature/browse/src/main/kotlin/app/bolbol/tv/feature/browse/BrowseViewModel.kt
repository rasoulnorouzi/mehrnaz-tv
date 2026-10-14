package app.bolbol.tv.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.bolbol.tv.core.model.CatalogRepository
import app.bolbol.tv.core.model.Genre
import app.bolbol.tv.core.model.ListQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Paged grid of one catalogue query: the Movies / Series tabs ([ListQuery] without filters, which
 * also get quick-filter chips) and every home row's "view all".
 *
 * - Page 1 loads on creation; later pages load when a poster in the last [PREFETCH_ROWS] rows gets
 *   focus ([onItemFocused]); loading stops at the server's page count. One request at a time.
 * - A failed later page keeps the grid and shows an inline retry (no automatic retries: the API
 *   must not be hammered). A failed first page shows the full-screen error with retry.
 * - Changing the chip reloads from page 1 (the previous request is cancelled).
 * - Remembers the last focused poster so the screen can restore focus after details.
 *
 * Create with [factory] (manual DI, docs/decisions.md §Architecture); keep one per tab / "view all"
 * entry so state survives navigation to details and back.
 */
class BrowseViewModel(
    private val query: ListQuery,
    title: String,
    private val catalog: CatalogRepository,
) : ViewModel() {

    private val filters = quickFiltersFor(query)
    private val _state = MutableStateFlow(
        BrowseUiState(title = title, filters = filters, selected = BrowseFilter.All, phase = BrowsePhase.Loading),
    )
    internal val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    /**
     * Id of the poster that last had focus (null before the first focus and after a chip change).
     * Read once when the grid is (re)composed to restore focus; kept out of [state] on purpose.
     */
    internal var lastFocusedId: Long? = null
        private set

    private var activeQuery = query
    private var paging = PagingState()
    private var job: Job? = null

    init {
        loadFirstPage()
        if (genresOfferedFor(query)) loadGenres()
    }

    /** Separate coroutine: the chips may appear a moment after the grid, never delay it. A failure just means no genre chips. */
    private fun loadGenres() {
        viewModelScope.launch {
            val genres = try {
                catalog.genres(query.category)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _state.update { it.copy(genres = genres) }
        }
    }

    /** Called when the poster with [id] at grid position [index] gains focus. */
    internal fun onItemFocused(id: Long, index: Int) {
        lastFocusedId = id
        val s = _state.value
        val busy = s.phase != BrowsePhase.Content || s.loadingMore || s.loadMoreError != null
        if (shouldLoadMore(index, s.items.size, GRID_COLUMNS, paging.hasMore, busy)) loadMore()
    }

    /**
     * True while the grid is reloading because a chip was picked: the chip keeps focus then. Otherwise (first load,
     * retry) the grid takes focus even if the chip row grabbed it while the page was still empty (v1.4 QA).
     */
    internal var reloadFromChip = false

    internal fun selectFilter(filter: BrowseFilter) {
        val s = _state.value
        if (filter !in filters || (filter == s.selected && s.phase != BrowsePhase.Error)) return
        reloadFromChip = true
        activeQuery = query.withFilter(filter)
        _state.update { it.copy(selected = filter, selectedGenre = null) }
        loadFirstPage()
    }

    /** Reloads the grid from page 1 for [genre] (replaces any quick chip; the API's combined-filter behaviour is unverified). */
    internal fun selectGenre(genre: Genre) {
        val s = _state.value
        if (genre !in s.genres || (genre == s.selectedGenre && s.phase != BrowsePhase.Error)) return
        reloadFromChip = true
        activeQuery = query.withGenre(genre)
        _state.update { it.copy(selected = null, selectedGenre = genre) }
        loadFirstPage()
    }

    /** Retry after the first page failed. */
    internal fun retry() = loadFirstPage()

    /** Fetches the next page (normal paging, and the retry after a failed one). One request at a time. */
    internal fun loadMore() {
        val s = _state.value
        if (job?.isActive == true || s.phase != BrowsePhase.Content || !paging.hasMore) return
        _state.update { it.copy(loadingMore = true, loadMoreError = null) }
        job = viewModelScope.launch {
            var emptyPages = 0
            while (true) {
                val requested = paging.nextPage
                val result = try {
                    catalog.list(activeQuery, requested)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(loadingMore = false, loadMoreError = e.browseMessage()) }
                    return@launch
                }
                ensureActive()
                val before = paging.items.size
                paging = paging.append(requested, result)
                _state.update { it.copy(items = paging.items, page = paging.page, totalPages = paging.totalPages) }
                // A page of only duplicates adds no poster to focus, so nothing would ask for the next one.
                if (paging.items.size > before || !paging.hasMore || ++emptyPages >= MAX_EMPTY_PAGES) break
            }
            _state.update { it.copy(loadingMore = false) }
        }
    }

    private fun loadFirstPage() {
        job?.cancel()
        paging = PagingState()
        lastFocusedId = null
        _state.update {
            it.copy(
                phase = BrowsePhase.Loading,
                items = emptyList(),
                page = 0,
                totalPages = 0,
                error = null,
                loadingMore = false,
                loadMoreError = null,
            )
        }
        job = viewModelScope.launch {
            try {
                val result = catalog.list(activeQuery, 1)
                ensureActive()
                paging = paging.append(1, result)
                _state.update {
                    it.copy(
                        phase = if (paging.items.isEmpty()) BrowsePhase.Empty else BrowsePhase.Content,
                        items = paging.items,
                        page = paging.page,
                        totalPages = paging.totalPages,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = BrowsePhase.Error, error = e.browseMessage()) }
            }
        }
    }

    companion object {
        fun factory(query: ListQuery, title: String, catalog: CatalogRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { BrowseViewModel(query, title, catalog) } }
    }
}
