package app.bolbol.tv.core.network.mapper

import app.bolbol.tv.core.network.IMAGE_PATH_PREFIX
import app.bolbol.tv.core.network.NetworkModule
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Rewrites image URLs to the primary image CDN (docs/api.md quirk 7).
 *
 * The API hands out upload URLs on `seo2024.ir` (lists) and `euploadi.com` (details), but the API hosts return 404
 * for them; the same path works on the site's "active CDN". Every URL whose path starts with `/wp-content/uploads/`
 * keeps its path and query and gets the scheme, host and port of [primary]. Anything else (trailer MP4s, third-party
 * images, plain strings) is returned as is. Blank input is null.
 *
 * [primary] is read on every call, so a changed host list takes effect on the next mapping. At request time
 * `ImageCdnInterceptor` still moves a failing request to the next CDN, so this stays the stable URL (and cache key).
 */
internal class ImageUrls(private val primary: () -> HttpUrl) {

    fun normalize(raw: String?): String? {
        val url = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val pathStart = pathStart(url)
        if (pathStart < 0 || !url.startsWith(IMAGE_PATH_PREFIX, pathStart)) return url
        return origin(primary()) + url.substring(pathStart)
    }

    /** Index where the path begins (the `/` after the authority), or -1 when there is no usable path. */
    private fun pathStart(url: String): Int {
        val authorityStart = when {
            url.startsWith("/") && !url.startsWith("//") -> return 0 // site-relative
            url.startsWith("//") -> 2
            else -> url.indexOf("://").takeIf { it > 0 }?.plus(3) ?: return -1
        }
        return url.indexOf('/', authorityStart)
    }

    private fun origin(host: HttpUrl): String {
        val port = if (host.port == HttpUrl.defaultPort(host.scheme)) "" else ":${host.port}"
        return "${host.scheme}://${host.host}$port"
    }

    companion object {
        /** The built-in primary CDN; used by mapper tests and anything built without a [NetworkModule]. */
        val Default = ImageUrls { NetworkModule.DEFAULT_IMAGE_HOSTS.first().toHttpUrl() }
    }
}
