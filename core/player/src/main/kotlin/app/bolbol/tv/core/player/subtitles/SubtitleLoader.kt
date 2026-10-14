package app.bolbol.tv.core.player.subtitles

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI

/** Thrown when a subtitle file can't be fetched or holds no usable cue. */
class SubtitleLoadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Fetches and parses a `.vtt` (docs/subtitles.md §Engine 1-2) off the main thread.
 *
 * Uses the app's shared OkHttp [callFactory], so its HTTP cache makes re-opening a title free.
 * Bodies above [maxBytes] are refused (a Persian movie .vtt is ~100 KB; the cap guards against a
 * mirror answering with a video or an HTML page).
 */
class SubtitleLoader(
    private val callFactory: Call.Factory,
    private val userAgent: String? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxBytes: Long = MAX_BYTES,
) {
    /**
     * Cancellable: cancelling the caller cancels the HTTP call. Throws [SubtitleLoadException].
     * A `file://` URL (a saved copy, v1.4 offline playback) is read from disk with the same size cap.
     */
    suspend fun load(url: String): SubtitleTrack = withContext(dispatcher) {
        if (url.startsWith("file:")) return@withContext loadFile(url)
        val request = try {
            Request.Builder().url(url).apply { userAgent?.let { header("User-Agent", it) } }.build()
        } catch (e: IllegalArgumentException) {
            throw SubtitleLoadException("bad url", e)
        }
        val call = callFactory.newCall(request)
        val cancelHandle = coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            val text = call.execute().use { response ->
                if (!response.isSuccessful) throw SubtitleLoadException("HTTP ${response.code}")
                val body = response.body
                if (body.contentLength() > maxBytes) throw SubtitleLoadException("too large: ${body.contentLength()} B")
                val source = body.source()
                if (source.request(maxBytes + 1)) throw SubtitleLoadException("too large: > $maxBytes B")
                source.buffer.readUtf8()
            }
            val track = VttParser.parse(text)
            if (track.size == 0) throw SubtitleLoadException("no cues")
            track
        } catch (e: SubtitleLoadException) {
            throw e
        } catch (e: IOException) {
            throw SubtitleLoadException("fetch failed", e)
        } finally {
            cancelHandle.dispose()
        }
    }

    private fun loadFile(url: String): SubtitleTrack {
        val file = try {
            File(URI(url))
        } catch (e: IllegalArgumentException) {
            throw SubtitleLoadException("bad file url", e)
        }
        val length = file.length()
        if (!file.isFile) throw SubtitleLoadException("no file")
        if (length > maxBytes) throw SubtitleLoadException("too large: $length B")
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            throw SubtitleLoadException("read failed", e)
        }
        val track = VttParser.parse(text)
        if (track.size == 0) throw SubtitleLoadException("no cues")
        return track
    }

    companion object {
        /** docs/subtitles.md: size cap 2 MB. */
        const val MAX_BYTES: Long = 2L * 1024 * 1024
    }
}
