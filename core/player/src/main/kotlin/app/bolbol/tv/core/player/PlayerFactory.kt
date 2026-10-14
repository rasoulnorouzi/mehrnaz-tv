@file:OptIn(UnstableApi::class)

package app.bolbol.tv.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import android.os.SystemClock
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.bolbol.tv.core.player.subtitles.SubtitleLoader
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.WeakHashMap

/**
 * Builds the app's only kind of [ExoPlayer]. Create one per player screen and release it when
 * the screen goes away (docs/decisions.md §Player: one instance at a time on a 2 GB TV).
 *
 * @param callFactory the app's shared OkHttpClient. Subtitles use it as is (HTTP cache); video
 *   uses [MediaHttp.mediaClient] derived from it (same connection pool, no disk cache, shorter
 *   timeouts).
 * @param userAgent optional User-Agent for CDN requests (OkHttp's default otherwise).
 */
class PlayerFactory(
    context: Context,
    private val callFactory: Call.Factory,
    private val userAgent: String? = null,
) {
    private val appContext = context.applicationContext

    /** Video requests: the shared pool without the disk cache, with media timeouts (v1.1-2). */
    private val mediaCallFactory: Call.Factory by lazy { MediaHttp.mediaCallFactory(callFactory) }

    /**
     * Fetches the side-loaded .vtt files over the same [callFactory] (shared cache and pool).
     * Pass it to `PlayerViewModel.factory(subtitleLoader = …)`.
     */
    val subtitleLoader: SubtitleLoader by lazy { SubtitleLoader(callFactory, userAgent) }

    /** Must be called on the main thread; the player's application looper is the caller's. */
    fun create(): ExoPlayer {
        val tracker = MediaTransferTracker(nowMs = SystemClock::elapsedRealtime)
        val httpDataSourceFactory = OkHttpDataSource.Factory(mediaCallFactory).apply {
            userAgent?.let { setUserAgent(it) }
            setTransferListener(tracker)
        }
        // http(s) → OkHttp exactly as before; file:// (v1.4 saved copies, core:download) → FileDataSource.
        val dataSourceFactory = DefaultDataSource.Factory(appContext, httpDataSourceFactory)
        val mediaSourceFactory = KeyframeStartMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory).setLoadErrorHandlingPolicy(MediaLoadErrorPolicy()),
        )
        val renderersFactory = DefaultRenderersFactory(appContext)
            // If the MTK hardware decoder fails to init (e.g. after another app leaked it),
            // fall back to the next decoder instead of failing playback.
            .setEnableDecoderFallback(true)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(appContext, renderersFactory, mediaSourceFactory)
            .setLoadControl(createLoadControl())
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(PlayerTuning.SEEK_STEP_MS)
            .setSeekForwardIncrementMs(PlayerTuning.SEEK_STEP_MS)
            .build()
            .apply {
                // Subtitles are drawn by our own engine (subtitles/), never by ExoPlayer: no text
                // track is ever selected, so the text renderer stays idle.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                val prewarmer = ConnectionPrewarmer(mediaCallFactory, userAgent, SystemClock::elapsedRealtime)
                register(this, MediaNetwork(tracker, prewarmer, mediaCallFactory))
            }
    }

    companion object {
        /** Players built here → their media network helpers (weak keys: gone with the player). Main thread. */
        private val networks = WeakHashMap<ExoPlayer, MediaNetwork>()

        private fun register(player: ExoPlayer, network: MediaNetwork) = synchronized(networks) { networks[player] = network }

        /**
         * The tracker/pre-warmer of a player built by [create], null for any other player (tests).
         * Lets [PlaybackController] find them without a constructor change in the ViewModel.
         */
        internal fun networkFor(player: ExoPlayer): MediaNetwork? = synchronized(networks) { networks[player] }

        /** Explicit buffer policy; see [PlayerTuning] for why each number is what it is. */
        fun createLoadControl(): LoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                PlayerTuning.MIN_BUFFER_MS,
                PlayerTuning.MAX_BUFFER_MS,
                PlayerTuning.BUFFER_FOR_PLAYBACK_MS,
                PlayerTuning.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setTargetBufferBytes(PlayerTuning.TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(PlayerTuning.PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS)
            .setBackBuffer(PlayerTuning.BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
            .build()
    }
}

/** Per-player network helpers for the quality switch (v1.1-2). */
internal class MediaNetwork(
    val tracker: MediaTransferTracker,
    val prewarmer: ConnectionPrewarmer,
    private val callFactory: Call.Factory,
) {
    /**
     * Closes the pool's idle connections so the watchdog's retry opens a fresh one (the stalled
     * in-flight request is cancelled by the re-prepare itself). Rare path; API calls just reconnect.
     */
    fun dropIdleConnections() {
        (callFactory as? OkHttpClient)?.connectionPool?.evictAll()
    }
}
