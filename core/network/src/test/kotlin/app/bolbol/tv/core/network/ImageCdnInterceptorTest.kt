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

class ImageCdnInterceptorTest {

    private lateinit var cdn1: MockWebServer
    private lateinit var cdn2: MockWebServer
    private lateinit var api1: MockWebServer
    private lateinit var api2: MockWebServer
    private lateinit var imageHosts: MirrorList
    private lateinit var mirrors: MirrorList
    private lateinit var client: OkHttpClient

    private val imagePath = "/wp-content/uploads/2026/02/0-76008300-1772116872.webp"
    private val imageBytes = "RIFFxxxxWEBP"

    @Before
    fun setUp() {
        cdn1 = MockWebServer().apply { start() }
        cdn2 = MockWebServer().apply { start() }
        api1 = MockWebServer().apply { start() }
        api2 = MockWebServer().apply { start() }
        imageHosts = MirrorList(listOf(cdn1.url("/").toString(), cdn2.url("/").toString()))
        mirrors = MirrorList(listOf(api1.url("/").toString(), api2.url("/").toString()))
        // The same two interceptors the shared client installs.
        client = OkHttpClient.Builder()
            .addInterceptor(MirrorInterceptor(mirrors))
            .addInterceptor(ImageCdnInterceptor(imageHosts))
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        listOf(cdn1, cdn2, api1, api2).forEach { runCatching { it.shutdown() } }
    }

    private fun image(contentType: String? = "image/webp") =
        MockResponse().setResponseCode(200).apply { if (contentType != null) setHeader("Content-Type", contentType) }.setBody(imageBytes)

    private fun notFoundHtml() =
        MockResponse().setResponseCode(404).setHeader("Content-Type", "text/html").setBody("<html>404</html>")

    private fun get(path: String = imagePath, host: MockWebServer = cdn1): okhttp3.Response =
        client.newCall(Request.Builder().url(host.url(path)).build()).execute()

    @Test
    fun healthyPrimaryServesTheImageAlone() {
        cdn1.enqueue(image())
        get().use { assertEquals(imageBytes, it.body.string()) }
        assertEquals(1, cdn1.requestCount)
        assertEquals(0, cdn2.requestCount)
    }

    @Test
    fun http404OnThePrimaryFailsOverAndTheGoodHostIsRemembered() {
        cdn1.enqueue(notFoundHtml())
        cdn2.enqueue(image())
        cdn2.enqueue(image())

        get().use { assertEquals(imageBytes, it.body.string()) }
        assertEquals(cdn2.port, imageHosts.current().port)

        get().use { assertEquals(imageBytes, it.body.string()) }
        assertEquals(1, cdn1.requestCount) // the broken host is not asked again
        assertEquals(2, cdn2.requestCount)
    }

    @Test
    fun http5xxFailsOver() {
        cdn1.enqueue(MockResponse().setResponseCode(503).setHeader("Content-Type", "image/webp"))
        cdn2.enqueue(image())
        get().use { assertEquals(200, it.code) }
        assertEquals(cdn2.port, imageHosts.current().port)
    }

    @Test
    fun htmlBodyWithStatus200FailsOver() {
        cdn1.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html; charset=UTF-8").setBody("<html>captcha</html>"))
        cdn2.enqueue(image())
        get().use { assertEquals(imageBytes, it.body.string()) }
    }

    @Test
    fun connectionFailureFailsOver() {
        cdn1.shutdown()
        cdn2.enqueue(image())
        get(host = cdn2).use { assertEquals(imageBytes, it.body.string()) }
        assertEquals(cdn2.port, imageHosts.current().port)
    }

    @Test
    fun octetStreamAndUnlabelledImagesAreAccepted() {
        cdn1.enqueue(image("application/octet-stream"))
        get().use { assertEquals(imageBytes, it.body.string()) }
        cdn1.enqueue(image(contentType = null))
        get().use { assertEquals(imageBytes, it.body.string()) }
        assertEquals(0, cdn2.requestCount)
    }

    @Test
    fun theUploadPathAndQuerySurviveTheHostSwap() {
        cdn1.enqueue(notFoundHtml())
        cdn2.enqueue(image())
        val url = cdn1.url(imagePath).newBuilder().addQueryParameter("v", "3").build()
        client.newCall(Request.Builder().url(url).build()).execute().close()

        cdn1.takeRequest(1, TimeUnit.SECONDS)
        val recorded = cdn2.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(imagePath, recorded.requestUrl!!.encodedPath)
        assertEquals("3", recorded.requestUrl!!.queryParameter("v"))
    }

    @Test
    fun anImageMissingEverywhereFailsWithAllHostsFailed() {
        cdn1.enqueue(notFoundHtml())
        cdn2.enqueue(notFoundHtml())
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
    fun noHostReachableFailsAsNotReachable() {
        cdn1.shutdown()
        cdn2.shutdown()
        try {
            get(host = cdn1)
            fail("expected AllMirrorsFailedException")
        } catch (e: AllMirrorsFailedException) {
            assertFalse(e.reachedAnyMirror)
        }
    }

    @Test
    fun apiAndStreamRequestsAreNeverSentToTheImageHosts() {
        // /wp-json/ 404 is a real API answer: returned as is, no image host involved.
        api1.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"success":false}"""))
        get(path = "/wp-json/mapi/v1/post/1", host = api1).use { assertEquals(404, it.code) }
        assertEquals(0, cdn1.requestCount + cdn2.requestCount)

        // Stream URLs have no uploads prefix: a 404/HTML there is returned untouched and nothing is retried.
        val streamPath = "/zr/stream_movies/stream7/2025/X/X.1080p.mp4"
        cdn1.enqueue(notFoundHtml())
        get(path = streamPath, host = cdn1).use { assertEquals(404, it.code) }
        assertEquals(1, cdn1.requestCount)
        assertEquals(0, cdn2.requestCount)
        assertEquals(cdn1.port, imageHosts.current().port)
    }

    @Test
    fun imageFailoverDoesNotDisturbTheApiMirrorMemory() {
        cdn1.enqueue(notFoundHtml())
        cdn2.enqueue(image())
        get().close()
        assertEquals(cdn2.port, imageHosts.current().port)
        assertEquals(api1.port, mirrors.current().port)

        // And the other way round: an API failover leaves the image host alone.
        api1.enqueue(MockResponse().setResponseCode(503).setHeader("Content-Type", "text/html").setBody("<html>down</html>"))
        api2.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{}"))
        get(path = "/wp-json/mapi/v1/post/all", host = api1).close()
        assertEquals(api2.port, mirrors.current().port)
        assertEquals(cdn2.port, imageHosts.current().port)
    }

    @Test
    fun updatingTheImageHostsRestartsAtTheNewFirstEntry() {
        cdn1.enqueue(notFoundHtml())
        cdn2.enqueue(image())
        get().close()
        assertEquals(cdn2.port, imageHosts.current().port)
        imageHosts.update(listOf(cdn1.url("/").toString()))
        assertEquals(cdn1.port, imageHosts.current().port)
        assertEquals(cdn1.port, imageHosts.primary().port)
    }
}
