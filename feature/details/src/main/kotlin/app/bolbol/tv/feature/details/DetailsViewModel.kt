package app.bolbol.tv.feature.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.CatalogRepository
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PrefsRepository
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.model.WatchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State holder of the details screen (movie or series).
 *
 * [uiState] combines the loaded details, the live saved progress of the title and the remembered
 * audio/quality choice into one immutable [DetailsUiState]. The combine step is a handful of
 * list operations over one title's episodes, cheap enough to run where the state is collected.
 *
 * Audio and quality chips write `PrefsRepository.saveTitlePrefs`; the player reads the same prefs,
 * so what the chips show is what plays.
 *
 * [myList] (optional) drives the "+ لیست من" button: [DetailsUiState.Content.inMyList] follows it
 * and [onToggleMyList] flips it. With null the button is hidden.
 *
 * [focusSeason] / [focusEpisode] (from Continue Watching's "رفتن به صفحهٔ سریال"): that season starts selected and
 * [DetailsUiState.Content.focusEpisode] names the episode to focus; both fall back to the usual behaviour when
 * the season or episode does not exist. A season tab picked by the user replaces the preselection.
 */
class DetailsViewModel(
    private val titleId: Long,
    private val catalog: CatalogRepository,
    private val watch: WatchRepository,
    private val prefs: PrefsRepository,
    private val myList: MyListActions? = null,
    /** Season of the episode to open on (0 = none): preselects that season tab when it exists. */
    focusSeason: Int = 0,
    /** Episode to focus first (0 = none); only used together with [focusSeason]. */
    focusEpisode: Int = 0,
) : ViewModel() {

    private val load = MutableStateFlow<LoadState>(LoadState.Loading)
    private val choice = MutableStateFlow<PlaybackPrefs?>(null)
    private val focusTarget = episodeFocusTarget(titleId, focusSeason, focusEpisode)
    private val userSeason = MutableStateFlow<Int?>(focusSeason.takeIf { it > 0 })
    private var loadJob: Job? = null

    /**
     * null = unknown or not wired in. Starts with null so a slow or broken list can never hold the
     * screen back, and a failing list simply hides the button.
     */
    private val inMyList: Flow<Boolean?> = myList?.isInMyList(titleId)
        ?.map<Boolean, Boolean?> { it }
        ?.onStart { emit(null) }
        ?.catch { emit(null) }
        ?: flowOf(null)

    val uiState: StateFlow<DetailsUiState> = combine(
        load,
        // A broken local progress table must not hide the title.
        watch.progressForTitle(titleId).catch { emit(emptyList()) },
        choice,
        userSeason,
        inMyList,
    ) { loadState, progress, prefsChoice, season, listed ->
        when (loadState) {
            LoadState.Loading -> DetailsUiState.Loading
            is LoadState.Failed -> DetailsUiState.Error(loadState.error)
            is LoadState.Loaded -> buildDetailsContent(loadState.details, progress, prefsChoice, season, listed, focusTarget)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailsUiState.Loading)

    init {
        loadDetails()
    }

    fun retry() {
        loadDetails()
    }

    /** Season tab picked by the user (series). */
    fun onSeasonSelected(season: Int) {
        userSeason.value = season
    }

    fun onAudioSelected(audio: AudioType) {
        save((choice.value ?: PlaybackPrefs()).withAudio(audio))
    }

    fun onQualitySelected(quality: Int) {
        val shownAudio = (uiState.value as? DetailsUiState.Content)?.choices?.selectedAudio
        save((choice.value ?: PlaybackPrefs()).withQuality(quality, shownAudio))
    }

    /** "+ لیست من" / "✓ در لیست من": adds the title to My List, or removes it when it is already there. */
    fun onToggleMyList() {
        val actions = myList ?: return
        val content = uiState.value as? DetailsUiState.Content ?: return
        val listed = content.inMyList ?: return
        val summary = content.details.summary.let { it.copy(type = it.type ?: content.details.type) }
        viewModelScope.launch { quietly { actions.setInMyList(summary, !listed) } }
    }

    /** Long-press on an episode row: marks it watched ([watched] = true) or not watched. */
    fun onSetWatched(key: WatchKey, watched: Boolean) {
        viewModelScope.launch { quietly { watch.markWatched(key, watched) } }
    }

    /** A failed local write changes nothing on screen; there is nothing useful to tell the user. */
    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ignored on purpose
        }
    }

    private fun save(updated: PlaybackPrefs) {
        choice.value = updated
        viewModelScope.launch {
            try {
                prefs.saveTitlePrefs(titleId, updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Remembering the choice is a convenience; the chips already show it for this visit.
            }
        }
    }

    private fun loadDetails() {
        loadJob?.cancel()
        load.value = LoadState.Loading
        loadJob = viewModelScope.launch {
            try {
                val details = catalog.details(titleId)
                choice.value = rememberedChoice()
                load.value = LoadState.Loaded(details)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                load.value = LoadState.Failed(e.toDetailsError())
            }
        }
    }

    /** The title's own choice wins over the global default; either may be absent (or unreadable). */
    private suspend fun rememberedChoice(): PlaybackPrefs? =
        try {
            prefs.titlePrefs(titleId) ?: prefs.globalPrefs().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(
            titleId: Long,
            catalog: CatalogRepository,
            watch: WatchRepository,
            prefs: PrefsRepository,
            myList: MyListActions? = null,
            focusSeason: Int = 0,
            focusEpisode: Int = 0,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { DetailsViewModel(titleId, catalog, watch, prefs, myList, focusSeason, focusEpisode) }
            }
    }
}
