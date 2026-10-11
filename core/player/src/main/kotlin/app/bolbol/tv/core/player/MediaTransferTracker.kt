@file:OptIn(UnstableApi::class)

package app.bolbol.tv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** What the network did for one watched URL since [MediaTransferTracker.watch] (times from the tracker's clock). */
data class TransferSnapshot(
    val watchedAtMs: Long = -1,
    /** HTTP requests opened for the URL (the first has no Range header; ExoPlayer then seeks with a Range request). */
    val requests: Int = 0,
    /** Response headers of the first request. */
    val firstResponseAtMs: Long = -1,
    /** Response headers of the latest request (the range request at the start position, after the moov box). */
    val lastResponseAtMs: Long = -1,
    val lastRequestAtMs: Long = -1,
    val lastByteAtMs: Long = -1,
    val bytes: Long = 0,
)

/**
 * Media3 [TransferListener] on the media data source (called on loader threads, hence the locks):
 * counts requests, response times and bytes for one URL at a time, the stream being switched to.
 * Feeds the switch watchdog ("are bytes still arriving?") and the `BolbolSwitch` breakdown log.
 */
class MediaTransferTracker(private val nowMs: () -> Long) : TransferListener {
    // Plain fields under the lock: onBytes runs hundreds of times a second while loading, so it must not allocate.
    private var url: String? = null
    private var watchedAtMs = -1L
    private var requests = 0
    private var firstResponseAtMs = -1L
    private var lastResponseAtMs = -1L
    private var lastRequestAtMs = -1L
    private var lastByteAtMs = -1L
    private var bytes = 0L

    /** Starts recording for [url], dropping what was recorded before. */
    @Synchronized
    fun watch(url: String) {
        this.url = url
        watchedAtMs = nowMs()
        requests = 0
        firstResponseAtMs = -1
        lastResponseAtMs = -1
        lastRequestAtMs = -1
        lastByteAtMs = -1
        bytes = 0
    }

    @Synchronized
    fun snapshot(): TransferSnapshot =
        TransferSnapshot(watchedAtMs, requests, firstResponseAtMs, lastResponseAtMs, lastRequestAtMs, lastByteAtMs, bytes)

    /** Last time a byte of the watched URL arrived, -1 if none yet (watchdog input). */
    @Synchronized
    fun lastByteAtMs(): Long = lastByteAtMs

    @Synchronized
    internal fun onRequest(url: String) {
        if (url != this.url) return
        requests++
        lastRequestAtMs = nowMs()
    }

    @Synchronized
    internal fun onResponse(url: String) {
        if (url != this.url) return
        val now = nowMs()
        if (firstResponseAtMs < 0) firstResponseAtMs = now
        lastResponseAtMs = now
    }

    @Synchronized
    internal fun onBytes(url: String, count: Int) {
        if (url != this.url) return
        bytes += count
        lastByteAtMs = nowMs()
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork) onRequest(dataSpec.uri.toString())
    }

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork) onResponse(dataSpec.uri.toString())
    }

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork) onBytes(dataSpec.uri.toString(), bytesTransferred)
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
}
