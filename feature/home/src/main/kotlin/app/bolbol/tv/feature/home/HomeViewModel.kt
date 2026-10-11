package app.bolbol.tv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.bolbol.tv.core.model.CatalogRepository
import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.WatchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State holder of the home screen.
 *
 * - [uiState] combines the (cache-first) catalogue feed with the Continue Watching list into one
 *   immutable [HomeUiState]. It leaves `Loading` only when both have emitted, so the first rows
 *   never appear without the Continue Watching row and then shift down.
 * - The feed is loaded once per ViewModel; [retry] reloads it after a failure.
 * - [markWatched] and [removeFromContinueWatching] are the Continue Watching card's menu actions;
 *   the list is a live query, so the card disappears from [uiState] by itself.
 * - [lastFocus] survives navigation (the ViewModel does, the composition does not) so Back from
 *   details restores focus to the same card.
 */
class HomeViewModel(
    private val catalog: CatalogRepository,
    private val watch: WatchRepository,
) : ViewModel() {

    private val feed = MutableStateFlow<FeedState>(FeedState.Loading)
    private var feedJob: Job? = null
    private var focused: FocusTarget? = null

    val uiState: StateFlow<HomeUiState> = combine(
        feed,
        // A broken local list must not take the whole screen down.
        watch.continueWatching().catch { emit(emptyList()) },
    ) { feedState, continueWatching -> buildHomeUiState(feedState, continueWatching) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState.Loading)

    /** The card that last had focus, or null before the user has focused one. Not observable on purpose. */
    val lastFocus: FocusTarget? get() = focused

    init {
        loadFeed()
    }

    fun onCardFocused(rowId: String, titleId: Long) {
        focused = FocusTarget(rowId, titleId)
    }

    /** The row's trailing «مشاهده همه» card got focus: Back from its grid returns to it, not to a poster. */
    fun onViewAllFocused(rowId: String) {
        focused = FocusTarget(rowId, VIEW_ALL_FOCUS_ID)
    }

    fun retry() {
        loadFeed()
    }

    /** "علامت‌گذاری به‌عنوان دیده‌شده": finishes the entry's latest episode (or the movie). */
    fun markWatched(item: ContinueWatchingItem) {
        viewModelScope.launch { quietly { watch.markWatched(item.progress.key, true) } }
    }

    /** "حذف از ادامه تماشا": hides the whole title from the row (episode watched flags are kept). */
    fun removeFromContinueWatching(item: ContinueWatchingItem) {
        viewModelScope.launch { quietly { watch.hideFromContinueWatching(item.title.id) } }
    }

    /** A failed local write leaves the card where it is; there is nothing useful to tell the user. */
    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ignored on purpose
        }
    }

    private fun loadFeed() {
        feedJob?.cancel()
        // Keep showing rows we already have; only a failed screen goes back to the placeholder.
        if (feed.value !is FeedState.Loaded) feed.value = FeedState.Loading
        feedJob = viewModelScope.launch {
            catalog.homeFeed()
                .catch { e ->
                    // The repository only throws when there is no cache either. If rows are already
                    // on screen, a failed refresh stays silent.
                    if (feed.value !is FeedState.Loaded) feed.value = FeedState.Failed(e.toHomeError())
                }
                .collect { homeFeed -> feed.value = FeedState.Loaded(homeFeed.sections) }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(catalog: CatalogRepository, watch: WatchRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { HomeViewModel(catalog, watch) } }
    }
}
