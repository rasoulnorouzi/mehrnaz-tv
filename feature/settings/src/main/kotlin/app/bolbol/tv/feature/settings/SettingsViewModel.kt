package app.bolbol.tv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PrefsRepository
import app.bolbol.tv.core.model.SubtitleStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State and actions of [SettingsScreen]. Built with [factory] (manual DI, docs/decisions.md §Architecture).
 *
 * @param currentMirror the mirror host in use right now (read on demand; never cached here).
 * @param resetMirrors forgets the failover state so the next request starts at the first mirror.
 */
class SettingsViewModel(
    private val prefs: PrefsRepository,
    private val appSettings: AppSettings,
    private val currentMirror: () -> String,
    private val resetMirrors: () -> Unit,
    val versionName: String,
) : ViewModel() {

    private val subtitle = OptimisticValue(
        scope = viewModelScope,
        initial = SubtitleStyle(),
        source = prefs.subtitleStyle(),
        save = { prefs.saveSubtitleStyle(it) },
    )
    private val playback = OptimisticValue(
        scope = viewModelScope,
        initial = PlaybackPrefs(),
        source = prefs.globalPrefs(),
        save = { prefs.saveGlobalPrefs(it) },
    )

    /** The saved subtitle look; changes at once when the user edits it (drives the live preview). */
    val subtitleStyle: StateFlow<SubtitleStyle> = subtitle.state

    /** Default audio and quality (global). */
    val playbackPrefs: StateFlow<PlaybackPrefs> = playback.state

    val showPerformance: StateFlow<Boolean> = appSettings.showPerformance

    private val _mirror = MutableStateFlow(currentMirror())

    /** Host of the server in use. Call [refreshMirror] when the connection page is shown. */
    val mirror: StateFlow<String> = _mirror.asStateFlow()

    private val _mirrorResetDone = MutableStateFlow(false)

    /** True for a few seconds after [onResetMirrors] (the page shows a confirmation line). */
    val mirrorResetDone: StateFlow<Boolean> = _mirrorResetDone.asStateFlow()
    private var resetNoticeJob: Job? = null

    internal fun onSubtitleChange(change: SubtitleChange) = subtitle.update { it.reduce(change) }

    internal fun onPlaybackChange(change: PlaybackChange) = playback.update { it.reduce(change) }

    fun onShowPerformance(value: Boolean) = appSettings.setShowPerformance(value)

    fun refreshMirror() {
        _mirror.value = currentMirror()
    }

    fun onResetMirrors() {
        resetMirrors()
        refreshMirror()
        _mirrorResetDone.value = true
        resetNoticeJob?.cancel()
        resetNoticeJob = viewModelScope.launch {
            delay(RESET_NOTICE_MS)
            _mirrorResetDone.value = false
        }
    }

    companion object {
        const val RESET_NOTICE_MS = 3_000L

        fun factory(
            prefs: PrefsRepository,
            appSettings: AppSettings,
            currentMirror: () -> String,
            resetMirrors: () -> Unit,
            versionName: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(prefs, appSettings, currentMirror, resetMirrors, versionName) }
        }
    }
}
