@file:OptIn(UnstableApi::class)

package app.bolbol.tv.core.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PlaybackRequest
import app.bolbol.tv.core.model.PrefsRepository
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.SubtitleStyle
import app.bolbol.tv.core.model.WatchRepository
import app.bolbol.tv.core.player.subtitles.SubtitleLoader
import app.bolbol.tv.core.player.subtitles.SubtitleOffset
import app.bolbol.tv.core.player.subtitles.SubtitleSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/** Coarse player state for the UI (changes on player events only). */
data class PlayerStatus(
    /** Player.STATE_IDLE / BUFFERING / READY / ENDED. */
    val playbackState: Int = Player.STATE_IDLE,
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    /** 0 while unknown. */
    val durationMs: Long = 0,
    /** Display aspect ratio of the video; 16:9 until the first frame size is known. */
    val videoAspectRatio: Float = 16f / 9f,
    val stream: StreamOption? = null,
) {
    val isBuffering: Boolean get() = playbackState == Player.STATE_BUFFERING
    val isEnded: Boolean get() = playbackState == Player.STATE_ENDED
}

/** One-shot things the UI should tell the user about (each is delivered once). */
sealed interface PlayerEvent {
    /** The .vtt couldn't be fetched or parsed: toast "زیرنویس بارگذاری نشد", keep playing. */
    data object SubtitleLoadFailed : PlayerEvent
}

/** Playhead numbers, refreshed every [PlayerTuning.TICK_INTERVAL_MS] and right after seeks. */
data class PlaybackPosition(
    val positionMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * Drives one [ExoPlayer] for one player screen: starts streams, switches quality/audio, saves
 * watch progress, runs the subtitle engine (subtitles/: load, parse, sync, offset, style) and
 * collects diagnostics. UI-free.
 *
 * Threading: call everything on the main thread, and give a [scope] on the main dispatcher
 * (ExoPlayer's application thread). The [scope] must outlive [release]: the final progress
 * write is launched in it. PlayerViewModel therefore uses a private scope, not viewModelScope
 * (which is already cancelled when onCleared runs).
 *
 * The caller owns [player] and releases it after [release].
 *
 * @param subtitleLoader fetches the side-loaded .vtt files; null = no subtitles (tests).
 */
class PlaybackController(
    val player: ExoPlayer,
    private val watch: WatchRepository,
    private val prefs: PrefsRepository,
    private val scope: CoroutineScope,
    private val subtitleLoader: SubtitleLoader?,
    private val clock: PlaybackClock = PlaybackClock.System,
    private val speedMemory: PlaybackSpeedMemory = PlaybackSpeedMemory.Session,
) {
    private val _status = MutableStateFlow(PlayerStatus())
    val status: StateFlow<PlayerStatus> = _status.asStateFlow()

    private val _position = MutableStateFlow(PlaybackPosition())
    val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

    private val subtitleSync = SubtitleSync(scope, positionMs = { player.currentPosition })

    /** Subtitle lines on screen now (credits dropped, bidi-fixed); emits only when they change. */
    val subtitleLines: StateFlow<List<String>> = subtitleSync.lines

    private val _subtitleStyle = MutableStateFlow(SubtitleStyle())

    /** The user's global subtitle look (PrefsRepository.subtitleStyle), applied live. */
    val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle.asStateFlow()

    private val _subtitleOffsetMs = MutableStateFlow(0)

    /** This title's sync offset in ms (positive = subtitles later), read on [start]. */
    val subtitleOffsetMs: StateFlow<Int> = _subtitleOffsetMs.asStateFlow()

    /**
     * Playback speed (v1.2-1), shared by every controller of the app session through [speedMemory]
     * (not persisted). Change it with [setSpeed]; it is re-applied on every prepare.
     */
    val speed: StateFlow<Float> = speedMemory.speed

    private val eventChannel = Channel<PlayerEvent>(Channel.BUFFERED)

    /** One-shot events for toasts. Collect from one place only (the screen). */
    val events: Flow<PlayerEvent> = eventChannel.receiveAsFlow()

    private val _diagnostics = MutableStateFlow(PlaybackDiagnostics())
    val diagnostics: StateFlow<PlaybackDiagnostics> = _diagnostics.asStateFlow()

    private val _error = MutableStateFlow<PlaybackError?>(null)
    val error: StateFlow<PlaybackError?> = _error.asStateFlow()

    private val _switchingQuality = MutableStateFlow(false)

    /**
     * True from [switchStream] until the new stream's first frame (or READY); false after an
     * error. The UI keeps the last frame and shows «در حال تغییر کیفیت…» (QualitySwitchIndicator)
     * instead of the buffering spinner.
     */
    val switchingQuality: StateFlow<Boolean> = _switchingQuality.asStateFlow()

    var request: PlaybackRequest? = null
        private set
    var stream: StreamOption? = null
        private set

    private val savePolicy = ProgressSavePolicy()
    private val statsListener = PlaybackStatsListener(/* keepHistory = */ false, /* callback = */ null)
    private var videoAspectRatio = 16f / 9f
    private var ttffStartMs = NOT_MEASURING
    private var ttffIsSwitch = false
    private var droppedFrames = 0L
    /** Reloads the stream in place when playback keeps dropping frames (v1.5.1, [StutterGuard]). */
    private val stutterGuard = StutterGuard()
    private var decoderName: String? = null
    private var bandwidthEstimate = -1L
    private var tickJob: Job? = null
    private var stoppedByLifecycle = false
    private var released = false

    /** Finished on purpose (auto-next): every later save keeps the item completed. */
    private var markedCompleted = false
    private var subtitleUrl: String? = null
    private var subtitleJob: Job? = null
    private var offsetPostId: Long? = null
    private var offsetLoadJob: Job? = null
    private var offsetSaveJob: Job? = null
    private var styleJob: Job? = null

    /** Tracker + pre-warmer of players built by [PlayerFactory]; null for other players (tests). */
    private val network: MediaNetwork? = PlayerFactory.networkFor(player)
    private val switchPolicy = StreamSwitchPolicy()
    private var switchTimeline: SwitchTimeline? = null
    private var switchJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishStatus()
            publishPosition()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) saveProgress(SaveReason.PAUSE)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            subtitleSync.setPlaying(isPlaying)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            subtitleSync.update()
            stutterGuard.restartWindow() // a seek drops frames on purpose
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) saveProgress(SaveReason.ENDED)
            if (playbackState == Player.STATE_READY) onSwitchReady()
        }

        override fun onTracksChanged(tracks: Tracks) {
            // Non-empty tracks after a switch = the new file's moov box is downloaded and parsed.
            val t = switchTimeline ?: return
            if (!tracks.isEmpty && t.preparedAtMs < 0) switchTimeline = t.copy(preparedAtMs = clock.elapsedRealtimeMs())
        }

        override fun onPlayerError(error: PlaybackException) {
            val mapped = PlaybackError.from(error)
            Log.w(TAG, "playback error ${mapped.codeName} http=${mapped.httpStatus} kind=${mapped.kind}", error)
            _error.value = mapped
            endSwitch("error_${mapped.kind}")
            saveProgress(SaveReason.ERROR)
        }

        override fun onRenderedFirstFrame() {
            if (ttffStartMs == NOT_MEASURING) return
            val ttff = clock.elapsedRealtimeMs() - ttffStartMs
            ttffStartMs = NOT_MEASURING
            _diagnostics.value = _diagnostics.value.copy(ttffMs = ttff)
            val s = stream
            val what = "stream=${s?.quality}p/${s?.audio}"
            // tools/tv.sh perf greps "BolbolPerf ttff=<n>ms": keep this format stable.
            Log.i(PERF_TAG, if (ttffIsSwitch) "stream_switch=${ttff}ms $what" else "ttff=${ttff}ms $what")
            if (ttffIsSwitch) onSwitchFirstFrame() else prewarmOtherStreams()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoAspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                publishStatus()
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@PlaybackController.droppedFrames += droppedFrames
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            this@PlaybackController.decoderName = decoderName
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            val t = switchTimeline ?: return
            switchTimeline = t.copy(loadErrors = t.loadErrors + 1)
            Log.i(SWITCH_TAG, "load error during switch: ${error.javaClass.simpleName} (Media3 retries it)")
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            bandwidthEstimate = bitrateEstimate
        }
    }

    init {
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        player.addAnalyticsListener(statsListener)
        styleJob = scope.launch {
            safely("subtitleStyle") {
                prefs.subtitleStyle().collect { style ->
                    _subtitleStyle.value = style
                    subtitleSync.enabled = style.enabled
                }
            }
        }
    }

    /**
     * Starts [stream] of [request] at [startPositionMs]. With [playWhenReady] = false the player
     * prepares and shows the first frame paused (used behind the resume prompt so "continue"
     * starts instantly). TTFF is measured from this call. Also loads the stream's subtitles and
     * the title's saved sync offset.
     */
    fun start(request: PlaybackRequest, stream: StreamOption, startPositionMs: Long, playWhenReady: Boolean = true) {
        if (released) return
        if (this.request != null) saveProgress(SaveReason.SWITCH)
        endSwitch("replaced")
        val start = startPositionMs.coerceAtLeast(0)
        this.request = request
        this.stream = stream
        _error.value = null
        stoppedByLifecycle = false
        markedCompleted = false
        savePolicy.reset(clock.elapsedRealtimeMs(), start)
        beginTtff(isSwitch = false)
        stutterGuard.reset()
        // Resuming mid-film starts at the keyframe before the saved spot, like a quality switch: an exact start makes the
        // TV decode up to 10 s of hidden frames first, which on the MT9611 could leave playback choppy (v1.5.1).
        player.setMediaItem(stream.toMediaItem(request, keyframeStart = start > 0), start)
        player.playWhenReady = playWhenReady
        applySpeed()
        player.prepare()
        loadSubtitleOffset(request.key.postId)
        loadSubtitles(stream)
        startTicker()
        publishStatus()
        publishPosition()
    }

    /**
     * Switches quality/audio keeping the position, and remembers the choice for this title
     * (and globally when [rememberGlobally]). Also used to recover from an error.
     */
    fun switchStream(stream: StreamOption, rememberGlobally: Boolean = true) {
        val request = request ?: return
        if (released || (stream == this.stream && _error.value == null)) return
        saveProgress(SaveReason.SWITCH)
        val from = this.stream
        val position = player.currentPosition.coerceAtLeast(0)
        val play = player.playWhenReady || _error.value != null
        this.stream = stream
        _error.value = null
        stoppedByLifecycle = false
        savePolicy.reset(clock.elapsedRealtimeMs(), position)
        beginTtff(isSwitch = true)
        beginSwitch(from, stream, position)
        stutterGuard.restartWindow()
        // Keyframe start (KeyframeStart): the first picture needs no hidden decoding of up to 10 s of frames.
        player.setMediaItem(stream.toMediaItem(request, keyframeStart = true), position)
        player.playWhenReady = play
        applySpeed()
        player.prepare()
        loadSubtitles(stream)
        publishStatus()
        rememberChoice(request.key.postId, stream, rememberGlobally)
    }

    /** Retries the current stream after an error, from the same position. */
    fun retry() {
        if (released || request == null) return
        _error.value = null
        player.playWhenReady = true
        applySpeed()
        player.prepare()
    }

    /**
     * Sets the playback speed (snapped to [PlaybackSpeeds.CHOICES]) for this and every later player of
     * the app session, and applies it at once (also while paused). Pitch stays natural (Media3's
     * default `setPlaybackSpeed`). Returns the applied speed.
     */
    fun setSpeed(speed: Float): Float {
        val value = speedMemory.set(speed)
        if (released) return value
        applySpeed()
        subtitleSync.update()
        return value
    }

    fun play() {
        if (released || request == null) return
        when {
            _error.value != null -> retry()
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0); player.play() }
            player.playbackState == Player.STATE_IDLE -> { applySpeed(); player.prepare(); player.play() }
            else -> player.play()
        }
    }

    fun pause() {
        if (!released) player.pause()
    }

    fun togglePlayPause() {
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        if (released || request == null) return
        player.seekTo(positionMs.coerceAtLeast(0))
        publishPosition()
        subtitleSync.update()
    }

    /** Relative seek, clamped (see [SeekSteps.target]). Returns the target position. */
    fun seekBy(deltaMs: Long): Long {
        val target = SeekSteps.target(player.currentPosition, deltaMs, knownDurationMs())
        seekTo(target)
        return target
    }

    /** Scrubbing mode while an arrow key auto-repeats: fast, approximate seeks. */
    fun setScrubbing(enabled: Boolean) {
        if (released || player.isScrubbingModeEnabled == enabled) return
        player.setScrubbingModeEnabled(enabled)
    }

    /**
     * Warms DNS + TCP + TLS to the CDN hosts of this title's other streams (one tiny request per
     * host, at most once a minute per host, off the main thread; see [ConnectionPrewarmer]).
     * Called once after the first frame of [start]; call it again when the quality/audio picker
     * opens, so the connection is fresh when the viewer chooses.
     */
    fun prewarmOtherStreams() {
        val request = request ?: return
        if (released) return
        network?.prewarmer?.warm(request.streams, stream?.url)
    }

    /**
     * Sets this title's subtitle sync offset (clamped to ±120 s and rounded to 0.5 s steps, see
     * [SubtitleOffset]), applies it at once and saves it per title. Saving is debounced
     * ([OFFSET_SAVE_DEBOUNCE_MS]) so holding a step button doesn't write 8 rows a second.
     * Returns the applied value.
     */
    fun setSubtitleOffset(offsetMs: Int): Int {
        val postId = request?.key?.postId
        if (released || postId == null) return _subtitleOffsetMs.value
        val value = SubtitleOffset.normalize(offsetMs)
        offsetLoadJob?.cancel() // the user's choice wins over a late read
        _subtitleOffsetMs.value = value
        subtitleSync.offsetMs = value
        offsetSaveJob?.cancel()
        offsetSaveJob = scope.launch {
            delay(OFFSET_SAVE_DEBOUNCE_MS)
            safely("saveSubtitleOffset") { prefs.saveSubtitleOffsetMs(postId, value) }
        }
        return value
    }

    /** Applies [style] live and saves it as the global subtitle style. */
    fun setSubtitleStyle(style: SubtitleStyle) {
        if (released || style == _subtitleStyle.value) return
        _subtitleStyle.value = style
        subtitleSync.enabled = style.enabled
        scope.launch { safely("saveSubtitleStyle") { prefs.saveSubtitleStyle(style) } }
    }

    /**
     * The viewer is leaving this item finished (auto-next): saves it as completed now, and every
     * later save (pause, release, …) keeps it completed.
     */
    fun markCompleted() {
        if (released || request == null) return
        markedCompleted = true
        saveProgress(SaveReason.ENDED)
    }

    /** Screen went to the background: save, pause and free decoders/buffers. */
    fun onStop() {
        if (released || request == null) return
        saveProgress(SaveReason.STOP)
        endSwitch("stopped")
        if (player.playbackState != Player.STATE_IDLE) {
            stoppedByLifecycle = true
            player.pause()
            player.stop()
        }
    }

    /**
     * Screen is visible again: re-prepare (paused) at the same position if [onStop] stopped it.
     * Returns true in that case so the UI can show its (paused) controls.
     */
    fun onStart(): Boolean {
        if (released || !stoppedByLifecycle) return false
        stoppedByLifecycle = false
        if (_error.value == null) reloadInPlace("screen visible again")
        return true
    }

    /** ComponentCallbacks2.onTrimMemory: the process may be killed soon, save now. */
    fun onTrimMemory(@Suppress("UNUSED_PARAMETER") level: Int) {
        if (!released) saveProgress(SaveReason.TRIM_MEMORY)
    }

    /**
     * Final progress save + perf summary, then detaches from the player. The caller releases
     * the player afterwards. Safe to call twice.
     */
    fun release() {
        if (released) return
        saveProgress(SaveReason.RELEASE)
        endSwitch("released")
        publishDiagnostics()
        logSummary()
        released = true
        tickJob?.cancel()
        subtitleJob?.cancel()
        offsetLoadJob?.cancel()
        styleJob?.cancel()
        subtitleSync.release()
        player.removeListener(playerListener)
        player.removeAnalyticsListener(analyticsListener)
        player.removeAnalyticsListener(statsListener)
    }

    // ---- internals ------------------------------------------------------------------------

    /**
     * ExoPlayer keeps its playback parameters across media items, but we set the speed again before each
     * prepare so a new player, a re-prepare after [onStop] or a watchdog retry can never fall back to 1×.
     */
    private fun applySpeed() {
        val speed = speedMemory.speed.value
        if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
    }

    private fun beginTtff(isSwitch: Boolean) {
        ttffStartMs = clock.elapsedRealtimeMs()
        ttffIsSwitch = isSwitch
    }

    // ---- quality/audio switch (v1.1-2, perf/reports/v1.1-quality-switch.md) ------------------

    private fun beginSwitch(from: StreamOption?, to: StreamOption, positionMs: Long) {
        endSwitch("replaced")
        val now = clock.elapsedRealtimeMs()
        network?.tracker?.watch(to.url)
        switchTimeline = SwitchTimeline(
            startedAtMs = now,
            from = from?.tag() ?: "-",
            to = to.tag(),
            host = PrewarmPlan.hostOf(to.url) ?: "?",
            requestedPositionMs = positionMs,
        )
        _switchingQuality.value = true
        val firstCheckMs = switchPolicy.begin(now)
        switchJob = scope.launch { watchSwitch(firstCheckMs) }
    }

    private suspend fun watchSwitch(firstCheckMs: Long) {
        var delayMs = firstCheckMs
        while (true) {
            delay(delayMs)
            if (released) return
            val lastByteAtMs = network?.tracker?.lastByteAtMs() ?: -1
            when (val action = switchPolicy.check(clock.elapsedRealtimeMs(), lastByteAtMs)) {
                StreamSwitchPolicy.Action.None -> return
                is StreamSwitchPolicy.Action.Wait -> delayMs = action.delayMs
                is StreamSwitchPolicy.Action.Reprepare -> {
                    reprepareSwitch()
                    delayMs = action.delayMs
                }
                StreamSwitchPolicy.Action.GiveUp -> {
                    giveUpSwitch()
                    return
                }
            }
        }
    }

    /** Watchdog: no picture and no bytes. Drop the stalled request and idle connections, load again. */
    private fun reprepareSwitch() {
        val request = request ?: return
        val stream = stream ?: return
        val t = switchTimeline ?: return
        switchTimeline = t.copy(reprepares = switchPolicy.reprepares)
        Log.i(SWITCH_TAG, "no first frame, no bytes ${clock.elapsedRealtimeMs() - t.startedAtMs}ms after switch: re-preparing ${stream.tag()}")
        network?.dropIdleConnections()
        player.setMediaItem(stream.toMediaItem(request, keyframeStart = true), t.requestedPositionMs)
        applySpeed()
        player.prepare()
    }

    /** Watchdog gave up: stop loading and show the error panel (retry / «کیفیت پایین‌تر»). */
    private fun giveUpSwitch() {
        val code = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        finishSwitch("gave_up")
        ttffStartMs = NOT_MEASURING
        player.stop() // retry() → prepare() then starts a fresh load at the same position
        _error.value = PlaybackError(PlaybackError.Kind.NETWORK, code, null, PlaybackException.getErrorCodeName(code))
        saveProgress(SaveReason.ERROR)
        publishStatus()
    }

    private fun onSwitchFirstFrame() {
        val t = switchTimeline ?: return
        val now = clock.elapsedRealtimeMs()
        val updated = t.copy(firstFrameAtMs = now, startPositionMs = player.currentPosition)
        switchTimeline = updated
        stopSwitchWatch()
        val moov = if (updated.preparedAtMs >= 0) " (moov ${updated.preparedAtMs - updated.startedAtMs})" else ""
        _diagnostics.value = _diagnostics.value.copy(lastSwitch = "${updated.to} ${now - updated.startedAtMs} ms$moov")
        if (updated.readyAtMs >= 0) finishSwitch("ok")
    }

    private fun onSwitchReady() {
        val t = switchTimeline ?: return
        val updated = if (t.readyAtMs < 0) t.copy(readyAtMs = clock.elapsedRealtimeMs()) else t
        switchTimeline = updated
        stopSwitchWatch() // buffered, so not stalled; the picture follows
        if (updated.firstFrameAtMs >= 0) finishSwitch("ok")
    }

    private fun stopSwitchWatch() {
        switchPolicy.end()
        switchJob?.cancel()
        switchJob = null
        _switchingQuality.value = false
    }

    /** Ends a running switch, if any, and logs it with [result]. */
    private fun endSwitch(result: String) {
        if (switchTimeline != null || _switchingQuality.value) finishSwitch(result)
    }

    private fun finishSwitch(result: String) {
        stopSwitchWatch()
        val t = switchTimeline ?: return
        switchTimeline = null
        val done = t.copy(reprepares = switchPolicy.reprepares, transfer = network?.tracker?.snapshot() ?: t.transfer)
        Log.i(SWITCH_TAG, done.logLine(result))
    }

    private fun StreamOption.tag(): String = "${quality}p/$audio"

    private fun knownDurationMs(): Long = player.duration.let { if (it == C.TIME_UNSET || it < 0) 0 else it }

    private fun saveProgress(reason: SaveReason) {
        val request = request ?: return
        val progress = savePolicy.evaluate(
            key = request.key,
            positionMs = player.currentPosition,
            durationMs = knownDurationMs(),
            ended = markedCompleted || player.playbackState == Player.STATE_ENDED,
            reason = reason,
            nowElapsedMs = clock.elapsedRealtimeMs(),
            nowWallMs = clock.currentTimeMs(),
        ) ?: return
        val title = request.title
        scope.launch { safely("saveProgress") { watch.saveProgress(title, progress) } }
    }

    private fun rememberChoice(postId: Long, stream: StreamOption, rememberGlobally: Boolean) {
        scope.launch {
            safely("rememberChoice") {
                prefs.saveTitlePrefs(postId, PlaybackPrefs(audio = stream.audio, quality = stream.quality))
                if (rememberGlobally) {
                    val global = prefs.globalPrefs().first()
                    prefs.saveGlobalPrefs(global.copy(audio = stream.audio, quality = stream.quality))
                }
            }
        }
    }

    /**
     * Fetches [stream]'s .vtt unless that file is already loaded (or loading). Dubbed streams have
     * none. On failure a single [PlayerEvent.SubtitleLoadFailed] is sent and playback goes on.
     */
    private fun loadSubtitles(stream: StreamOption) {
        val url = stream.subtitleUrl?.takeIf { it.isNotBlank() }
        if (url != null && url == subtitleUrl && (subtitleSync.track != null || subtitleJob?.isActive == true)) return
        subtitleJob?.cancel()
        subtitleUrl = url
        subtitleSync.track = null
        val loader = subtitleLoader
        if (url == null || loader == null) return
        subtitleJob = scope.launch {
            val started = clock.elapsedRealtimeMs()
            try {
                val track = loader.load(url)
                if (!released && subtitleUrl == url) {
                    subtitleSync.track = track
                    Log.i(TAG, "subtitles: ${track.size} cues in ${clock.elapsedRealtimeMs() - started}ms")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "subtitle load failed: ${e.message}", e.cause)
                if (!released && subtitleUrl == url) eventChannel.trySend(PlayerEvent.SubtitleLoadFailed)
            }
        }
    }

    /** Reads the title's saved sync offset (once per title; episodes of a series share it). */
    private fun loadSubtitleOffset(postId: Long) {
        if (offsetPostId == postId) return
        offsetPostId = postId
        offsetLoadJob?.cancel()
        _subtitleOffsetMs.value = 0
        subtitleSync.offsetMs = 0
        offsetLoadJob = scope.launch {
            safely("subtitleOffset") {
                val saved = SubtitleOffset.normalize(prefs.subtitleOffsetMs(postId))
                _subtitleOffsetMs.value = saved
                subtitleSync.offsetMs = saved
            }
        }
    }

    private suspend fun safely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
        }
    }

    /**
     * Rebuilds the current stream at the current position from the keyframe before it (what a quality switch does,
     * without changing quality): fresh decoder, fresh source. Used when the screen becomes visible again after
     * [onStop] and when [StutterGuard] sees choppy playback. Keeps play/pause, speed and subtitles.
     */
    private fun reloadInPlace(reason: String) {
        val request = request ?: return
        val stream = stream ?: return
        if (released) return
        val position = player.currentPosition.coerceAtLeast(0)
        val play = player.playWhenReady
        Log.i(TAG, "reload in place ($reason) at ${position}ms")
        stutterGuard.restartWindow()
        player.setMediaItem(stream.toMediaItem(request, keyframeStart = true), position)
        player.playWhenReady = play
        applySpeed()
        player.prepare()
    }

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(PlayerTuning.TICK_INTERVAL_MS)
                tick()
            }
        }
    }

    private fun tick() {
        if (released) return
        val state = player.playbackState
        if (state == Player.STATE_IDLE) return
        publishPosition()
        publishDiagnostics()
        if (player.isPlaying) saveProgress(SaveReason.TICK)
        val playingSmoothly = player.isPlaying && !_switchingQuality.value
        if (stutterGuard.onTick(clock.elapsedRealtimeMs(), playingSmoothly, droppedFrames, player.videoFormat?.frameRate ?: 0f)) {
            reloadInPlace("stutter: dropped frames")
        }
    }

    private fun publishStatus() {
        if (released) return
        _status.value = PlayerStatus(
            playbackState = player.playbackState,
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying,
            durationMs = knownDurationMs(),
            videoAspectRatio = videoAspectRatio,
            stream = stream,
        )
    }

    private fun publishPosition() {
        if (released) return
        _position.value = PlaybackPosition(
            positionMs = player.currentPosition.coerceAtLeast(0),
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
            durationMs = knownDurationMs(),
        )
    }

    private fun publishDiagnostics() {
        val stats = statsListener.combinedPlaybackStats
        val format = player.videoFormat
        val size = player.videoSize
        _diagnostics.value = _diagnostics.value.copy(
            videoWidth = if (size.width > 0) size.width else format?.width?.coerceAtLeast(0) ?: 0,
            videoHeight = if (size.height > 0) size.height else format?.height?.coerceAtLeast(0) ?: 0,
            videoBitrate = format?.let { if (it.averageBitrate > 0) it.averageBitrate else it.bitrate } ?: -1,
            videoDecoder = decoderName,
            bufferedAheadMs = player.totalBufferedDuration.coerceAtLeast(0),
            droppedFrames = droppedFrames,
            rebufferCount = stats.totalRebufferCount,
            meanRebufferMs = stats.meanSingleRebufferTimeMs.orUnknown(),
            meanSeekMs = stats.meanSingleSeekTimeMs.orUnknown(),
            meanJoinMs = stats.meanJoinTimeMs.orUnknown(),
            bandwidthEstimate = bandwidthEstimate,
            speed = speedMemory.speed.value,
        )
    }

    private fun logSummary() {
        val d = _diagnostics.value
        if (d.ttffMs < 0 && d.meanJoinMs < 0) return
        Log.i(
            PERF_TAG,
            "summary rebuffers=${d.rebufferCount} meanRebufferMs=${d.meanRebufferMs} meanSeekMs=${d.meanSeekMs} " +
                "joinMs=${d.meanJoinMs} dropped=${d.droppedFrames} video=${d.videoWidth}x${d.videoHeight} " +
                "decoder=${d.videoDecoder}",
        )
    }

    private fun Long.orUnknown(): Long = if (this == C.TIME_UNSET || this < 0) -1 else this

    /**
     * Video only: subtitles are side-loaded by our own engine, never by ExoPlayer. With
     * [keyframeStart] the item starts at the keyframe before its start position ([KeyframeStart]).
     */
    private fun StreamOption.toMediaItem(request: PlaybackRequest, keyframeStart: Boolean = false): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaId("${request.key.asString()}@$quality/$audio")
            .apply { if (keyframeStart) setTag(KeyframeStart) }
            .build()

    companion object {
        /** Logcat tag grepped by tools/tv.sh perf. */
        const val PERF_TAG = "BolbolPerf"

        /** Logcat tag of the quality-switch breakdown (`switch … firstByte=… moov=… firstFrame=…`) and pre-warms. */
        const val SWITCH_TAG = ConnectionPrewarmer.SWITCH_TAG
        private const val TAG = "BolbolPlayer"
        private const val NOT_MEASURING = -1L
        private const val OFFSET_SAVE_DEBOUNCE_MS = 400L
    }
}
