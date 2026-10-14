package app.bolbol.tv.feature.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.CatalogRepository
import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.TitleSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val SEARCH_DEBOUNCE_MS = 400L

/** Predictions follow typing faster than the search itself. */
internal const val PREDICTION_DEBOUNCE_MS = 120L
internal const val MAX_PREDICTIONS = 6

internal enum class SearchPhase {
    /** Query shorter than [MIN_QUERY_LENGTH]: recent searches and a hint. */
    Idle,

    /** Waiting for the first page (any [SearchUiState.items] are the previous results). */
    Loading,
    Results,
    Empty,
    Error,
}

internal data class SearchUiState(
    /** Text shown in the query field (as typed). */
    val query: String = "",
    val language: KeyboardLanguage = KeyboardLanguage.Persian,
    val recents: List<String> = emptyList(),
    /** Completions for [query] shown above the keys (recent searches first, then known titles), best first. */
    val predictions: List<String> = emptyList(),
    val phase: SearchPhase = SearchPhase.Idle,
    val items: List<TitleSummary> = emptyList(),
    /** Last page loaded for [items]. */
    val page: Int = 0,
    val hasNext: Boolean = false,
    val loadingMore: Boolean = false,
    val error: SearchError? = null,
    /** Bumped whenever a fresh result set replaces the old one, so the grid starts at the top. */
    val generation: Int = 0,
) {
    /** True when the results panel has something the D-pad can land on. */
    val hasFocusableResults: Boolean
        get() = when (phase) {
            SearchPhase.Idle -> recents.isNotEmpty()
            SearchPhase.Loading -> items.isNotEmpty()
            SearchPhase.Results, SearchPhase.Error -> true
            SearchPhase.Empty -> false
        }
}

/**
 * State and logic of the search screen (docs/design-system.md §Screens 4).
 *
 * Typing -> normalise -> 400 ms debounce -> `CatalogRepository.search(text, 1)`; a newer query
 * cancels the older request ([collectLatest]). Queries under [MIN_QUERY_LENGTH] characters show the
 * recent searches instead. More pages load when focus reaches the last grid row. The last keyboard
 * layout and the recent searches persist in SharedPreferences (read/written off the main thread).
 *
 * Built with [factory] (manual DI, docs/decisions.md §Architecture).
 */
class SearchViewModel internal constructor(
    private val catalog: CatalogRepository,
    private val store: SearchStore,
    private val ioDispatcher: CoroutineDispatcher,
    private val debounceMs: Long,
    private val predictionDebounceMs: Long = PREDICTION_DEBOUNCE_MS,
) : ViewModel() {

    constructor(catalog: CatalogRepository, context: Context) :
        this(catalog, SharedPrefsSearchStore(context), Dispatchers.IO, SEARCH_DEBOUNCE_MS)

    private val _state = MutableStateFlow(SearchUiState())
    internal val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** One search to run; [nonce] forces a re-run of the same text (retry). */
    private data class Request(
        val query: String,
        val nonce: Int = 0,
        /** Skip the debounce (recent chip, voice, retry). */
        val immediate: Boolean = false,
        /** Add to the recent searches once it returns results (voice). */
        val remember: Boolean = false,
    )

    private val requests = MutableStateFlow(Request(""))
    private val typed = MutableStateFlow("")
    private var moreJob: Job? = null

    /** Id of the key that had focus last, so coming back from details/results lands on it again. */
    internal var lastKeyId: String = DEFAULT_KEY_ID
        private set

    /** Normalised query that produced the current results (what "next page" and "recent" refer to). */
    private var activeQuery = ""

    /** Title opened last, so the screen can put focus back on it when the user returns. */
    private var openedId: Long? = null

    init {
        viewModelScope.launch(ioDispatcher) {
            val language = store.loadLanguage()
            val recents = store.loadRecents()
            _state.update { it.copy(language = language, recents = recents) }
        }
        viewModelScope.launch {
            requests.collectLatest { process(it) }
        }
        viewModelScope.launch {
            typed.debounce(predictionDebounceMs).collectLatest { updatePredictions(it) }
        }
    }

    internal fun onKeyFocused(id: String) {
        lastKeyId = id
    }

    /** A prediction chip was chosen: fill the query with it and search right away. */
    internal fun onPredictionClicked(text: String) {
        setText(text, immediate = true, remember = true, language = detectLanguage(text))
    }

    private suspend fun updatePredictions(text: String) {
        val query = normalizeQuery(text)
        if (query.isEmpty()) {
            _state.update { if (it.predictions.isEmpty()) it else it.copy(predictions = emptyList()) }
            return
        }
        val fromRecents = _state.value.recents.filter { r ->
            r.startsWith(query, ignoreCase = true) && !r.equals(query, ignoreCase = true)
        }
        val fromCatalog = try {
            catalog.suggestions(query, MAX_PREDICTIONS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList() // predictions are a nicety, never an error
        }
        val merged = (fromRecents + fromCatalog).distinctBy { it.lowercase() }.take(MAX_PREDICTIONS)
        _state.update { if (it.predictions == merged) it else it.copy(predictions = merged) }
    }

    // region Input

    internal fun onKey(action: KeyAction) {
        val current = _state.value
        val next = KeyboardState(current.query, current.language).reduce(action)
        if (action == KeyAction.SwitchLanguage) {
            _state.update { it.copy(language = next.language) }
            viewModelScope.launch(ioDispatcher) { store.saveLanguage(next.language) }
        } else if (next.text != current.query) {
            setText(next.text)
        }
    }

    internal fun onRecentClicked(query: String) {
        setText(query, immediate = true, language = detectLanguage(query))
    }

    /** Speech recogniser result. */
    internal fun onVoiceResult(spoken: String) {
        val text = sanitizeInput(spoken)
        if (text.isBlank()) return
        setText(text, immediate = true, remember = true, language = detectLanguage(text))
    }

    /** Text typed on the phone (phone remote); replaces the query and switches the layout display. */
    internal fun onExternalInput(typed: String) {
        val text = sanitizeInput(typed)
        setText(text, language = detectLanguage(text))
    }

    internal fun retry() {
        requests.update { it.copy(nonce = it.nonce + 1, immediate = true) }
    }

    private fun setText(
        text: String,
        immediate: Boolean = false,
        remember: Boolean = false,
        language: KeyboardLanguage? = null,
    ) {
        _state.update { it.copy(query = text, language = language ?: it.language) }
        typed.value = text
        requests.update { it.copy(query = normalizeQuery(text), immediate = immediate, remember = remember) }
    }

    // endregion

    // region Search

    private suspend fun process(request: Request) {
        moreJob?.cancel()
        moreJob = null
        if (!isSearchable(request.query)) {
            activeQuery = ""
            _state.update {
                it.copy(
                    phase = SearchPhase.Idle,
                    items = emptyList(),
                    page = 0,
                    hasNext = false,
                    loadingMore = false,
                    error = null,
                    generation = it.generation + 1,
                )
            }
            return
        }
        if (!request.immediate) delay(debounceMs) // cancelled by the next keystroke
        _state.update { it.copy(phase = SearchPhase.Loading, error = null, loadingMore = false) }
        try {
            showFirstPage(request, catalog.search(request.query, 1))
        } catch (e: CancellationException) {
            throw e
        } catch (e: CatalogException.NotFound) {
            // "No results" is a normal answer, not a failure (docs/api.md §5.3).
            showFirstPage(request, null)
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    phase = SearchPhase.Error,
                    items = emptyList(),
                    page = 0,
                    hasNext = false,
                    error = e.toSearchError(),
                    generation = it.generation + 1,
                )
            }
        }
    }

    private fun showFirstPage(request: Request, page: Page<TitleSummary>?) {
        val items = mergePages(emptyList(), page?.items.orEmpty())
        activeQuery = request.query
        _state.update {
            it.copy(
                phase = if (items.isEmpty()) SearchPhase.Empty else SearchPhase.Results,
                items = items,
                page = if (items.isEmpty()) 0 else 1,
                hasNext = items.isNotEmpty() && page != null && page.hasNext,
                loadingMore = false,
                error = null,
                generation = it.generation + 1,
            )
        }
        if (request.remember && items.isNotEmpty()) addRecent(request.query)
    }

    /** Called when the poster at [index] gets focus; loads the next page from the last row on. */
    internal fun onResultFocused(index: Int, columns: Int) {
        val s = _state.value
        if (s.phase == SearchPhase.Results && isInLastRow(index, s.items.size, columns)) loadMore()
    }

    private fun loadMore() {
        val s = _state.value
        if (s.phase != SearchPhase.Results || !s.hasNext || s.loadingMore || moreJob?.isActive == true) return
        val query = activeQuery
        val nextPage = s.page + 1
        _state.update { it.copy(loadingMore = true) }
        moreJob = viewModelScope.launch {
            try {
                val page = catalog.search(query, nextPage)
                if (activeQuery != query) return@launch // a newer search replaced these results
                _state.update {
                    it.copy(
                        items = mergePages(it.items, page.items),
                        page = nextPage,
                        hasNext = page.hasNext,
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what we have; the next focus on the last row retries.
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    // endregion

    // region Recents and focus restore

    /** The user opened [item] from the current results: remember the search and the item. */
    internal fun onResultOpened(item: TitleSummary) {
        openedId = item.id
        if (activeQuery.isNotEmpty()) addRecent(activeQuery)
    }

    /** Id of the title opened before leaving the screen (once), so focus can return to it. */
    internal fun consumeOpenedId(): Long? = openedId.also { openedId = null }

    private fun addRecent(query: String) {
        var updated = emptyList<String>()
        _state.update {
            updated = it.recents.withRecent(query)
            it.copy(recents = updated)
        }
        val snapshot = updated
        viewModelScope.launch(ioDispatcher) { store.saveRecents(snapshot) }
    }

    // endregion

    companion object {
        /** Key focused first (ی on the Persian layout, g on the English one): the middle of the letters. */
        const val DEFAULT_KEY_ID = "2.4"

        fun factory(catalog: CatalogRepository, context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext ?: context
            return viewModelFactory {
                initializer { SearchViewModel(catalog, appContext) }
            }
        }
    }
}
