package app.bolbol.tv.core.player

import android.util.Log
import app.bolbol.tv.core.model.StreamOption
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Which URLs to touch for a pre-warm (pure, unit-tested). */
object PrewarmPlan {

    fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host

    /**
     * One URL per distinct host among [streams], highest quality first (the likely switch target),
     * skipping [currentUrl]'s host (its connection is already open) and hosts warmed less than
     * [minIntervalMs] ago according to [lastWarmedAtMs] (host → time).
     */
    fun pick(
        streams: List<StreamOption>,
        currentUrl: String?,
        lastWarmedAtMs: Map<String, Long>,
        nowMs: Long,
        minIntervalMs: Long = PlayerTuning.PREWARM_MIN_INTERVAL_MS,
    ): List<String> {
        val currentHost = currentUrl?.let(::hostOf)
        val seen = HashSet<String>()
        val picked = ArrayList<String>()
        for (stream in streams.sortedByDescending { it.quality }) {
            val host = hostOf(stream.url) ?: continue
            if (host == currentHost || !seen.add(host)) continue
            val last = lastWarmedAtMs[host]
            if (last != null && nowMs - last < minIntervalMs) continue
            picked += stream.url
        }
        return picked
    }
}

/**
 * Opens DNS + TCP + TLS to the other qualities' CDN hosts before the viewer switches (v1.1-2):
 * one `Range: bytes=0-0` request per host (1 byte of body), sent asynchronously on OkHttp's
 * dispatcher (never the main thread). The connection then waits in the shared pool for
 * ExoPlayer's first request, and the TLS session is cached for the second one.
 *
 * Evidence: a movie's 1080p and 480p files are on one host, 720p on another, and a cold DNS
 * lookup took 1.7 s from the dev machine; "go lower, then back up" fixed the stall because the
 * lower quality warmed the 1080p host.
 *
 * [warm] is called from the main thread only (the host map is not locked).
 */
class ConnectionPrewarmer(
    private val callFactory: Call.Factory,
    private val userAgent: String?,
    private val nowMs: () -> Long,
) {
    private val lastWarmedAtMs = HashMap<String, Long>()

    /** Returns the hosts a request was sent to. */
    fun warm(streams: List<StreamOption>, currentUrl: String?): List<String> {
        val now = nowMs()
        val urls = PrewarmPlan.pick(streams, currentUrl, lastWarmedAtMs, now)
        return urls.mapNotNull { url ->
            val host = PrewarmPlan.hostOf(url) ?: return@mapNotNull null
            lastWarmedAtMs[host] = now
            send(url, host)
            host
        }
    }

    private fun send(url: String, host: String) {
        val request = try {
            Request.Builder().url(url).header("Range", "bytes=0-0")
                .apply { userAgent?.let { header("User-Agent", it) } }
                .build()
        } catch (e: IllegalArgumentException) {
            return
        }
        val started = nowMs()
        callFactory.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    // Read the 1-byte body to the end so the connection goes back to the pool. A server that
                    // ignores Range (200 + whole file) is just closed: that connection is dropped, not downloaded.
                    if (it.code == 206 && it.body.contentLength() in 0..16) it.body.bytes()
                }
                Log.i(SWITCH_TAG, "prewarm host=$host http=${response.code} in ${nowMs() - started}ms")
            }

            override fun onFailure(call: Call, e: IOException) {
                Log.i(SWITCH_TAG, "prewarm host=$host failed after ${nowMs() - started}ms: ${e.javaClass.simpleName}")
            }
        })
    }

    companion object {
        /** Logcat tag for the quality-switch breakdown and pre-warm lines. */
        const val SWITCH_TAG = "BolbolSwitch"
    }
}
