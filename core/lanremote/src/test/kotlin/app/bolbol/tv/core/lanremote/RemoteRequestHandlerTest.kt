package app.bolbol.tv.core.lanremote

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRequestHandlerTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private val received = mutableListOf<String>()
    private var nowNanos = 0L
    private var bodyReads = 0
    private val handler = RemoteRequestHandler(
        token = token,
        onText = { received += it },
        limiter = RateLimiter(perSecond = 20, nowNanos = { nowNanos }),
    )

    private fun request(
        method: String,
        path: String,
        queryToken: String? = null,
        body: String? = null,
        contentLength: Long? = null,
        client: String = "192.168.1.50",
        failRead: Boolean = false,
    ): RemoteRequest {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        return RemoteRequest(
            method = method,
            path = path,
            queryToken = queryToken,
            contentLength = contentLength ?: (bytes?.size?.toLong() ?: -1L),
            clientId = client,
            readBody = { length ->
                bodyReads++
                if (failRead) throw IOException("timeout")
                (bytes ?: ByteArray(0)).copyOf(minOf(length, bytes?.size ?: 0))
            },
        )
    }

    private fun RemoteReply.bodyText() = String(body, Charsets.UTF_8)

    private fun post(body: String?, queryToken: String? = token, contentLength: Long? = null) =
        handler.handle(request("POST", "/text", queryToken, body, contentLength))

    // ---- GET / (the page) ----

    @Test
    fun pageIsServedWithTheToken() {
        val reply = handler.handle(request("GET", "/", token))
        assertEquals(200, reply.status)
        assertEquals("text/html; charset=utf-8", reply.contentType)
        val html = reply.bodyText()
        assertTrue(html.contains("dir=\"rtl\""))
        assertTrue(html.contains("autofocus"))
        assertTrue(html.contains("enterkeyhint=\"search\""))
        assertTrue(html.contains("<meta charset=\"utf-8\">"))
        assertTrue(html.contains("/text?t="))
        assertTrue(html.contains("250"))
    }

    @Test
    fun pageIsPersianAndSelfContained() {
        val html = handler.handle(request("GET", "/", token)).bodyText()
        assertTrue(html.contains("تایپ برای بلبل تی&zwnj;وی"))
        assertTrue(html.contains("ارسال شد ✓"))
        // No external resources of any kind.
        assertFalse(html.contains("http"))
        assertFalse(html.contains("<link"))
        assertFalse(html.contains("src="))
    }

    @Test
    fun pageNeedsTheToken() {
        assertEquals(403, handler.handle(request("GET", "/")).status)
        assertEquals(403, handler.handle(request("GET", "/", "")).status)
        assertEquals(403, handler.handle(request("GET", "/", "wrong")).status)
        assertEquals(403, handler.handle(request("GET", "/", token.dropLast(1))).status)
        assertEquals(403, handler.handle(request("GET", "/", token + "0")).status)
        assertEquals(403, handler.handle(request("GET", "/", token.uppercase())).status)
        assertEquals(403, handler.handle(request("GET", "/", token.replaceRange(31, 32, "0"))).status)
    }

    // ---- routing ----

    @Test
    fun onlyTheTwoRoutesExist() {
        assertEquals(404, handler.handle(request("GET", "/other", token)).status)
        assertEquals(404, handler.handle(request("GET", "/favicon.ico", token)).status)
        assertEquals(404, handler.handle(request("GET", "/text", token)).status)
        assertEquals(404, handler.handle(request("GET", "/text/", token)).status)
        assertEquals(404, handler.handle(request("POST", "/", token, "{}")).status)
        assertEquals(404, handler.handle(request("DELETE", "/text", token)).status)
        assertEquals(404, handler.handle(request("OPTIONS", "/text", token)).status)
        assertEquals(404, handler.handle(request("PUT", "/text", token, "{}")).status)
        assertEquals(404, handler.handle(request("HEAD", "/", token)).status)
    }

    @Test
    fun unknownRoutesRevealNothingWithoutTheToken() {
        assertEquals(403, handler.handle(request("GET", "/other")).status)
        assertEquals(403, handler.handle(request("GET", "/favicon.ico", "nope")).status)
        assertEquals(403, handler.handle(request("DELETE", "/text")).status)
    }

    // ---- POST /text ----

    @Test
    fun textWithQueryTokenIsDelivered() {
        val reply = post("""{"text":"dune"}""")
        assertEquals(200, reply.status)
        assertEquals("""{"ok":true}""", reply.bodyText())
        assertEquals(listOf("dune"), received)
    }

    @Test
    fun textWithBodyTokenIsDelivered() {
        val reply = post("""{"t":"$token","text":"matrix"}""", queryToken = null)
        assertEquals(200, reply.status)
        assertEquals(listOf("matrix"), received)
    }

    @Test
    fun persianTextSurvivesAndIsNormalised() {
        // Typed on an Arabic-mode keyboard: kaf, yeh, mim, yeh, alef with the Arabic code points.
        val arabicTyped = listOf(0x0643, 0x064A, 0x0645, 0x064A, 0x0627).joinToString("") { it.toChar().toString() }
        val persian = listOf(0x06A9, 0x06CC, 0x0645, 0x06CC, 0x0627).joinToString("") { it.toChar().toString() }
        assertEquals(200, post("""{"text":"$arabicTyped"}""").status)
        assertEquals(listOf(persian), received)
        val withZwnj = "بلبل تی" + 0x200C.toChar() + "وی"
        post("""{"text":"$withZwnj"}""")
        assertEquals(withZwnj, received.last())
    }

    @Test
    fun replaceSemanticsEveryPostIsTheWholeText() {
        post("""{"text":"ab"}""")
        post("""{"text":"abc"}""")
        post("""{"text":""}""")
        assertEquals(listOf("ab", "abc", ""), received)
    }

    @Test
    fun wrongOrMissingTokenIsForbiddenAndNothingIsDelivered() {
        assertEquals(403, post("""{"text":"x"}""", queryToken = "wrong").status)
        assertEquals(403, post("""{"text":"x"}""", queryToken = null).status)
        assertEquals(403, post("""{"t":"wrong","text":"x"}""", queryToken = null).status)
        assertEquals(403, post("""{"t":"","text":"x"}""", queryToken = null).status)
        assertEquals(403, post("not json", queryToken = null).status)
        assertTrue(received.isEmpty())
    }

    @Test
    fun aWrongQueryTokenIsFinalEvenIfTheBodyHasTheRightOne() {
        val reply = post("""{"t":"$token","text":"x"}""", queryToken = "wrong")
        assertEquals(403, reply.status)
        assertEquals(0, bodyReads)
        assertTrue(received.isEmpty())
    }

    @Test
    fun bodyLargerThanTwoKilobytesIsRejectedUnread() {
        val big = """{"text":"${"a".repeat(MAX_BODY_BYTES)}"}"""
        assertEquals(413, post(big).status)
        assertEquals(413, post("""{"text":"x"}""", contentLength = (MAX_BODY_BYTES + 1).toLong()).status)
        assertEquals(0, bodyReads)
        assertTrue(received.isEmpty())
    }

    @Test
    fun bodyOfExactlyTwoKilobytesIsAccepted() {
        val base = """{"text":"abc"}"""
        val padded = base + " ".repeat(MAX_BODY_BYTES - base.length)
        assertEquals(MAX_BODY_BYTES, padded.toByteArray().size)
        assertEquals(200, post(padded).status)
        assertEquals(listOf("abc"), received)
    }

    @Test
    fun oversizedBodyWithoutAQueryTokenCannotBeAuthenticated() {
        val big = """{"t":"$token","text":"${"a".repeat(MAX_BODY_BYTES)}"}"""
        assertEquals(403, post(big, queryToken = null).status)
        assertEquals(0, bodyReads)
    }

    @Test
    fun missingOrEmptyBodyIsBadRequest() {
        assertEquals(400, handler.handle(request("POST", "/text", token)).status) // no Content-Length
        assertEquals(400, post("", contentLength = 0).status)
        assertEquals(0, bodyReads)
    }

    @Test
    fun malformedBodyIsBadRequest() {
        assertEquals(400, post("not json").status)
        assertEquals(400, post("""{"text":5}""").status)
        assertEquals(400, post("""{"nope":"x"}""").status)
        assertEquals(400, post("""{"text":"x"} trailing""").status)
        assertTrue(received.isEmpty())
    }

    @Test
    fun unreadableBodyIsBadRequest() {
        val reply = handler.handle(request("POST", "/text", token, """{"text":"x"}""", failRead = true))
        assertEquals(400, reply.status)
        assertTrue(received.isEmpty())
    }

    @Test
    fun controlCharactersAreStrippedFromDeliveredText() {
        val bs = '\\'
        post("{\"text\":\"a${bs}nb${bs}u0000c\"}")
        assertEquals(listOf("a bc"), received)
    }

    // ---- rate limit ----

    @Test
    fun twentyRequestsPerSecondThenTooManyRequests() {
        repeat(20) { assertEquals("request ${it + 1}", 200, handler.handle(request("GET", "/", token)).status) }
        assertEquals(429, handler.handle(request("GET", "/", token)).status)
        assertEquals(429, handler.handle(request("GET", "/", "wrong-token")).status) // bad guesses are throttled too
        nowNanos += 1_000_000_000L
        assertEquals(200, handler.handle(request("GET", "/", token)).status)
    }

    @Test
    fun theLimitIsPerClient() {
        repeat(20) { handler.handle(request("GET", "/", token, client = "10.0.0.1")) }
        assertEquals(429, handler.handle(request("GET", "/", token, client = "10.0.0.1")).status)
        assertEquals(200, handler.handle(request("GET", "/", token, client = "10.0.0.2")).status)
    }

    @Test
    fun theLimiterRefillsGradually() {
        val limiter = RateLimiter(perSecond = 20, nowNanos = { nowNanos })
        repeat(20) { assertTrue(limiter.tryAcquire("a")) }
        assertFalse(limiter.tryAcquire("a"))
        nowNanos += 60_000_000L // 60 ms = a bit more than one token
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
    }

    @Test
    fun theLimiterStateStaysBounded() {
        val limiter = RateLimiter(perSecond = 20, nowNanos = { nowNanos }, maxClients = 8)
        repeat(1000) { assertTrue(limiter.tryAcquire("client-$it")) }
    }

    @Test
    fun humanTypingNeverHitsTheLimit() {
        // 4 requests per second (250 ms debounce) for a minute.
        repeat(240) {
            nowNanos += 250_000_000L
            assertEquals(200, post("""{"text":"x$it"}""").status)
        }
    }
}
