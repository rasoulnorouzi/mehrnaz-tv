package app.bolbol.tv.core.lanremote

import java.io.IOException

/** Largest request body accepted by `POST /text`, in bytes. */
internal const val MAX_BODY_BYTES = 2048

/** Framework-free request description (the NanoHTTPD adapter builds it), so the rules below are unit-testable. */
internal class RemoteRequest(
    val method: String,
    /** Path without the query string, e.g. `/` or `/text`. */
    val path: String,
    /** Value of the `t` query parameter, if present. */
    val queryToken: String?,
    /** Declared `Content-Length`, or -1 when absent/unparsable. */
    val contentLength: Long,
    /** Remote address, the rate-limit key. */
    val clientId: String,
    /** Reads exactly the given number of body bytes (fewer on EOF). Only ever called after the size checks. */
    val readBody: (Int) -> ByteArray,
)

internal class RemoteReply(val status: Int, val contentType: String, val body: ByteArray) {
    companion object {
        fun text(status: Int, message: String) = RemoteReply(status, "text/plain; charset=utf-8", message.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Pure routing and security rules of the phone remote:
 * - at most ~20 requests per second per client (429 beyond that);
 * - every request must carry the 128-bit token, in `?t=` or (for `POST /text`) in the JSON body, else 403;
 * - only `GET /` (the page) and `POST /text` exist, anything else is 404;
 * - bodies are limited to [MAX_BODY_BYTES] (413), and the body is only read once the query token is valid (or absent).
 * The token is compared in constant time. [onText] receives the normalised text of each accepted POST.
 */
internal class RemoteRequestHandler(
    private val token: String,
    private val onText: (String) -> Unit,
    private val limiter: RateLimiter = RateLimiter(),
    private val page: ByteArray = RemotePage.bytes,
) {
    fun handle(request: RemoteRequest): RemoteReply {
        if (!limiter.tryAcquire(request.clientId)) return RemoteReply.text(429, "slow down")
        val queryTokenOk = tokenMatches(request.queryToken, token)
        return when {
            request.method == "GET" && request.path == "/" ->
                if (queryTokenOk) RemoteReply(200, "text/html; charset=utf-8", page) else forbidden()
            request.method == "POST" && request.path == "/text" -> postText(request, queryTokenOk)
            // Unknown route: do not reveal anything to callers without the token.
            else -> if (queryTokenOk) RemoteReply.text(404, "not found") else forbidden()
        }
    }

    private fun postText(request: RemoteRequest, queryTokenOk: Boolean): RemoteReply {
        // A wrong token in the query is final. Without a query token we have to authenticate through the body.
        if (request.queryToken != null && !queryTokenOk) return forbidden()
        val length = request.contentLength
        if (queryTokenOk) {
            if (length > MAX_BODY_BYTES) return RemoteReply.text(413, "body too large")
            if (length <= 0) return RemoteReply.text(400, "body required")
        } else if (length !in 1..MAX_BODY_BYTES) {
            return forbidden() // cannot be authenticated without reading an acceptable body
        }
        val bytes = try {
            request.readBody(length.toInt())
        } catch (_: IOException) {
            return RemoteReply.text(400, "unreadable body")
        }
        val fields = MiniJson.parseStringMap(String(bytes, Charsets.UTF_8))
            ?: return if (queryTokenOk) RemoteReply.text(400, "bad json") else forbidden()
        if (!queryTokenOk && !tokenMatches(fields["t"], token)) return forbidden()
        val text = fields["text"] ?: return RemoteReply.text(400, "text required")
        onText(normalizeRemoteText(text))
        return RemoteReply(200, "application/json; charset=utf-8", """{"ok":true}""".toByteArray(Charsets.UTF_8))
    }

    private fun forbidden() = RemoteReply.text(403, "forbidden")
}

/**
 * Token bucket per client: [perSecond] requests per second, burst of [perSecond]. State is bounded
 * ([maxClients] entries; when full, unknown clients restart from a clean slate).
 */
internal class RateLimiter(
    private val perSecond: Int = 20,
    private val nowNanos: () -> Long = System::nanoTime,
    private val maxClients: Int = 64,
) {
    private class Bucket(var tokens: Double, var lastNanos: Long)

    private val buckets = HashMap<String, Bucket>()

    @Synchronized
    fun tryAcquire(client: String): Boolean {
        val now = nowNanos()
        if (buckets.size >= maxClients && client !in buckets) buckets.clear()
        val bucket = buckets.getOrPut(client) { Bucket(perSecond.toDouble(), now) }
        val elapsedSeconds = (now - bucket.lastNanos).coerceAtLeast(0L) / 1_000_000_000.0
        bucket.tokens = minOf(perSecond.toDouble(), bucket.tokens + elapsedSeconds * perSecond)
        bucket.lastNanos = now
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }
}
