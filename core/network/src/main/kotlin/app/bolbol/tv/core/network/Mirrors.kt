package app.bolbol.tv.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Path prefix that identifies API calls; only these are rewritten (never images or streams). */
internal const val API_PATH_PREFIX = "/wp-json/"

/** Thrown (as an [IOException], as OkHttp requires) when every mirror failed for one API call. */
internal class AllMirrorsFailedException(
    /** True when at least one mirror answered (5xx / HTML), false when none could even be reached. */
    val reachedAnyMirror: Boolean,
    val failures: List<Throwable>,
) : IOException("all ${failures.size} mirror(s) failed", failures.lastOrNull()) {
    init {
        failures.dropLast(1).forEach { addSuppressed(it) }
    }
}

/**
 * Ordered mirror list plus the index of the last mirror that worked (kept in memory only).
 * Only scheme, host and port of an entry are used; the API path is always `/wp-json/mapi/v1/`.
 */
internal class MirrorList(mirrors: List<String>) {
    class State(val urls: List<HttpUrl>) {
        @Volatile var good: Int = 0
    }

    @Volatile private var state = State(parse(mirrors))

    /** Replaces the list (e.g. after the user edits it); the good-mirror memory restarts at the first entry. */
    fun update(mirrors: List<String>) {
        state = State(parse(mirrors))
    }

    fun snapshot(): State = state

    /** The mirror API calls currently start with. */
    fun current(): HttpUrl = state.let { it.urls[it.good] }

    /** Retrofit base URL: first mirror + API root. Real hosts are substituted by [MirrorInterceptor]. */
    fun apiBaseUrl(): HttpUrl = state.urls.first().newBuilder()
        .encodedPath("/wp-json/mapi/v1/")
        .build()

    private fun parse(mirrors: List<String>): List<HttpUrl> {
        val urls = mirrors.map { it.trim() }.filter { it.isNotEmpty() }
            .map { if ("://" in it) it else "https://$it" }
            .map { it.toHttpUrl() }
        require(urls.isNotEmpty()) { "at least one mirror is required" }
        return urls
    }
}

/**
 * Sends API requests to the current mirror and fails over to the next one on
 * an [IOException], an HTTP 5xx, or a non-JSON (HTML) body. The mirror that last answered properly
 * is remembered and tried first next time. 4xx JSON answers (404 "not found", 400 ...) are
 * real API answers and are returned untouched.
 */
internal class MirrorInterceptor(private val mirrors: MirrorList) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!original.url.encodedPath.startsWith(API_PATH_PREFIX)) return chain.proceed(original)

        val state = mirrors.snapshot()
        val count = state.urls.size
        val failures = ArrayList<Throwable>(count)
        var reached = false

        for (attempt in 0 until count) {
            val index = (state.good + attempt) % count
            val mirror = state.urls[index]
            val request = original.newBuilder()
                .url(original.url.newBuilder().scheme(mirror.scheme).host(mirror.host).port(mirror.port).build())
                .build()
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                failures += e
                continue
            }
            val problem = response.problem()
            if (problem == null) {
                state.good = index
                return response
            }
            reached = true
            failures += IOException("mirror ${mirror.host}: $problem")
            response.close()
        }
        throw AllMirrorsFailedException(reached, failures)
    }

    /** Null when the response is a usable API answer, otherwise a short reason. */
    private fun Response.problem(): String? {
        if (code in 500..599) return "HTTP $code"
        val contentType = body.contentType()
        if (contentType?.subtype?.contains("json", ignoreCase = true) == true) return null
        // Mislabelled or missing content type: accept only if the body really starts like JSON.
        val head = peekBody(PEEK_BYTES).string().trimStart { it.isWhitespace() || it == BOM }
        return if (head.startsWith("{") || head.startsWith("[")) null else "non-JSON response (HTTP $code)"
    }

    private companion object {
        const val PEEK_BYTES = 256L
        val BOM = Char(0xFEFF)
    }
}
