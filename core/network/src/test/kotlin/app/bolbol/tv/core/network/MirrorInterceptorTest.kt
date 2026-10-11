package app.bolbol.tv.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class MirrorInterceptorTest {

    private lateinit var first: MockWebServer
    private lateinit var second: MockWebServer
    private lateinit var mirrors: MirrorList
    private lateinit var client: OkHttpClient

    private val apiPath = "/wp-json/mapi/v1/post/all"
    private val json = """{"success":true,"data":{"sections":[]}}"""

    @Before
    fun setUp() {
        first = MockWebServer().apply { start() }
        second = MockWebServer().apply { start() }
        mirrors = MirrorList(listOf(first.url("/").toString(), second.url("/").toString()))
        client = OkHttpClient.Builder()
            .addInterceptor(MirrorInterceptor(mirrors))
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        runCatching { first.shutdown() }
        runCatching { second.shutdown() }
    }

    private fun jsonResponse(code: Int = 200, body: String = json) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=UTF-8").setBody(body)

    private fun htmlResponse(code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "text/html; charset=UTF-8")
            .setBody("<html><body>Bad gateway</body></html>")

    private fun get(path: String = apiPath, host: MockWebServer = first): okhttp3.Response =
        client.newCall(Request.Builder().url(host.url(path)).build()).execute()

    @Test
    fun healthyFirstMirrorIsUsedAlone() {
        first.enqueue(jsonResponse())
        get().use { assertEquals(json, it.body.string()) }
        assertEquals(1, first.requestCount)
        assertEquals(0, second.requestCount)
    }

    @Test
    fun http503OnTheFirstMirrorFailsOverToTheSecondAndIsRemembered() {
        first.enqueue(htmlResponse(503))
        second.enqueue(jsonResponse())
        second.enqueue(jsonResponse())

        get().use { assertEquals(json, it.body.string()) }
        assertEquals(1, first.requestCount)
        assertEquals(1, second.requestCount)
        assertEquals(second.url("/").host, mirrors.current().host)
        assertEquals(second.port, mirrors.current().port)

        // The next call goes straight to the remembered mirror; the broken one is not touched again.
        get().use { assertEquals(json, it.body.string()) }
        assertEquals(1, first.requestCount)
        assertEquals(2, second.requestCount)
    }

    @Test
    fun htmlBodyWithStatus200FailsOver() {
        first.enqueue(htmlResponse(200))
        second.enqueue(jsonResponse())
        get().use { assertEquals(json, it.body.string()) }
        assertEquals(second.port, mirrors.current().port)
    }

    @Test
    fun jsonWithAWrongContentTypeIsAccepted() {
        first.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(json))
        get().use { assertEquals(json, it.body.string()) }
        assertEquals(0, second.requestCount)
    }

    @Test
    fun connectionFailureFailsOver() {
        first.shutdown()
        second.enqueue(jsonResponse())
        get(host = second).use { assertEquals(json, it.body.string()) }
        assertEquals(second.port, mirrors.current().port)
    }

    @Test
    fun apiJson404IsARealAnswerAndDoesNotFailOver() {
        first.enqueue(jsonResponse(404, """{"success":false,"message":"not found"}"""))
        get().use {
            assertEquals(404, it.code)
            assertTrue(it.body.string().contains("success"))
        }
        assertEquals(0, second.requestCount)
        assertEquals(first.port, mirrors.current().port)
    }

    @Test
    fun theRequestPathAndQuerySurviveTheHostSwap() {
        first.enqueue(htmlResponse(502))
        second.enqueue(jsonResponse())
        val url = first.url("/wp-json/mapi/v1/post/search").newBuilder()
            .addQueryParameter("search", "دوستان")
            .addQueryParameter("page", "2")
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().close()

        first.takeRequest(1, TimeUnit.SECONDS)
        val recorded = second.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/wp-json/mapi/v1/post/search", recorded.requestUrl!!.encodedPath)
        assertEquals("دوستان", recorded.requestUrl!!.queryParameter("search"))
        assertEquals("2", recorded.requestUrl!!.queryParameter("page"))
    }

    @Test
    fun nonApiRequestsAreNeverRewrittenOrRetried() {
        // An image on the first host failing with 503 must come back as-is, not be re-sent to the other mirror.
        first.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        get(path = "/wp-content/uploads/2026/02/poster.webp").use { assertEquals(503, it.code) }
        assertEquals(1, first.requestCount)
        assertEquals(0, second.requestCount)
        assertEquals(first.port, mirrors.current().port)

        // Nor is a non-API HTML answer treated as a mirror failure.
        first.enqueue(htmlResponse(200))
        get(path = "/some/page.html").use { assertEquals(200, it.code) }
        assertEquals(0, second.requestCount)
    }

    @Test
    fun allMirrorsAnsweringWithErrorsReportsReachable() {
        first.enqueue(htmlResponse(503))
        second.enqueue(htmlResponse(502))
        try {
            get()
            fail("expected AllMirrorsFailedException")
        } catch (e: AllMirrorsFailedException) {
            assertTrue(e is IOException)
            assertTrue(e.reachedAnyMirror)
            assertEquals(2, e.failures.size)
        }
    }

    @Test
    fun allMirrorsUnreachableReportsNotReachable() {
        first.shutdown()
        second.shutdown()
        try {
            get(host = first)
            fail("expected AllMirrorsFailedException")
        } catch (e: AllMirrorsFailedException) {
            assertFalse(e.reachedAnyMirror)
            assertEquals(2, e.failures.size)
        }
    }

    @Test
    fun failoverWrapsAroundFromTheRememberedMirror() {
        // Second mirror becomes the remembered one, then dies: the first one is tried again.
        first.enqueue(htmlResponse(503))
        second.enqueue(jsonResponse())
        get().close()
        assertEquals(second.port, mirrors.current().port)

        second.enqueue(htmlResponse(503))
        first.enqueue(jsonResponse())
        get().use { assertEquals(json, it.body.string()) }
        assertEquals(first.port, mirrors.current().port)
    }

    @Test
    fun updatingTheMirrorListResetsTheRememberedMirror() {
        first.enqueue(htmlResponse(503))
        second.enqueue(jsonResponse())
        get().close()
        assertEquals(second.port, mirrors.current().port)

        mirrors.update(listOf(first.url("/").toString()))
        assertEquals(first.port, mirrors.current().port)
    }

    @Test
    fun bareHostsGetHttpsAndOnlySchemeHostAndPortMatter() {
        val list = MirrorList(listOf("euploadi.com", "https://uuploadi.ir/ignored/path"))
        assertEquals("euploadi.com", list.current().host)
        assertEquals("https", list.current().scheme)
        assertEquals("https://euploadi.com/wp-json/mapi/v1/", list.apiBaseUrl().toString())
    }

    @Test
    fun theSharedClientHasTheDocumentedSettings() {
        val dir = java.nio.file.Files.createTempDirectory("okhttp-cache").toFile()
        try {
            val shared = createOkHttpClient(dir, MirrorList(NetworkModule.DEFAULT_MIRRORS), MirrorList(NetworkModule.DEFAULT_IMAGE_HOSTS))
            assertEquals(10_000, shared.connectTimeoutMillis)
            assertEquals(20_000, shared.readTimeoutMillis)
            assertTrue(shared.retryOnConnectionFailure)
            assertEquals(50L * 1024 * 1024, shared.cache!!.maxSize())
            assertTrue(shared.interceptors.any { it is MirrorInterceptor })
            assertTrue(shared.interceptors.any { it is ImageCdnInterceptor })
        } finally {
            dir.deleteRecursively()
        }
    }
}
