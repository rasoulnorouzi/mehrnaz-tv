@file:OptIn(UnstableApi::class)

package app.bolbol.tv.core.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
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

    private val eventChannel = Channel<PlayerEvent>(Channel.BUFFERED)

    /** One-shot events for toasts. Collect from one place only (the screen). */
    val events: Flow<PlayerEvent> = eventChannel.receiveAsFlow()

    private val _diagnostics = MutableStateFlow(PlaybackDiagnostics())
    val diagnostics: StateFlow<PlaybackDiagnostics> = _diagnostics.asStateFlow()

    private val _error = MutableStateFlow<PlaybackError?>(null)
    val error: StateFlow<PlaybackError?> = _error.asStateFlow()

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
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) saveProgress(SaveReason.ENDED)
        }

        override fun onPlayerError(error: PlaybackException) {
            val mapped = PlaybackError.from(error)
            Log.w(TAG, "playback error ${mapped.codeName} http=${mapped.httpStatus} kind=${mapped.kind}", error)
            _error.value = mapped
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
        val start = startPositionMs.coerceAtLeast(0)
        this.request = request
        this.stream = stream
        _error.value = null
        stoppedByLifecycle = false
        markedCompleted = false
        savePolicy.reset(clock.elapsedRealtimeMs(), start)
        beginTtff(isSwitch = false)
        player.setMediaItem(stream.toMediaItem(request), start)
        player.playWhenReady = playWhenReady
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
        val position = player.currentPosition.coerceAtLeast(0)
        val play = player.playWhenReady || _error.value != null
        this.stream = stream
        _error.value = null
        stoppedByLifecycle = false
        savePolicy.reset(clock.elapsedRealtimeMs(), position)
        beginTtff(isSwitch = true)
        player.setMediaItem(stream.toMediaItem(request), position)
        player.playWhenReady = play
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
        player.prepare()
    }

    fun play() {
        if (released || request == null) return
        when {
            _error.value != null -> retry()
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0); player.play() }
            player.playbackState == Player.STATE_IDLE -> { player.prepare(); player.play() }
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
     * Sets this title's subtitle sync offset (clamped to ±10 s and rounded to 0.5 s steps, see
     * [SubtitleOffset]), applies it at once and saves it per title. Returns the applied value.
     */
    fun setSubtitleOffset(offsetMs: Int): Int {
        val postId = request?.key?.postId
        if (released || postId == null) return _subtitleOffsetMs.value
        val value = SubtitleOffset.normalize(offsetMs)
        offsetLoadJob?.cancel() // the user's choice wins over a late read
        _subtitleOffsetMs.value = value
        subtitleSync.offsetMs = value
        offsetSaveJob?.cancel()
        offsetSaveJob = scope.launch { safely("saveSubtitleOffset") { prefs.saveSubtitleOffsetMs(postId, value) } }
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
        if (_error.value == null) player.prepare()
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

    private fun beginTtff(isSwitch: Boolean) {
        ttffStartMs = clock.elapsedRealtimeMs()
        ttffIsSwitch = isSwitch
    }

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

    /** Video only: subtitles are side-loaded by our own engine, never by ExoPlayer. */
    private fun StreamOption.toMediaItem(request: PlaybackRequest): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaId("${request.key.asString()}@$quality/$audio")
            .build()

    companion object {
        /** Logcat tag grepped by tools/tv.sh perf. */
        const val PERF_TAG = "BolbolPerf"
        private const val TAG = "BolbolPlayer"
        private const val NOT_MEASURING = -1L
    }
}
