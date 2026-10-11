package app.bolbol.tv.core.lanremote

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Starts the real NanoHTTPD server on the loopback interface (the address provider is injected, so no Android
 * is needed) and talks to it over HTTP: adapter, headers, UTF-8 body reading, limits and lifecycle.
 */
class PhoneRemoteServerTest {
    private val servers = mutableListOf<PhoneRemoteServer>()

    private fun newServer(
        address: String? = "127.0.0.1",
        ports: List<Int> = PhoneRemoteServer.DEFAULT_PORTS,
    ) = PhoneRemoteServer(addressProvider = { address }, ports = ports).also { servers += it }

    @After
    fun tearDown() = servers.forEach { it.stop() }

    private class Response(val code: Int, val body: String, val headers: Map<String?, List<String>>)

    private fun call(method: String, url: String, body: ByteArray? = null, contentType: String? = "application/json"): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        if (body != null) {
            conn.doOutput = true
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
        return Response(code, text, conn.headerFields)
    }

    private fun PhoneRemoteSession.base() = url.substringBefore("/?t=")

    private fun PhoneRemoteSession.token() = url.substringAfter("?t=")

    @Test
    fun noLanAddressMeansNoSession() {
        assertNull(newServer(address = null).start())
    }

    @Test
    fun anAddressProviderThatThrowsMeansNoSession() {
        val server = PhoneRemoteServer(addressProvider = { throw IllegalStateException("no service") })
        assertNull(server.start())
    }

    @Test
    fun sessionUrlHasTheLanAddressARandomPortAndA128BitToken() {
        val server = newServer()
        val session = server.start()
        assertNotNull(session)
        val match = Regex("""^http://127\.0\.0\.1:(\d+)/\?t=([0-9a-f]{32})$""").matchEntire(session!!.url)
        assertNotNull(session.url, match)
        assertTrue(match!!.groupValues[1].toInt() in 8000..8999)
    }

    @Test
    fun startingTwiceKeepsTheSameSessionAndRestartingMakesANewOne() {
        val server = newServer()
        val first = server.start()!!
        assertSame(first, server.start())
        server.stop()
        val second = server.start()!!
        assertNotEquals(first.token(), second.token())
    }

    @Test
    fun servesThePageWithSecurityHeaders() {
        val session = newServer().start()!!
        val response = call("GET", session.url)
        assertEquals(200, response.code)
        assertTrue(response.body.contains("dir=\"rtl\""))
        assertTrue(response.body.contains("تایپ برای بلبل تی&zwnj;وی"))
        assertTrue(response.headers["Content-Type"].orEmpty().single().contains("utf-8", ignoreCase = true))
        assertEquals("no-store", response.headers["Cache-Control"]?.single())
        assertEquals("nosniff", response.headers["X-Content-Type-Options"]?.single())
        assertTrue(response.headers["Content-Security-Policy"]!!.single().contains("default-src 'none'"))
    }

    @Test
    fun requestsWithoutTheTokenAreForbidden() {
        val session = newServer().start()!!
        assertEquals(403, call("GET", session.base() + "/").code)
        assertEquals(403, call("GET", session.base() + "/?t=00000000000000000000000000000000").code)
        assertEquals(403, call("POST", session.base() + "/text", """{"text":"x"}""".toByteArray()).code)
        assertEquals(403, call("GET", session.base() + "/favicon.ico").code)
    }

    @Test
    fun unknownRoutesAreNotFound() {
        val session = newServer().start()!!
        val token = session.token()
        assertEquals(404, call("GET", session.base() + "/other?t=$token").code)
        assertEquals(404, call("GET", session.base() + "/text?t=$token").code)
        assertEquals(404, call("POST", session.base() + "/?t=$token", "{}".toByteArray()).code)
        assertEquals(404, call("DELETE", session.base() + "/text?t=$token").code)
    }

    @Test
    fun persianTextPostedAsUtf8ArrivesNormalisedOnTheFlow() = runBlocking {
        val server = newServer()
        val session = server.start()!!
        val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { server.text.first() } }
        val typed = listOf(0x0643, 0x064A, 0x0627, 0x0646).joinToString("") { it.toChar().toString() } + " بلبل"
        val expected = listOf(0x06A9, 0x06CC, 0x0627, 0x0646).joinToString("") { it.toChar().toString() } + " بلبل"
        val body = """{"text":"$typed"}""".toByteArray(Charsets.UTF_8)
        val response = call("POST", session.base() + "/text?t=" + session.token(), body)
        assertEquals(200, response.code)
        assertEquals(expected, received.await())
    }

    @Test
    fun tokenInTheJsonBodyIsEnough() = runBlocking {
        val server = newServer()
        val session = server.start()!!
        val received = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { server.text.first() } }
        val body = """{"t":"${session.token()}","text":"hello"}""".toByteArray()
        assertEquals(200, call("POST", session.base() + "/text", body).code)
        assertEquals("hello", received.await())
    }

    @Test
    fun oversizedBodyIsRejectedAndSmallOnesStillWork() {
        val server = newServer()
        val session = server.start()!!
        val url = session.base() + "/text?t=" + session.token()
        val big = """{"text":"${"a".repeat(3000)}"}""".toByteArray()
        assertEquals(413, call("POST", url, big).code)
        // The rejected connection must not poison the server.
        assertEquals(200, call("POST", url, """{"text":"ok"}""".toByteArray()).code)
        assertEquals(400, call("POST", url, "garbage".toByteArray()).code)
    }

    @Test
    fun stopClosesThePort() {
        val server = newServer()
        val session = server.start()!!
        assertEquals(200, call("GET", session.url).code)
        server.stop()
        try {
            call("GET", session.url)
            fail("server should be closed")
        } catch (_: java.io.IOException) {
            // connection refused: expected
        }
        server.stop() // idempotent
    }

    @Test
    fun aTakenPortIsSkipped() {
        val blocker = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        blocker.use {
            val free = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { s -> s.localPort }
            val session = newServer(ports = listOf(blocker.localPort, free)).start()
            assertNotNull(session)
            assertTrue(session!!.url.contains(":$free/"))
        }
    }

    @Test
    fun allPortsTakenMeansNoSession() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { blocker ->
            assertNull(newServer(ports = listOf(blocker.localPort)).start())
        }
    }
}
