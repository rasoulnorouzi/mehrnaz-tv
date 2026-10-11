@file:OptIn(UnstableApi::class)

package app.bolbol.tv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The HTTP client video bytes go through (v1.1-2). Derived from the app's shared client with
 * `newBuilder()`, so it shares the connection pool, dispatcher and DNS with the API and images.
 */
object MediaHttp {

    /** [shared] as a media client when it is an [OkHttpClient]; any other factory (tests) is used as is. */
    fun mediaCallFactory(shared: Call.Factory): Call.Factory =
        (shared as? OkHttpClient)?.let(::mediaClient) ?: shared

    /**
     * - **No disk cache.** ExoPlayer's first request for a progressive MP4 has no Range header, so
     *   the CDN answers `200` with the whole 0.4-2.6 GB file, `Last-Modified` and no
     *   `Cache-Control`: OkHttp counts that as cacheable and tees every byte read into the 50 MB
     *   API/image cache, and on close spends up to 100 ms reading on to "finish" the entry. The
     *   entry is then thrown away anyway. Pure waste on the TV's slow flash.
     * - **Shorter timeouts** ([PlayerTuning.MEDIA_CONNECT_TIMEOUT_MS], [PlayerTuning.MEDIA_READ_TIMEOUT_MS]):
     *   a stalled connection fails in 10 s instead of 20 s and the retry gets a fresh one.
     */
    fun mediaClient(shared: OkHttpClient): OkHttpClient =
        shared.newBuilder()
            .cache(null)
            .connectTimeout(PlayerTuning.MEDIA_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PlayerTuning.MEDIA_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
}

/** Retry rules for media loads (pure, unit-tested). Used by [MediaLoadErrorPolicy]. */
object MediaRetry {

    /**
     * 4xx means this file is gone or forbidden: retrying only delays the error panel (and its
     * «کیفیت پایین‌تر» button). 408 (timeout) and 429 (rate limit) are transient.
     */
    fun isRetryable(httpStatus: Int?): Boolean =
        httpStatus == null || httpStatus !in 400..499 || httpStatus == 408 || httpStatus == 429

    /**
     * Delay before retry number [errorCount] (1-based). Media3's default is 0, 1, 2, 3 … 5 s; we
     * retry sooner because the media client's timeouts already waited (a retry also gets a fresh
     * connection), and the next try is what the viewer is staring at.
     */
    fun delayMs(errorCount: Int): Long = when {
        errorCount <= 1 -> 200L
        errorCount == 2 -> 500L
        errorCount == 3 -> 1_000L
        else -> 2_000L
    }
}

/**
 * Media3's default policy (which errors are fatal, how many retries before the player reports an
 * error) with [MediaRetry]'s faster delays and fatal 4xx.
 */
class MediaLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val status = (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (!MediaRetry.isRetryable(status)) return C.TIME_UNSET
        val default = super.getRetryDelayMsFor(loadErrorInfo)
        return if (default == C.TIME_UNSET) C.TIME_UNSET else MediaRetry.delayMs(loadErrorInfo.errorCount)
    }
}
