package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.WatchKey
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The whole account network stack (Retrofit + mirror failover + token interceptor + parsing) against MockWebServer. */
class RetrofitAccountRemoteTest {

    private lateinit var first: MockWebServer
    private lateinit var second: MockWebServer
    private lateinit var remote: RetrofitAccountRemote
    private var token: String? = "tok-abc"
    private val shapes = mutableListOf<String>()

    private val base = "/wp-json/mapi/v1/"

    @Before
    fun setUp() {
        first = MockWebServer().apply { start() }
        second = MockWebServer().apply { start() }
        val mirrors = MirrorList(listOf(first.url("/").toString(), second.url("/").toString()))
        val client = OkHttpClient.Builder()
            .addInterceptor(MirrorInterceptor(mirrors))
            .addNetworkInterceptor(AuthTokenInterceptor(mirrors, TokenProvider { token }))
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        remote = RetrofitAccountRemote(createAccountService(client, mirrors), log = { shapes += it })
    }

    @After
    fun tearDown() {
        runCatching { first.shutdown() }
        runCatching { second.shutdown() }
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=UTF-8").setBody(body)

    private fun html(code: Int = 503) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "text/html").setBody("<html>down</html>")

    // --- login --------------------------------------------------------------------------------------

    @Test
    fun `login posts the form fields without a token and parses the profile`() = runTest {
        first.enqueue(
            json(
                """{"success":true,"data":{"id":7,"username":"ali","display_name":"Ali R","token":"server-token",
                   "vip":{"days_left":23,"status":"active"}},"message":""}""",
            ),
        )
        val outcome = remote.login("ali", "p@ss word&=")

        outcome as LoginOutcome.Success
        assertEquals("server-token", outcome.token)
        assertEquals("Ali R", outcome.profile.displayName)
        assertEquals(23, outcome.profile.vipDaysLeft)

        val request = first.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(base + "login", request.requestUrl!!.encodedPath)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals("username=ali&password=p%40ss+word%26%3D", request.body.readUtf8())
        assertNull("login must not carry a token", request.getHeader("Authorization"))
        // Only shapes are logged: never the token, name or password.
        assertTrue(shapes.single().contains("token:str"))
        assertFalse(shapes.single().contains("server-token"))
        assertFalse(shapes.joinToString().contains("p@ss"))
    }

    @Test
    fun `login with refused credentials`() = runTest {
        first.enqueue(json("""{"success":false,"message":"نام کاربری یا رمز عبور اشتباه است."}""", code = 400))
        assertEquals(LoginOutcome.WrongCredentials, remote.login("ali", "nope"))
        assertEquals(1, first.requestCount + second.requestCount) // one attempt, no failover for a JSON 4xx
    }

    @Test
    fun `login fails over to the next mirror when the first is down`() = runTest {
        first.enqueue(html(502))
        second.enqueue(json("""{"success":true,"data":{"token":"t2"}}"""))
        val outcome = remote.login("ali", "pw")
        assertEquals("t2", (outcome as LoginOutcome.Success).token)
    }

    @Test
    fun `login reports server and network problems in Persian without throwing`() = runTest {
        first.enqueue(html(503))
        second.enqueue(html(503))
        assertEquals(LoginOutcome.Failed(AccountParsing.MSG_SERVER), remote.login("ali", "pw"))

        first.shutdown()
        second.shutdown()
        assertEquals(LoginOutcome.Failed(AccountParsing.MSG_OFFLINE), remote.login("ali", "pw"))
    }

    // --- profile ---------------------------------------------------------------------------------------

    @Test
    fun `profile sends the bearer token and never uses the cache`() = runTest {
        first.enqueue(json("""{"success":true,"data":{"display_name":"Ali","vip":{"days_left":4}}}"""))
        val result = remote.profile() as RemoteResult.Ok
        assertEquals(RemoteProfile("Ali", 4), result.value)
        val request = first.takeRequest()
        assertEquals(base + "user/profile", request.requestUrl!!.encodedPath)
        assertEquals("Bearer tok-abc", request.getHeader("Authorization"))
        assertEquals("no-store", request.getHeader("Cache-Control"))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    // --- favourites ---------------------------------------------------------------------------------------

    @Test
    fun `favourites returns the list in one request`() = runTest {
        first.enqueue(
            json("""{"success":true,"data":[{"id":353371,"title":"Dune","image":"https://x/wp-content/uploads/a.webp"},{"id":624236,"title":"Friends"}]}"""),
        )
        val list = (remote.favorites() as RemoteResult.Ok).value
        assertEquals(listOf(353371L, 624236L), list.items.map { it.id })
        assertTrue(list.complete)
        assertEquals("Dune", list.items[0].summary!!.title)
        assertEquals(1, first.requestCount)
        assertNull(first.takeRequest().requestUrl!!.queryParameter("page"))
    }

    @Test
    fun `favourites follows total_page and stays complete`() = runTest {
        first.enqueue(json("""{"success":true,"per_page":2,"page":1,"total_page":2,"data":[{"id":1},{"id":2}]}"""))
        first.enqueue(json("""{"success":true,"per_page":2,"page":2,"total_page":2,"data":[{"id":3}]}"""))
        val list = (remote.favorites() as RemoteResult.Ok).value
        assertEquals(listOf(1L, 2L, 3L), list.items.map { it.id })
        assertTrue(list.complete)
        first.takeRequest()
        assertEquals("2", first.takeRequest().requestUrl!!.queryParameter("page"))
    }

    @Test
    fun `a full page without a page count is followed and an ignored page parameter marks the list incomplete`() = runTest {
        val page = json("""{"success":true,"per_page":2,"data":[{"id":1},{"id":2}]}""")
        first.enqueue(page)
        first.enqueue(json("""{"success":true,"per_page":2,"data":[{"id":1},{"id":2}]}"""))
        val list = (remote.favorites() as RemoteResult.Ok).value
        assertEquals(listOf(1L, 2L), list.items.map { it.id })
        assertFalse("cannot prove the list is complete", list.complete)
        assertEquals(2, first.requestCount)
    }

    @Test
    fun `a short page ends the list`() = runTest {
        first.enqueue(json("""{"success":true,"per_page":5,"data":[{"id":1},{"id":2}]}"""))
        val list = (remote.favorites() as RemoteResult.Ok).value
        assertTrue(list.complete)
        assertEquals(1, first.requestCount)
    }

    @Test
    fun `paging is capped`() = runTest {
        for (page in 1..AccountWire.MAX_LIST_PAGES + 2) {
            first.enqueue(json("""{"success":true,"per_page":1,"total_page":99,"data":[{"id":$page}]}"""))
        }
        val list = (remote.favorites() as RemoteResult.Ok).value
        assertEquals(AccountWire.MAX_LIST_PAGES, list.items.size)
        assertFalse(list.complete)
        assertEquals(AccountWire.MAX_LIST_PAGES, first.requestCount)
    }

    @Test
    fun `a failing later page fails the whole read`() = runTest {
        first.enqueue(json("""{"success":true,"total_page":2,"data":[{"id":1}]}"""))
        first.enqueue(json("""{"success":false,"message":"x"}""", code = 400))
        second.enqueue(html(500))
        assertEquals(RemoteResult.Failed, remote.favorites())
    }

    @Test
    fun `an expired token is Unauthorized and other trouble is Failed`() = runTest {
        first.enqueue(json("""{"success":false,"message":"توکن نامعتبر است."}""", code = 401))
        assertEquals(RemoteResult.Unauthorized, remote.favorites())
        first.enqueue(json("""{"success":false,"message":"توکن الزامی است."}""", code = 400))
        assertEquals(RemoteResult.Failed, remote.favorites())
        first.enqueue(json("""{"success":false,"data":[]}"""))
        assertEquals(RemoteResult.Failed, remote.favorites())
        first.enqueue(json("""{"success":true,"data":{"count":1}}"""))
        assertEquals(RemoteResult.Failed, remote.favorites())
        first.enqueue(html(200))
        second.enqueue(html(200))
        assertEquals(RemoteResult.Failed, remote.favorites())
    }

    @Test
    fun `toggle posts post_id as a form`() = runTest {
        first.enqueue(json("""{"success":true,"data":["353371"],"message":"ok"}"""))
        assertEquals(RemoteResult.Ok(Unit), remote.toggleFavorite(353371))
        val request = first.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(base + "user/favorite-list", request.requestUrl!!.encodedPath)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals("post_id=353371", request.body.readUtf8())
        assertEquals("Bearer tok-abc", request.getHeader("Authorization"))
    }

    @Test
    fun `toggle failures`() = runTest {
        first.enqueue(json("""{"success":false,"message":"no"}"""))
        assertEquals(RemoteResult.Failed, remote.toggleFavorite(1))
        first.enqueue(json("""{"success":false}""", code = 403))
        assertEquals(RemoteResult.Unauthorized, remote.toggleFavorite(1))
    }

    // --- progress ---------------------------------------------------------------------------------------------

    @Test
    fun `progress list parses server entries`() = runTest {
        first.enqueue(
            json(
                """{"success":true,"data":[{"id":"353371","title":"Dune","image":"","current_time":"1200","duration":"9000","season":"","episode":""},
                   {"id":"624236","title":"Friends","image":"","current_time":"300","duration":"1500","season":"1","episode":"2"}]}""",
            ),
        )
        val items = (remote.progressList() as RemoteResult.Ok).value
        assertEquals(listOf(WatchKey(353371), WatchKey(624236, 1, 2)), items.map { it.key })
        assertEquals(1_200_000L, items[0].positionMs)
        assertEquals(9_000_000L, items[0].durationMs)
        val request = first.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(base + "user/watch-list", request.requestUrl!!.encodedPath)
        assertEquals("Bearer tok-abc", request.getHeader("Authorization"))
    }

    @Test
    fun `save progress sends a multipart movie without season and episode`() = runTest {
        first.enqueue(json("""{"success":true,"data":null}"""))
        assertEquals(RemoteResult.Ok(Unit), remote.saveProgress(353371, 1234, 0, 0))
        val request = first.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(base + "user/watch-list", request.requestUrl!!.encodedPath)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertEquals("Bearer tok-abc", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertEquals(mapOf("post_id" to "353371", "current_time" to "1234"), formParts(body))
    }

    @Test
    fun `save progress sends season and episode for episodes`() = runTest {
        first.enqueue(json("""{"success":true}"""))
        remote.saveProgress(624236, 600, 2, 3)
        assertEquals(
            mapOf("post_id" to "624236", "current_time" to "600", "season" to "2", "episode" to "3"),
            formParts(first.takeRequest().body.readUtf8()),
        )
    }

    @Test
    fun `save progress with an expired token`() = runTest {
        first.enqueue(json("""{"success":false}""", code = 401))
        assertEquals(RemoteResult.Unauthorized, remote.saveProgress(1, 10, 0, 0))
    }

    /** name -> value of every simple form-data part of a multipart body. */
    private fun formParts(body: String): Map<String, String> =
        Regex("""name="([^"]+)"\r\n\r\n(.*?)\r\n--""", RegexOption.DOT_MATCHES_ALL)
            .findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
}
