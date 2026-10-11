package app.bolbol.tv.core.player.subtitles

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SubtitleLoaderTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun loader(maxBytes: Long = SubtitleLoader.MAX_BYTES) =
        SubtitleLoader(client, userAgent = "BolbolTest", maxBytes = maxBytes)

    private fun url() = server.url("/sub.vtt").toString()

    private fun expectFailure(block: suspend () -> Unit): SubtitleLoadException = runBlocking {
        try {
            block()
            fail("expected SubtitleLoadException")
            error("unreachable")
        } catch (e: SubtitleLoadException) {
            e
        }
    }

    @Test fun loadsAndParsesTheRealFile() = runBlocking {
        val vtt = requireNotNull(javaClass.classLoader?.getResource("lego_one_piece_s01e01.vtt")).readBytes()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(vtt)).setHeader("Content-Type", "text/vtt"))
        val track = loader().load(url())
        assertEquals(478, track.size)
        assertEquals("BolbolTest", server.takeRequest().getHeader("User-Agent"))
    }

    @Test fun httpErrorFails() {
        server.enqueue(MockResponse().setResponseCode(404))
        val e = expectFailure { loader().load(url()) }
        assertTrue(e.message!!.contains("404"))
    }

    @Test fun declaredTooLargeFails() {
        server.enqueue(MockResponse().setBody("WEBVTT\n\n" + "x".repeat(2_000)))
        val e = expectFailure { loader(maxBytes = 1_000).load(url()) }
        assertTrue(e.message!!.startsWith("too large"))
    }

    @Test fun chunkedTooLargeFails() {
        server.enqueue(MockResponse().setChunkedBody("WEBVTT\n\n" + "x".repeat(2_000), 256))
        val e = expectFailure { loader(maxBytes = 1_000).load(url()) }
        assertTrue(e.message!!.startsWith("too large"))
    }

    @Test fun noCuesFails() {
        server.enqueue(MockResponse().setBody("<html>mirror error page</html>"))
        val e = expectFailure { loader().load(url()) }
        assertEquals("no cues", e.message)
    }

    @Test fun badUrlAndConnectionFailuresFail() {
        expectFailure { loader().load("not a url") }
        expectFailure { loader().load("http://127.0.0.1:1/closed-port.vtt") }
    }
}
