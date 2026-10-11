package app.bolbol.tv.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <token>` to the account calls (`/wp-json/mapi/v1/user/` paths), and only when
 * - a token exists (signed in), and
 * - the request goes to one of the configured API mirrors (same scheme, host and port), and
 * - the path matches [AccountWire.needsToken].
 *
 * Installed as a **network** interceptor on purpose: it then runs for every hop on the wire, after
 * [MirrorInterceptor] has picked the real mirror and for each redirect target. OkHttp rebuilds a redirected request
 * from the original (which carries no token), so a redirect to a CDN, an `http://` downgrade or any foreign host never
 * receives the token. Images, streams and the public catalogue calls are never touched. If the transport turns
 * out different, this class plus [AccountWire.AUTH_HEADER] are the only places to change.
 *
 * Nothing is logged here and the header value is never copied anywhere else.
 */
internal class AuthTokenInterceptor(
    private val mirrors: MirrorList,
    private val tokens: TokenProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!AccountWire.needsToken(request.url.encodedPath)) return chain.proceed(request)
        val token = tokens.token()?.takeIf { it.isNotEmpty() } ?: return chain.proceed(request)
        if (!isMirrorOrigin(request.url)) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(AccountWire.AUTH_HEADER, AccountWire.AUTH_SCHEME + token).build())
    }

    private fun isMirrorOrigin(url: HttpUrl): Boolean = mirrors.snapshot().urls.any {
        it.scheme == url.scheme && it.host == url.host && it.port == url.port
    }
}
