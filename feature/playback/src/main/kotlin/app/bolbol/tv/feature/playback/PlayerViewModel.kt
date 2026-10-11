package app.bolbol.tv.feature.playback

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.exoplayer.ExoPlayer
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.PlaybackRequest
import app.bolbol.tv.core.model.PrefsRepository
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.WatchRepository
import app.bolbol.tv.core.model.pick
import app.bolbol.tv.core.player.PlaybackClock
import app.bolbol.tv.core.player.PlaybackController
import app.bolbol.tv.core.player.ResumeDecision
import app.bolbol.tv.core.player.ResumePolicy
import app.bolbol.tv.core.player.find
import app.bolbol.tv.core.player.forAudio
import app.bolbol.tv.core.player.nextLowerQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the player screen shows besides video. */
sealed interface PlayerPhase {
    /** Reading prefs/progress (a few ms). Black screen. */
    data object Loading : PlayerPhase

    /** Resume prompt; the player is already prepared, paused at [positionMs]. */
    data class AskResume(val positionMs: Long) : PlayerPhase

    data object Playing : PlayerPhase

    /** The request had no playable stream. */
    data object NoStreams : PlayerPhase
}

data class PlayerScreenState(
    val phase: PlayerPhase = PlayerPhase.Loading,
    /** Effective subtitle scale (title prefs, else global), 1.0 = default size. */
    val subtitleScale: Float = 1f,
)

/**
 * Owns the screen's single [ExoPlayer] (created by [createPlayer], released in [onCleared]) and
 * its [PlaybackController]. Built with [factory] (manual DI, docs/decisions.md §Architecture).
 */
class PlayerViewModel(
    val request: PlaybackRequest,
    createPlayer: () -> ExoPlayer,
    private val watch: WatchRepository,
    private val prefs: PrefsRepository,
    clock: PlaybackClock = PlaybackClock.System,
) : ViewModel() {

    /**
     * Not viewModelScope: that is cancelled before onCleared runs, and the final progress write
     * is launched from onCleared. Nothing long-lived runs here after release.
     */
    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val player: ExoPlayer = createPlayer()
    val controller = PlaybackController(player, watch, prefs, playerScope, clock)

    private val _screen = MutableStateFlow(PlayerScreenState())
    val screen: StateFlow<PlayerScreenState> = _screen.asStateFlow()

    init {
        viewModelScope.launch { begin() }
    }

    private suspend fun begin() {
        val global = orNull("globalPrefs") { prefs.globalPrefs().first() } ?: PlaybackPrefs()
        val titlePrefs = orNull("titlePrefs") { prefs.titlePrefs(request.key.postId) }
        val effective = titlePrefs ?: global
        _screen.update { it.copy(subtitleScale = effective.subtitleScale) }

        val stream = request.streams.pick(effective)
        if (stream == null) {
            _screen.update { it.copy(phase = PlayerPhase.NoStreams) }
            return
        }
        val saved: PlaybackProgress? =
            if (request.startPositionMs == null) orNull("progress") { watch.progress(request.key) } else null
        when (val decision = ResumePolicy.decide(saved, request.startPositionMs)) {
            ResumeDecision.StartFromBeginning -> startPlaying(stream, 0)
            is ResumeDecision.ResumeAt -> startPlaying(stream, decision.positionMs)
            is ResumeDecision.AskUser -> {
                // Prepare behind the prompt so "continue" (the focused default) starts instantly.
                controller.start(request, stream, decision.positionMs, playWhenReady = false)
                _screen.update { it.copy(phase = PlayerPhase.AskResume(decision.positionMs)) }
            }
        }
    }

    private fun startPlaying(stream: StreamOption, positionMs: Long) {
        controller.start(request, stream, positionMs)
        _screen.update { it.copy(phase = PlayerPhase.Playing) }
    }

    /** Resume prompt answer: true = continue from the saved position, false = from the start. */
    fun onResumeChoice(resume: Boolean) {
        if (_screen.value.phase !is PlayerPhase.AskResume) return
        if (!resume) controller.seekTo(0)
        controller.play()
        _screen.update { it.copy(phase = PlayerPhase.Playing) }
    }

    fun selectAudio(audio: AudioType) {
        val current = controller.stream ?: return
        if (current.audio == audio) return
        request.streams.forAudio(audio, current.quality)?.let { controller.switchStream(it) }
    }

    fun selectQuality(quality: Int) {
        val current = controller.stream ?: return
        if (current.quality == quality) return
        request.streams.find(current.audio, quality)?.let { controller.switchStream(it) }
    }

    fun setSubtitleScale(scale: Float) {
        if (_screen.value.subtitleScale == scale) return
        _screen.update { it.copy(subtitleScale = scale) }
        controller.saveSubtitleScale(scale)
    }

    /** True when the error panel can offer the lower-quality button. */
    fun canLowerQuality(): Boolean = controller.stream?.let { request.streams.nextLowerQuality(it) } != null

    fun retry() = controller.retry()

    /** Error fallback: next lower quality, remembered for this title only. */
    fun lowerQuality() {
        val current = controller.stream ?: return
        request.streams.nextLowerQuality(current)?.let { controller.switchStream(it, rememberGlobally = false) }
    }

    /** Called just before navigating away: stop sound now and save (release saves again, deduped). */
    fun prepareExit() = controller.pause()

    /** Returns true if playback had been stopped in the background and is now re-prepared. */
    fun onStart(): Boolean = controller.onStart()

    fun onStop() = controller.onStop()

    fun onTrimMemory(level: Int) = controller.onTrimMemory(level)

    override fun onCleared() {
        controller.release()
        player.release()
    }

    private suspend fun <T> orNull(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("BolbolPlayer", "$what failed", e)
        null
    }

    companion object {
        /**
         * @param createPlayer usually `{ playerFactory.create() }` (core:player PlayerFactory);
         *   called once, on the main thread.
         */
        fun factory(
            request: PlaybackRequest,
            createPlayer: () -> ExoPlayer,
            watch: WatchRepository,
            prefs: PrefsRepository,
            clock: PlaybackClock = PlaybackClock.System,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { PlayerViewModel(request, createPlayer, watch, prefs, clock) }
        }
    }
}
