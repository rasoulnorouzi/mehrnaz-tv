package app.bolbol.tv.core.lanremote

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Where the phone should point its camera: `http://192.168.x.y:port/?t=token`. */
data class PhoneRemoteSession(val url: String)

/**
 * Tiny HTTP server that lets a phone on the same Wi-Fi/LAN type into the TV's search box.
 *
 * Lives only as long as the caller keeps it running ([PhoneRemotePanel] starts it when shown and stops it when
 * dismissed). It binds to the LAN IPv4 address only (never `0.0.0.0`, never loopback) on a random free port in
 * 8000..8999, and every request needs the random 128-bit token that is part of the URL (see [RemoteRequestHandler]).
 */
class PhoneRemoteServer internal constructor(
    private val addressProvider: () -> String?,
    private val tokenSupplier: () -> String = { newRemoteToken() },
    private val ports: List<Int> = DEFAULT_PORTS,
) {
    constructor(context: Context) : this(AndroidLanAddress(context.applicationContext))

    private val mutableText = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Latest text typed on the phone (replace semantics, not append). Already normalised (Persian yeh/kaf). */
    val text: SharedFlow<String> = mutableText.asSharedFlow()

    private var server: RemoteHttpServer? = null
    private var session: PhoneRemoteSession? = null

    /**
     * Starts listening (if not already) on the device's LAN IPv4 address with a new random token.
     * Returns null when the device has no LAN address (offline) or no port could be bound.
     */
    @Synchronized
    fun start(): PhoneRemoteSession? {
        session?.let { if (server?.isAlive == true) return it }
        stopLocked()
        val host = lanAddressOrNull() ?: return null
        val token = tokenSupplier()
        val handler = RemoteRequestHandler(token = token, onText = { mutableText.tryEmit(it) })
        for (port in ports.shuffled().take(MAX_BIND_ATTEMPTS)) {
            val candidate = RemoteHttpServer(host, port, handler)
            try {
                candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            } catch (_: IOException) {
                candidate.stop()
                continue // port taken: try another
            } catch (_: RuntimeException) {
                candidate.stop()
                return null // e.g. SecurityException: retrying will not help
            }
            server = candidate
            return PhoneRemoteSession("http://$host:$port/?t=$token").also { session = it }
        }
        return null
    }

    /** Stops listening and drops open connections. Safe to call repeatedly or without [start]. */
    @Synchronized
    fun stop() = stopLocked()

    private fun lanAddressOrNull(): String? = try {
        addressProvider()
    } catch (_: RuntimeException) {
        null
    }

    private fun stopLocked() {
        server?.stop()
        server = null
        session = null
    }

    internal companion object {
        val DEFAULT_PORTS: List<Int> = (8000..8999).toList()
        const val MAX_BIND_ATTEMPTS = 25
    }
}

private const val DRAIN_LIMIT_BYTES = 64L * 1024

/** Thin NanoHTTPD adapter: builds a [RemoteRequest], asks the handler, adds the security headers. */
private class RemoteHttpServer(
    host: String,
    port: Int,
    private val handler: RemoteRequestHandler,
) : NanoHTTPD(host, port) {

    init {
        setAsyncRunner(BoundedAsyncRunner())
    }

    override fun serve(session: IHTTPSession): Response {
        val contentLength = session.headers["content-length"]?.trim()?.toLongOrNull() ?: -1L
        var consumed = 0L
        val reply = try {
            handler.handle(
                RemoteRequest(
                    method = session.method?.name.orEmpty(),
                    path = session.uri.orEmpty(),
                    queryToken = session.parameters["t"]?.firstOrNull(),
                    contentLength = contentLength,
                    clientId = session.remoteIpAddress.orEmpty(),
                    readBody = { length -> readExactly(session.inputStream, length).also { consumed += it.size } },
                ),
            )
        } catch (_: RuntimeException) {
            RemoteReply.text(500, "error")
        }
        // A rejected POST leaves its body unread; swallow a small one so closing the socket does not reset the
        // connection before the phone has seen our answer. Huge declared bodies are simply cut off.
        if (contentLength in (consumed + 1)..DRAIN_LIMIT_BYTES) drain(session.inputStream, contentLength - consumed)
        val status = Response.Status.lookup(reply.status) ?: Response.Status.INTERNAL_ERROR
        return newFixedLengthResponse(status, reply.contentType, ByteArrayInputStream(reply.body), reply.body.size.toLong()).apply {
            // One short request per connection: no idle keep-alive connections holding the few worker threads.
            addHeader("Connection", "close")
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("X-Frame-Options", "DENY")
            addHeader("Referrer-Policy", "no-referrer")
            addHeader(
                "Content-Security-Policy",
                "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; " +
                    "base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            )
        }
    }

    private fun drain(input: InputStream, bytes: Long) {
        val buffer = ByteArray(1024)
        var left = bytes
        try {
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (n < 0) break
                left -= n
            }
        } catch (_: IOException) {
            // Timed out or closed by the peer: nothing more to drain.
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) buffer else buffer.copyOf(read)
    }
}

/**
 * Worker pool for connections: at most 6 threads and 8 waiting connections, the rest are closed. The default
 * NanoHTTPD runner starts an unbounded thread per connection, which a flood on the LAN could use to exhaust a 2 GB TV.
 */
private class BoundedAsyncRunner : NanoHTTPD.AsyncRunner {
    private val running = Collections.synchronizedSet(LinkedHashSet<NanoHTTPD.ClientHandler>())
    private val pool = ThreadPoolExecutor(
        MAX_THREADS, MAX_THREADS, 5, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_QUEUED),
        { runnable -> Thread(runnable, "bolbol-phone-remote").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
        running.add(clientHandler)
        try {
            pool.execute(clientHandler)
        } catch (_: RejectedExecutionException) {
            running.remove(clientHandler)
            clientHandler.close()
        }
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        running.remove(clientHandler)
    }

    override fun closeAll() {
        synchronized(running) { running.toList() }.forEach { it.close() }
        pool.shutdownNow()
    }

    private companion object {
        const val MAX_THREADS = 6
        const val MAX_QUEUED = 8
    }
}
