package app.bolbol.tv.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Which requests get `Authorization: Bearer <token>`, and which never do. */
class AuthTokenInterceptorTest {

    private lateinit var api: MockWebServer
    private lateinit var foreign: MockWebServer
    private var token: String? = "tok-abc"

    private val userPath = "/wp-json/mapi/v1/user/favorite-list"

    @Before
    fun setUp() {
        api = MockWebServer().apply { start() }
        foreign = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { api.shutdown() }
        runCatching { foreign.shutdown() }
    }

    /** [failover] adds the real [MirrorInterceptor] (which would also redirect any `/wp-json/` call to a mirror). */
    private fun client(vararg mirrors: MockWebServer, failover: Boolean = true): OkHttpClient {
        val list = MirrorList(mirrors.map { it.url("/").toString() })
        return OkHttpClient.Builder()
            .apply { if (failover) addInterceptor(MirrorInterceptor(list)) }
            .addNetworkInterceptor(AuthTokenInterceptor(list, TokenProvider { token }))
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    private fun json(body: String = """{"success":true,"data":[]}""") =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun OkHttpClient.get(server: MockWebServer, path: String) {
        newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.body.string() }
    }

    @Test
    fun `user calls carry the bearer token when signed in`() {
        api.enqueue(json())
        client(api).get(api, userPath)
        assertEquals("Bearer tok-abc", api.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `no token means no header`() {
        token = null
        api.enqueue(json())
        client(api).get(api, userPath)
        assertNull(api.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `an empty token means no header`() {
        token = ""
        api.enqueue(json())
        client(api).get(api, userPath)
        assertNull(api.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `catalogue login images and streams never carry the token`() {
        val http = client(api)
        for (path in listOf(
            "/wp-json/mapi/v1/post/all",
            "/wp-json/mapi/v1/post/353371",
            "/wp-json/mapi/v1/login",
            "/wp-json/mapi/v1/users/profile",
            "/wp-content/uploads/2026/07/a.webp",
            "/movies/a.mp4",
        )) {
            api.enqueue(json())
            http.get(api, path)
            assertNull(path, api.takeRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun `the token follows the current value on every request`() {
        val http = client(api)
        api.enqueue(json())
        http.get(api, userPath)
        assertEquals("Bearer tok-abc", api.takeRequest().getHeader("Authorization"))

        token = "tok-next"
        api.enqueue(json())
        http.get(api, userPath)
        assertEquals("Bearer tok-next", api.takeRequest().getHeader("Authorization"))

        token = null // logout
        api.enqueue(json())
        http.get(api, userPath)
        assertNull(api.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a host outside the mirror list never gets the token`() {
        // `foreign` is not a mirror: the interceptor sees its origin and keeps the token to itself.
        val http = client(api, failover = false)
        foreign.enqueue(json())
        http.get(foreign, userPath)
        assertNull(foreign.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a redirect to another origin does not carry the token`() {
        api.enqueue(MockResponse().setResponseCode(302).setHeader("Location", foreign.url(userPath).toString()))
        foreign.enqueue(json())
        client(api, failover = false).get(api, userPath)
        assertEquals("Bearer tok-abc", api.takeRequest().getHeader("Authorization"))
        assertNull(foreign.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `after a mirror failover the token goes to the mirror that answers`() {
        api.enqueue(MockResponse().setResponseCode(503).setHeader("Content-Type", "text/html").setBody("<html/>"))
        foreign.enqueue(json())
        client(api, foreign).get(api, userPath)
        assertEquals("Bearer tok-abc", api.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer tok-abc", foreign.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `needsToken only matches user routes`() {
        assertEquals(true, AccountWire.needsToken("/wp-json/mapi/v1/user/profile"))
        assertEquals(true, AccountWire.needsToken("/wp-json/mapi/v1/user/watch-list"))
        assertEquals(false, AccountWire.needsToken("/wp-json/mapi/v1/user"))
        assertEquals(false, AccountWire.needsToken("/wp-json/mapi/v1/post/tv"))
        assertEquals(false, AccountWire.needsToken("/other/wp-json/mapi/v1/user/profile"))
    }
}
