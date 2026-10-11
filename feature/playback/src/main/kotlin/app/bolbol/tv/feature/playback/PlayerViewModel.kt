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
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.model.WatchRepository
import app.bolbol.tv.core.model.pick
import app.bolbol.tv.core.player.AutoNextInput
import app.bolbol.tv.core.player.AutoNextPolicy
import app.bolbol.tv.core.player.AutoNextState
import app.bolbol.tv.core.player.PlaybackClock
import app.bolbol.tv.core.player.PlaybackController
import app.bolbol.tv.core.player.PlayerFactory
import app.bolbol.tv.core.player.ResumeDecision
import app.bolbol.tv.core.player.ResumePolicy
import app.bolbol.tv.core.player.find
import app.bolbol.tv.core.player.forAudio
import app.bolbol.tv.core.player.nextLowerQuality
import app.bolbol.tv.core.player.subtitles.SubtitleLoader
import app.bolbol.tv.core.player.subtitles.SubtitleOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

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
)

/**
 * Owns the screen's single [ExoPlayer] (created by [createPlayer], released in [onCleared]) and
 * its [PlaybackController]. Built with [factory] (manual DI, docs/decisions.md §Architecture).
 * Also runs the next-episode countdown (M2.2, rules in [AutoNextPolicy]).
 */
class PlayerViewModel(
    val request: PlaybackRequest,
    createPlayer: () -> ExoPlayer,
    private val watch: WatchRepository,
    private val prefs: PrefsRepository,
    subtitleLoader: SubtitleLoader?,
    private val clock: PlaybackClock = PlaybackClock.System,
) : ViewModel() {

    /**
     * Not viewModelScope: that is cancelled before onCleared runs, and the final progress write
     * is launched from onCleared. Nothing long-lived runs here after release.
     */
    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val player: ExoPlayer = createPlayer()
    val controller = PlaybackController(
        player = player,
        watch = watch,
        prefs = prefs,
        scope = playerScope,
        subtitleLoader = subtitleLoader ?: fallbackSubtitleLoader(),
        clock = clock,
    )

    private val _screen = MutableStateFlow(PlayerScreenState())
    val screen: StateFlow<PlayerScreenState> = _screen.asStateFlow()

    private val _autoNext = MutableStateFlow<AutoNextState>(AutoNextState.Waiting)

    /** The next-episode card's state; stays [AutoNextState.Waiting] when [PlaybackRequest.next] is null. */
    val autoNext: StateFlow<AutoNextState> = _autoNext.asStateFlow()

    private val playNextChannel = Channel<WatchKey>(Channel.CONFLATED)

    /** Emits [PlaybackRequest.next] once, when the countdown ends, playback ends, or OK is pressed. */
    val playNext: Flow<WatchKey> = playNextChannel.receiveAsFlow()

    private var chromeCovering = false
    private var lastAutoNextStepMs = -1L

    init {
        viewModelScope.launch { begin() }
        if (request.next != null) watchAutoNext()
    }

    private suspend fun begin() {
        val global = orNull("globalPrefs") { prefs.globalPrefs().first() } ?: PlaybackPrefs()
        val titlePrefs = orNull("titlePrefs") { prefs.titlePrefs(request.key.postId) }
        val stream = request.streams.pick(titlePrefs ?: global)
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

    // ---- subtitles (docs/subtitles.md §Settings: the player's quick menu) ------------------

    fun setSubtitlesEnabled(enabled: Boolean) =
        controller.setSubtitleStyle(controller.subtitleStyle.value.copy(enabled = enabled))

    /** One of SubtitleStyle.SIZE_STEPS. */
    fun setSubtitleSize(scale: Float) =
        controller.setSubtitleStyle(controller.subtitleStyle.value.copy(sizeScale = scale))

    /** «زودتر» (-1) / «دیرتر» (+1) by 0.5 s. Returns the new offset in ms (for the toast). */
    fun shiftSubtitles(direction: Int): Int =
        controller.setSubtitleOffset(SubtitleOffset.nudge(controller.subtitleOffsetMs.value, direction))

    /** «تنظیم مجدد». Returns the new offset (0). */
    fun resetSubtitleOffset(): Int = controller.setSubtitleOffset(0)

    // ---- next episode (M2.2) -----------------------------------------------------------------

    /** OK on the card: mark this episode completed and leave for the next one now. */
    fun playNextNow() {
        val next = request.next ?: return
        if (_autoNext.value !is AutoNextState.Counting) return
        _autoNext.value = AutoNextState.PlayNext
        leaveFor(next)
    }

    /** BACK on the card: keep watching (it comes back once when playback ends). */
    fun dismissAutoNext() {
        _autoNext.value = AutoNextPolicy.dismiss(_autoNext.value, controller.status.value.isEnded)
    }

    /** The overlay or the side panel covers the card: its countdown is frozen meanwhile. */
    fun setChromeCovering(covering: Boolean) {
        if (chromeCovering == covering) return
        stepAutoNext() // account the time before the change with the old value
        chromeCovering = covering
    }

    private fun watchAutoNext() {
        // Player events (ended, paused, …) are handled at once; time in between by a light poll.
        viewModelScope.launch { controller.status.collect { stepAutoNext() } }
        viewModelScope.launch {
            while (isActive) {
                delay(if (_autoNext.value is AutoNextState.Counting) COUNTDOWN_TICK_MS else WINDOW_POLL_MS)
                stepAutoNext()
            }
        }
    }

    private fun stepAutoNext() {
        val next = request.next ?: return
        val now = clock.elapsedRealtimeMs()
        val elapsed = if (lastAutoNextStepMs < 0) 0 else now - lastAutoNextStepMs
        lastAutoNextStepMs = now
        val status = controller.status.value
        val input = AutoNextInput(
            positionMs = player.currentPosition,
            durationMs = status.durationMs,
            ended = status.isEnded,
            playing = status.isPlaying,
            held = chromeCovering || _screen.value.phase != PlayerPhase.Playing || controller.error.value != null,
        )
        val old = _autoNext.value
        val new = AutoNextPolicy.step(old, input, elapsed)
        if (new == old) return
        _autoNext.value = new
        if (new == AutoNextState.PlayNext) leaveFor(next)
    }

    private fun leaveFor(next: WatchKey) {
        controller.markCompleted()
        controller.pause()
        playNextChannel.trySend(next)
    }

    // ---- errors, lifecycle ---------------------------------------------------------------------

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
        Log.w(TAG, "$what failed", e)
        null
    }

    companion object {
        private const val TAG = "BolbolPlayer"
        private const val COUNTDOWN_TICK_MS = 200L
        private const val WINDOW_POLL_MS = 1_000L

        /**
         * @param createPlayer usually `{ playerFactory.create() }` (core:player PlayerFactory);
         *   called once, on the main thread.
         * @param subtitleLoader pass `playerFactory.subtitleLoader` (shared OkHttp client + cache).
         *   Without it a private fallback client is used and a warning is logged.
         */
        fun factory(
            request: PlaybackRequest,
            createPlayer: () -> ExoPlayer,
            watch: WatchRepository,
            prefs: PrefsRepository,
            clock: PlaybackClock = PlaybackClock.System,
            subtitleLoader: SubtitleLoader? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { PlayerViewModel(request, createPlayer, watch, prefs, subtitleLoader, clock) }
        }

        /** Preferred: player and subtitle loader from the app's single [PlayerFactory]. */
        fun factory(
            request: PlaybackRequest,
            playerFactory: PlayerFactory,
            watch: WatchRepository,
            prefs: PrefsRepository,
        ): ViewModelProvider.Factory =
            factory(request, { playerFactory.create() }, watch, prefs, subtitleLoader = playerFactory.subtitleLoader)

        private val fallbackLoader by lazy { SubtitleLoader(OkHttpClient()) }

        private fun fallbackSubtitleLoader(): SubtitleLoader {
            Log.w(TAG, "no SubtitleLoader injected: using a private OkHttpClient (pass playerFactory.subtitleLoader)")
            return fallbackLoader
        }
    }
}
