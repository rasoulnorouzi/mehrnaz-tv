package app.bolbol.tv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PrefsRepository
import app.bolbol.tv.core.model.SubtitleStyle
import app.bolbol.tv.core.model.UpdateRepository
import app.bolbol.tv.core.model.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * @param updates the in-app updater (core:update); [NoUpdates] until it is wired.
 * @param updateScope scope for check/download work. Pass an application-lifetime scope so a download keeps going
 *   when the user leaves Settings; the default is a private process-lifetime scope.
 * @param manifestUrlOverride / [setManifestUrlOverride] developer field: the saved update.json URL ("" = default).
 */
class SettingsViewModel(
    private val prefs: PrefsRepository,
    private val appSettings: AppSettings,
    private val currentMirror: () -> String,
    private val resetMirrors: () -> Unit,
    val versionName: String,
    private val updates: UpdateRepository = NoUpdates,
    private val updateScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    manifestUrlOverride: () -> String = { "" },
    private val setManifestUrlOverride: (String) -> Unit = {},
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

    /** Update progress for the «به‌روزرسانی» page. */
    val updateState: StateFlow<UpdateState> = updates.state

    private val _manifestUrl = MutableStateFlow(manifestUrlOverride())

    /** The developer override of the update.json URL ("" = the real address). */
    val manifestUrl: StateFlow<String> = _manifestUrl.asStateFlow()

    /** [force] = the user pressed the button; false = page opened (skipped if checked in the last 24 h). */
    fun checkForUpdate(force: Boolean) {
        updateScope.launch { updates.check(force) }
    }

    fun downloadAndInstall() {
        updateScope.launch { updates.downloadAndInstall() }
    }

    fun openInstallPermissionSettings() = updates.openInstallPermissionSettings()

    fun onManifestUrl(url: String) {
        val trimmed = url.trim()
        _manifestUrl.value = trimmed
        setManifestUrlOverride(trimmed)
    }

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
            updates: UpdateRepository = NoUpdates,
            updateScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            manifestUrlOverride: () -> String = { "" },
            setManifestUrlOverride: (String) -> Unit = {},
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    prefs, appSettings, currentMirror, resetMirrors, versionName,
                    updates, updateScope, manifestUrlOverride, setManifestUrlOverride,
                )
            }
        }
    }
}

/** Stand-in until the real updater is wired: nothing to check, nothing to install. */
internal object NoUpdates : UpdateRepository {
    override val state: StateFlow<UpdateState> = MutableStateFlow<UpdateState>(UpdateState.Idle).asStateFlow()
    override suspend fun check(force: Boolean) = Unit
    override suspend fun downloadAndInstall() = Unit
    override fun openInstallPermissionSettings() = Unit
}
