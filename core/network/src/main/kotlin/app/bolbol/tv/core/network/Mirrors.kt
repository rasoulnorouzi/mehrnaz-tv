package app.bolbol.tv.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Path prefix that identifies API calls; only these are sent through [MirrorInterceptor]. */
internal const val API_PATH_PREFIX = "/wp-json/"

/** Path prefix of the poster/backdrop/photo files; only these are sent through [ImageCdnInterceptor]. */
internal const val IMAGE_PATH_PREFIX = "/wp-content/uploads/"

/** Thrown (as an [IOException], as OkHttp requires) when every host failed for one request. */
internal class AllMirrorsFailedException(
    /** True when at least one host answered (5xx / 404 / HTML), false when none could even be reached. */
    val reachedAnyMirror: Boolean,
    val failures: List<Throwable>,
) : IOException("all ${failures.size} host(s) failed", failures.lastOrNull()) {
    init {
        failures.dropLast(1).forEach { addSuppressed(it) }
    }
}

/**
 * Ordered host list plus the index of the last host that worked (kept in memory only). Used for the API
 * mirrors and, as a second instance, for the image CDNs. Only scheme, host and port of an entry are used.
 */
internal class MirrorList(mirrors: List<String>) {
    class State(val urls: List<HttpUrl>) {
        @Volatile var good: Int = 0
    }

    @Volatile private var state = State(parse(mirrors))

    /** Replaces the list (e.g. after the user edits it); the good-host memory restarts at the first entry. */
    fun update(mirrors: List<String>) {
        state = State(parse(mirrors))
    }

    fun snapshot(): State = state

    /** Called with the new list whenever [learn] adds a host (persist it so it survives restarts). */
    @Volatile var onLearned: ((List<String>) -> Unit)? = null

    /**
     * A mirror redirected to [host], i.e. the site moved: put [host] first and make it the good host. No-op when it
     * is already in the list. Returns true when the list changed.
     */
    @Synchronized
    fun learn(host: HttpUrl): Boolean {
        val current = state.urls
        if (current.any { it.host == host.host && it.port == host.port }) return false
        val origin = host.newBuilder().encodedPath("/").query(null).fragment(null).build()
        val next = listOf(origin) + current
        state = State(next)
        onLearned?.invoke(next.map { it.toString() })
        return true
    }

    /** The host requests currently start with. */
    fun current(): HttpUrl = state.let { it.urls[it.good] }

    /** The configured first (preferred) host. */
    fun primary(): HttpUrl = state.urls.first()

    /** Retrofit base URL: first mirror + API root. Real hosts are substituted by [MirrorInterceptor]. */
    fun apiBaseUrl(): HttpUrl = state.urls.first().newBuilder()
        .encodedPath("/wp-json/mapi/v1/")
        .build()

    private fun parse(mirrors: List<String>): List<HttpUrl> {
        val urls = mirrors.map { it.trim() }.filter { it.isNotEmpty() }
            .map { if ("://" in it) it else "https://$it" }
            .map { it.toHttpUrl() }
        require(urls.isNotEmpty()) { "at least one host is required" }
        return urls
    }
}

/**
 * Base of the two failover interceptors. Requests whose path starts with [pathPrefix] are sent to the
 * remembered good host first and moved to the next host on an [IOException] or when [problem] says the
 * answer is unusable; the host that last answered properly is remembered. Other requests pass untouched.
 */
internal abstract class HostFailoverInterceptor(
    private val hosts: MirrorList,
    private val pathPrefix: String,
) : Interceptor {

    /** Null when the response is a usable answer, otherwise a short reason. The body must not be consumed. */
    protected abstract fun problem(response: Response): String?

    /** Hook after a usable answer (e.g. learn the host a mirror redirected to). */
    protected open fun onUsable(hosts: MirrorList, request: okhttp3.Request, response: Response) {}

    /** Whether this interceptor handles a request with this encoded path. */
    protected open fun handles(path: String): Boolean = path.startsWith(pathPrefix)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!handles(original.url.encodedPath)) return chain.proceed(original)

        val state = hosts.snapshot()
        val count = state.urls.size
        val failures = ArrayList<Throwable>(count)
        var reached = false

        for (attempt in 0 until count) {
            val index = (state.good + attempt) % count
            val host = state.urls[index]
            val request = original.newBuilder()
                .url(original.url.newBuilder().scheme(host.scheme).host(host.host).port(host.port).build())
                .build()
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                failures += e
                continue
            }
            val problem = problem(response)
            if (problem == null) {
                state.good = index
                onUsable(hosts, request, response)
                return response
            }
            reached = true
            failures += IOException("host ${host.host}: $problem")
            response.close()
        }
        throw AllMirrorsFailedException(reached, failures)
    }
}

/**
 * API calls (`/wp-json/`): fail over on an [IOException], an HTTP 5xx, or a non-JSON (HTML) body.
 * JSON 4xx answers (404 "not found", 400 ...) are real API answers and are returned untouched.
 */
internal class MirrorInterceptor(mirrors: MirrorList) : HostFailoverInterceptor(mirrors, API_PATH_PREFIX) {

    /**
     * When a mirror redirected the API call to another host that answered with the API (the site moved domain),
     * remember that host as the new first mirror, so the app follows the site without an app update (v1.5).
     */
    override fun onUsable(hosts: MirrorList, request: okhttp3.Request, response: Response) {
        val finalUrl = response.request.url
        val moved = finalUrl.host != request.url.host || finalUrl.port != request.url.port
        if (moved && finalUrl.encodedPath.startsWith(API_PATH_PREFIX)) hosts.learn(finalUrl)
    }

    override fun problem(response: Response): String? {
        if (response.code in 500..599) return "HTTP ${response.code}"
        val contentType = response.body.contentType()
        if (contentType?.subtype?.contains("json", ignoreCase = true) == true) return null
        // Mislabelled or missing content type: accept only if the body really starts like JSON.
        val head = response.peekBody(PEEK_BYTES).string().trimStart { it.isWhitespace() || it == BOM }
        return if (head.startsWith("{") || head.startsWith("[")) null else "non-JSON response (HTTP ${response.code})"
    }

    private companion object {
        const val PEEK_BYTES = 256L
        val BOM = Char(0xFEFF)
    }
}

/**
 * Images (`/wp-content/uploads/`): the API's own hosts return 404 for them, so URLs are normalised to an
 * image CDN by the mappers and this interceptor keeps them alive when that CDN misbehaves. It fails over on an
 * [IOException], any non-2xx status (404, 403, 5xx) or a response that is not an image (an HTML error page).
 * Streams and `/wp-json/` calls never match the prefix and are never touched.
 */
internal class ImageCdnInterceptor(hosts: MirrorList) : HostFailoverInterceptor(hosts, IMAGE_PATH_PREFIX) {

    /** The site's search index lives under uploads too, but on the API mirrors ([SearchIndexInterceptor]). */
    override fun handles(path: String): Boolean = super.handles(path) && path != SEARCH_INDEX_PATH

    override fun problem(response: Response): String? {
        if (!response.isSuccessful) return "HTTP ${response.code}"
        val contentType = response.body.contentType() ?: return null // no label: trust the 2xx
        if (contentType.type == "image" || contentType.subtype == "octet-stream") return null
        return "not an image ($contentType)"
    }
}

internal const val SEARCH_INDEX_PATH = "/wp-content/uploads/search-data.json"

/**
 * The site's daily search index (`search-data.json`, v1.5): served by the API mirrors (not the image CDNs), so it
 * fails over across the same mirror list. 2xx and 304 (unchanged) are usable; anything else tries the next mirror.
 */
internal class SearchIndexInterceptor(mirrors: MirrorList) : HostFailoverInterceptor(mirrors, SEARCH_INDEX_PATH) {
    override fun handles(path: String): Boolean = path == SEARCH_INDEX_PATH

    override fun problem(response: Response): String? =
        if (response.isSuccessful || response.code == 304) null else "HTTP ${response.code}"
}
