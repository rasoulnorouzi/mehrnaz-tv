@file:OptIn(UnstableApi::class)

package app.bolbol.tv.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.Call

/**
 * Builds the app's only kind of [ExoPlayer]. Create one per player screen and release it when
 * the screen goes away (docs/decisions.md §Player: one instance at a time on a 2 GB TV).
 *
 * @param callFactory the app's shared OkHttpClient, so media requests reuse its connection
 *   pool, DNS cache and interceptors.
 * @param userAgent optional User-Agent for CDN requests (OkHttp's default otherwise).
 */
class PlayerFactory(
    context: Context,
    private val callFactory: Call.Factory,
    private val userAgent: String? = null,
) {
    private val appContext = context.applicationContext

    /** Must be called on the main thread; the player's application looper is the caller's. */
    fun create(): ExoPlayer {
        val dataSourceFactory = OkHttpDataSource.Factory(callFactory).apply {
            userAgent?.let { setUserAgent(it) }
        }
        val renderersFactory = DefaultRenderersFactory(appContext)
            // If the MTK hardware decoder fails to init (e.g. after another app leaked it),
            // fall back to the next decoder instead of failing playback.
            .setEnableDecoderFallback(true)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(appContext, renderersFactory, DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(createLoadControl())
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(PlayerTuning.SEEK_STEP_MS)
            .setSeekForwardIncrementMs(PlayerTuning.SEEK_STEP_MS)
            .build()
    }

    companion object {
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
