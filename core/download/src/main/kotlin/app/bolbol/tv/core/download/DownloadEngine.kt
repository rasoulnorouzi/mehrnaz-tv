package app.bolbol.tv.core.download

import app.bolbol.tv.core.database.DownloadDao
import app.bolbol.tv.core.database.DownloadEntity
import app.bolbol.tv.core.model.DownloadFailure
import app.bolbol.tv.core.model.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** What the Android side (foreground service, notification, locks) hears from the engine. Called on the engine's thread. */
internal interface EngineListener {
    /** A download is running; [bytes] of [total] saved. Called when it starts and about once a second. */
    fun onActive(item: DownloadEntity, bytes: Long, total: Long?) {}

    /** Nothing is running (queue empty, or only paused/failed items left). */
    fun onIdle() {}
}

/** Thrown inside a run to end it with a status. */
private class StopDownload(val failure: DownloadFailure, message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * The resumable writer: one download at a time, oldest QUEUED first, each saved as a plain file with HTTP Range resume
 * (README.md §Engine). Room is the only source of truth: a run reads its row and writes status/progress back with
 * UPDATEs only, so a row deleted by [remove] can never come back.
 *
 * Failures: no network → PAUSED/NETWORK until [onNetworkAvailable]; network trouble with the network up → PAUSED/NETWORK
 * and queued again after [DownloadRetry.delayMs], FAILED/NETWORK after [DownloadRetry.MAX_ATTEMPTS] attempts in a row
 * without a saved byte; 4xx → FAILED/SOURCE_GONE; disk full → FAILED/NO_SPACE; anything else → FAILED/UNKNOWN.
 *
 * @param scope runs the queue; give it an IO dispatcher and a SupervisorJob that lives as long as the process.
 */
internal class DownloadEngine(
    private val dao: DownloadDao,
    private val callFactory: Call.Factory,
    private val files: DownloadFiles,
    private val freeBytes: () -> Long,
    private val isNetworkAvailable: () -> Boolean,
    private val scope: CoroutineScope,
    private val listener: EngineListener = object : EngineListener {},
    private val userAgent: String? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: (attempt: Int) -> Long = DownloadRetry::delayMs,
    private val progressIntervalMs: Long = PROGRESS_INTERVAL_MS,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Mutex()
    private var loopJob: Job? = null

    /** The running item (key + job); guarded by [lock]. */
    private var current: Pair<String, Job>? = null

    @Volatile private var activeCall: Call? = null

    /** Failed attempts in a row per key (reset by a saved byte, [retry] or a removal). */
    private val attempts = ConcurrentHashMap<String, Int>()
    private val retryTimers = ConcurrentHashMap<String, Job>()

    /** Starts the queue (idempotent). Whatever was running or paused when the process died is queued again. */
    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch {
            dao.requeueInterrupted()
            loop()
        }
    }

    /** Something was queued: look at the queue now. */
    fun kick() {
        wake.trySend(Unit)
    }

    /** The network is back: downloads paused for it continue. */
    fun onNetworkAvailable() {
        scope.launch {
            if (dao.requeuePaused() > 0) kick()
        }
    }

    /** FAILED/PAUSED → QUEUED with a fresh attempt budget. */
    suspend fun retry(key: String) {
        attempts.remove(key)
        retryTimers.remove(key)?.cancel()
        val row = dao.get(key) ?: return
        val status = parseStatus(row.status)
        if (status == DownloadStatus.FAILED || status == DownloadStatus.PAUSED) {
            dao.updateStatus(key, DownloadStatus.QUEUED.name, null)
            kick()
        }
    }

    /** Deletes the row first (the queue can't pick it again), then stops it if it is running. Files: caller. */
    suspend fun remove(key: String) {
        attempts.remove(key)
        retryTimers.remove(key)?.cancel()
        val running = lock.withLock {
            dao.delete(key)
            current?.takeIf { it.first == key }?.second
        }
        if (running != null) {
            // Cancel the job before the socket, so the read's IOException is seen as a cancellation, not a failure.
            running.cancel()
            activeCall?.cancel()
            running.join()
        }
    }

    /** True while [key] is the running download. */
    suspend fun isRunning(key: String): Boolean = lock.withLock { current?.first == key }

    private suspend fun loop() {
        while (true) {
            val job = lock.withLock {
                val next = dao.nextQueued() ?: return@withLock null
                scope.launch(start = CoroutineStart.LAZY) { runOne(next) }.also { current = next.key to it }
            }
            if (job == null) {
                listener.onIdle()
                wake.receive()
                continue
            }
            job.start()
            job.join()
            lock.withLock { current = null }
        }
    }

    private suspend fun runOne(item: DownloadEntity) {
        val key = item.key
        try {
            dao.updateStatus(key, DownloadStatus.DOWNLOADING.name, null)
            listener.onActive(item, item.bytesDownloaded, item.totalBytes)
            files.dirOf(item.watchKey()).mkdirs()
            saveSidecars(item)
            saveVideo(item)
            // A subtitle/poster that failed before the video gets one more try now.
            saveSidecars(dao.get(key) ?: return)
            dao.updateStatus(key, DownloadStatus.COMPLETED.name, null)
            attempts.remove(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive() // removed meanwhile: the socket error is just the cancellation
            when (e) {
                is StopDownload -> stop(key, e.failure, e)
                is IOException -> stop(key, DownloadFailure.NETWORK, e)
                else -> stop(key, DownloadFailure.UNKNOWN, e)
            }
        } finally {
            activeCall = null
        }
    }

    private suspend fun stop(key: String, failure: DownloadFailure, cause: Throwable) {
        if (failure != DownloadFailure.NETWORK) {
            log("download $key failed: $failure (${cause.message})")
            dao.updateStatus(key, DownloadStatus.FAILED.name, failure.name)
            return
        }
        if (!isNetworkAvailable()) {
            // Not the CDN's fault: wait for onNetworkAvailable, without using up attempts.
            log("download $key paused: no network")
            dao.updateStatus(key, DownloadStatus.PAUSED.name, failure.name)
            return
        }
        val attempt = (attempts[key] ?: 0) + 1
        attempts[key] = attempt
        if (attempt >= DownloadRetry.MAX_ATTEMPTS) {
            log("download $key failed after $attempt attempts: ${cause.message}")
            dao.updateStatus(key, DownloadStatus.FAILED.name, failure.name)
            return
        }
        log("download $key paused (attempt $attempt): ${cause.message}")
        dao.updateStatus(key, DownloadStatus.PAUSED.name, failure.name)
        retryTimers.remove(key)?.cancel()
        retryTimers[key] = scope.launch {
            delay(retryDelayMs(attempt))
            retryTimers.remove(key)
            val row = dao.get(key) ?: return@launch
            if (row.status == DownloadStatus.PAUSED.name) {
                dao.updateStatus(key, DownloadStatus.QUEUED.name, null)
                kick()
            }
        }
    }

    // ---- video ------------------------------------------------------------------------------------------------

    private suspend fun saveVideo(item: DownloadEntity) {
        val key = item.key
        val video = File(item.videoPath)
        if (video.isFile && video.length() > 0 && item.totalBytes?.let { it == video.length() } != false) {
            dao.updateProgress(key, video.length(), video.length())
            return
        }
        val part = files.partFile(video)
        var have = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url(item.url).apply {
            userAgent?.let { header("User-Agent", it) }
            if (have > 0) {
                header("Range", "bytes=$have-")
                item.validator?.let { header("If-Range", it) }
            }
        }.build()
        val call = callFactory.newCall(request)
        activeCall = call
        call.execute().use { response ->
            val body = response.body
            val plan = RangePlan.decide(response.code, have, response.header("Content-Range"), body.contentLength())
            val total: Long?
            when (plan) {
                is RangePlan.Append -> total = plan.total
                is RangePlan.Restart -> {
                    if (have > 0) log("download $key: server sent the whole file, starting over")
                    part.delete()
                    have = 0
                    total = plan.total
                }
                RangePlan.AlreadyComplete -> {
                    finish(key, part, video, have)
                    return
                }
                RangePlan.Discard -> {
                    part.delete()
                    dao.updateProgress(key, 0, item.totalBytes)
                    throw IOException("unusable range response ${response.code} ${response.header("Content-Range")}")
                }
                is RangePlan.Gone -> throw StopDownload(DownloadFailure.SOURCE_GONE, "HTTP ${plan.code}")
                is RangePlan.Transient -> throw IOException("HTTP ${plan.code}")
            }
            val validator = response.header("ETag") ?: response.header("Last-Modified")
            if (validator != item.validator) dao.updateValidator(key, validator)
            if (total != null && (total - have) + SpaceCheck.RUNNING_FLOOR_BYTES > freeBytes()) {
                throw StopDownload(DownloadFailure.NO_SPACE, "needs ${total - have} B, free ${freeBytes()} B")
            }
            dao.updateProgress(key, have, total)
            listener.onActive(item, have, total)

            val startHave = have
            var lastReportMs = nowMs()
            val buffer = ByteArray(BUFFER_BYTES)
            val source = body.byteStream()
            try {
                FileOutputStream(part, /* append = */ true).use { out ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        have += n
                        val now = nowMs()
                        if (now - lastReportMs >= progressIntervalMs) {
                            lastReportMs = now
                            if (have > startHave) attempts.remove(key)
                            dao.updateProgress(key, have, total)
                            listener.onActive(item, have, total)
                            if (freeBytes() < SpaceCheck.RUNNING_FLOOR_BYTES) {
                                throw StopDownload(DownloadFailure.NO_SPACE, "free space under the floor")
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // Keep what was written; the next attempt resumes from part.length().
                if (have > startHave) attempts.remove(key)
                dao.updateProgress(key, have, total)
                if (e !is StopDownload && freeBytes() < SpaceCheck.RUNNING_FLOOR_BYTES) {
                    throw StopDownload(DownloadFailure.NO_SPACE, "write failed with a full disk", e)
                }
                throw e
            }
            if (total != null && have != total) {
                dao.updateProgress(key, have, total)
                if (have > total) part.delete()
                throw IOException("body ended at $have of $total bytes")
            }
            finish(key, part, video, have)
        }
    }

    private suspend fun finish(key: String, part: File, video: File, size: Long) {
        if (video.exists()) video.delete()
        if (!part.renameTo(video)) throw StopDownload(DownloadFailure.UNKNOWN, "rename failed")
        dao.updateProgress(key, size, size)
    }

    // ---- subtitle + poster ------------------------------------------------------------------------------------

    /** Best effort: a failure is logged and tried again later; it never fails the download. */
    private suspend fun saveSidecars(item: DownloadEntity) {
        val key = item.watchKey()
        var subtitlePath = item.subtitlePath?.takeIf { File(it).isFile }
        var posterPath = item.posterPath?.takeIf { File(it).isFile }
        val subtitleUrl = item.subtitleUrl?.takeIf { it.isNotBlank() }
        if (subtitlePath == null && subtitleUrl != null) {
            subtitlePath = fetchSmallFile(subtitleUrl, files.subtitleFile(key), SUBTITLE_MAX_BYTES)?.path
        }
        val posterUrl = item.posterUrl?.takeIf { it.isNotBlank() }
        if (posterPath == null && posterUrl != null) {
            posterPath = fetchSmallFile(posterUrl, files.posterFile(key), POSTER_MAX_BYTES)?.path
        }
        if (subtitlePath != item.subtitlePath || posterPath != item.posterPath) {
            dao.updateSidecars(item.key, subtitlePath, posterPath)
        }
    }

    private suspend fun fetchSmallFile(url: String, target: File, maxBytes: Long): File? {
        currentCoroutineContext().ensureActive()
        val tmp = File(target.path + ".tmp")
        return try {
            val request = Request.Builder().url(url).apply { userAgent?.let { header("User-Agent", it) } }.build()
            val call = callFactory.newCall(request)
            activeCall = call
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body
                if (body.contentLength() > maxBytes) throw IOException("too large")
                val source = body.source()
                if (source.request(maxBytes + 1)) throw IOException("too large")
                target.parentFile?.mkdirs()
                tmp.writeBytes(source.buffer.readByteArray())
            }
            if (!tmp.renameTo(target)) throw IOException("rename failed")
            target
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tmp.delete()
            log("could not save ${target.name}: ${e.message}")
            null
        }
    }

    private fun log(message: String) = DownloadLog.i(message)

    companion object {
        const val PROGRESS_INTERVAL_MS = 1_000L
        private const val BUFFER_BYTES = 64 * 1024
        private const val SUBTITLE_MAX_BYTES = 2L * 1024 * 1024
        private const val POSTER_MAX_BYTES = 5L * 1024 * 1024
    }
}

/** Logcat `BolbolDownload`; silent in JVM unit tests (android.util.Log is a stub there). */
internal object DownloadLog {
    const val TAG = "BolbolDownload"

    fun i(message: String) {
        try {
            android.util.Log.i(TAG, message)
        } catch (_: RuntimeException) {
        }
    }
}
