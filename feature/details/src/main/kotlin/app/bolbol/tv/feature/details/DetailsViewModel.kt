package app.bolbol.tv.feature.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.designsystem.formatEpisodeFa
import app.bolbol.tv.core.model.CatalogRepository
import app.bolbol.tv.core.model.DownloadFailure
import app.bolbol.tv.core.model.DownloadItem
import app.bolbol.tv.core.model.DownloadRepository
import app.bolbol.tv.core.model.DownloadRequest
import app.bolbol.tv.core.model.StartDownloadResult
import app.bolbol.tv.core.model.StreamOption
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
import kotlinx.coroutines.flow.update
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
    /** Offline saving (v1.4). With null the download buttons are hidden. */
    private val downloads: DownloadRepository? = null,
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

    /** This title's downloads; null = not wired in or unreadable (the download buttons are hidden). */
    private val titleDownloads: Flow<List<DownloadItem>?> = downloads?.downloadsForTitle(titleId)
        ?.map<List<DownloadItem>, List<DownloadItem>?> { it }
        ?.catch { emit(null) }
        ?: flowOf(null)

    private val extras: Flow<Pair<Boolean?, List<DownloadItem>?>> = combine(inMyList, titleDownloads) { a, b -> a to b }

    private val _overlay = MutableStateFlow<DetailsOverlay?>(null)

    /** The download dialog / remove confirmation shown over the page, or null. */
    val overlay: StateFlow<DetailsOverlay?> = _overlay

    private var overlayJob: Job? = null

    val uiState: StateFlow<DetailsUiState> = combine(
        load,
        // A broken local progress table must not hide the title.
        watch.progressForTitle(titleId).catch { emit(emptyList()) },
        choice,
        userSeason,
        extras,
    ) { loadState, progress, prefsChoice, season, (listed, saved) ->
        when (loadState) {
            LoadState.Loading -> DetailsUiState.Loading
            is LoadState.Failed -> DetailsUiState.Error(loadState.error)
            is LoadState.Loaded -> buildDetailsContent(
                loadState.details, progress, prefsChoice, season, listed, focusTarget, saved,
            )
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

    // -----------------------------------------------------------------------------------------
    // Offline saving
    // -----------------------------------------------------------------------------------------

    /** The streams of a movie ([key] without episode) or of one episode of the loaded title. */
    private fun streamsFor(key: WatchKey): List<StreamOption> {
        val content = uiState.value as? DetailsUiState.Content ?: return emptyList()
        return if (key.isEpisode) {
            content.details.seasons.firstOrNull { it.number == key.season }
                ?.episodes?.firstOrNull { it.number == key.episode }?.streams.orEmpty()
        } else {
            content.details.streams
        }
    }

    private fun subtitleLineFor(key: WatchKey): String? =
        if (key.isEpisode) formatEpisodeFa(key.season, key.episode) else null

    /** «دانلود» (movie button / episode icon): opens the dialog and loads free space and the sizes one by one. */
    fun onDownloadClicked(key: WatchKey) {
        val repo = downloads ?: return
        val streams = streamsFor(key)
        if (streams.isEmpty()) return
        overlayJob?.cancel()
        _overlay.value = DetailsOverlay.Pick(
            DownloadPickState(key, subtitleLineFor(key), initialOptions(streams, freeBytes = null), freeBytes = null),
        )
        overlayJob = viewModelScope.launch {
            quietly {
                val free = repo.storage().freeBytes
                updatePick(key) { it.withFree(free) }
            }
            // One HEAD at a time: the cost is tiny and the CDN is not hammered.
            for (stream in streams.distinct()) {
                val size = try {
                    repo.sizeOf(stream)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                updatePick(key) { it.withSize(stream, size) }
            }
        }
    }

    private fun updatePick(key: WatchKey, transform: (DownloadPickState) -> DownloadPickState) {
        _overlay.update { current ->
            if (current is DetailsOverlay.Pick && current.state.key == key) DetailsOverlay.Pick(transform(current.state)) else current
        }
    }

    /** A row of the dialog was chosen: starts the download; the dialog closes on success and explains a refusal. */
    fun onDownloadOptionChosen(stream: StreamOption) {
        val repo = downloads ?: return
        val pick = (_overlay.value as? DetailsOverlay.Pick)?.state ?: return
        if (pick.starting) return
        val content = uiState.value as? DetailsUiState.Content ?: return
        val summary = content.details.summary.let { it.copy(type = it.type ?: content.details.type) }
        val row = pick.options.firstOrNull { it.stream == stream }
        if (row != null && !row.fits) {
            // Known not to fit: explain with the numbers instead of asking the engine to refuse.
            val needed = (row.sizeBytes ?: 0L) + DOWNLOAD_SAFETY_MARGIN_BYTES
            updatePick(pick.key) { it.copy(message = DownloadMessage.NotEnoughSpace(needed, pick.freeBytes ?: 0L)) }
            return
        }
        updatePick(pick.key) { it.copy(starting = true, message = null) }
        viewModelScope.launch {
            val result = try {
                repo.start(DownloadRequest(pick.key, summary, pick.subtitleLine, stream))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StartDownloadResult.Failed(DownloadFailure.UNKNOWN)
            }
            when (result) {
                StartDownloadResult.Started, StartDownloadResult.AlreadySaved -> closeOverlay()
                is StartDownloadResult.NotEnoughSpace -> updatePick(pick.key) {
                    it.copy(starting = false, message = DownloadMessage.NotEnoughSpace(result.neededBytes, result.freeBytes))
                }
                is StartDownloadResult.Failed -> updatePick(pick.key) {
                    it.copy(starting = false, message = DownloadMessage.Failed(result.failure))
                }
            }
        }
    }

    /** «حذف دانلود»: asks first; nothing is deleted until [onConfirmRemoveDownload]. */
    fun onRemoveDownloadClicked(key: WatchKey) {
        if (downloads == null) return
        overlayJob?.cancel()
        _overlay.value = DetailsOverlay.ConfirmRemove(key, subtitleLineFor(key))
    }

    fun onConfirmRemoveDownload() {
        val repo = downloads ?: return
        val confirm = _overlay.value as? DetailsOverlay.ConfirmRemove ?: return
        closeOverlay()
        viewModelScope.launch { quietly { repo.remove(confirm.key) } }
    }

    /** A failed download was clicked: tries again. */
    fun onRetryDownload(key: WatchKey) {
        val repo = downloads ?: return
        viewModelScope.launch { quietly { repo.retry(key) } }
    }

    fun closeOverlay() {
        overlayJob?.cancel()
        _overlay.value = null
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
            downloads: DownloadRepository? = null,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { DetailsViewModel(titleId, catalog, watch, prefs, myList, focusSeason, focusEpisode, downloads) }
            }
    }
}
