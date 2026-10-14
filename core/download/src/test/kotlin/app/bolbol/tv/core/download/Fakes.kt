package app.bolbol.tv.core.download

import app.bolbol.tv.core.database.DownloadDao
import app.bolbol.tv.core.database.DownloadEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory DownloadDao with the same semantics as the Room queries. */
class FakeDownloadDao : DownloadDao {
    val rows = MutableStateFlow<Map<String, DownloadEntity>>(emptyMap())

    fun row(key: String): DownloadEntity? = rows.value[key]

    private fun sorted(list: Collection<DownloadEntity>) = list.sortedWith(compareByDescending<DownloadEntity> { it.createdAtMs }.thenByDescending { it.key })

    private fun change(key: String, f: (DownloadEntity) -> DownloadEntity) =
        rows.update { m -> m[key]?.let { m + (key to f(it)) } ?: m }

    override fun observeAll(): Flow<List<DownloadEntity>> = rows.map { sorted(it.values) }
    override fun observeForTitle(postId: Long): Flow<List<DownloadEntity>> =
        rows.map { m -> m.values.filter { it.postId == postId }.sortedWith(compareBy({ it.season }, { it.episode })) }

    override suspend fun all(): List<DownloadEntity> = sorted(rows.value.values)
    override suspend fun get(key: String): DownloadEntity? = rows.value[key]
    override suspend fun nextQueued(): DownloadEntity? =
        rows.value.values.filter { it.status == "QUEUED" }.minWithOrNull(compareBy({ it.createdAtMs }, { it.key }))

    override suspend fun upsert(entity: DownloadEntity) = rows.update { it + (entity.key to entity) }
    override suspend fun updateProgress(key: String, bytes: Long, total: Long?) =
        change(key) { it.copy(bytesDownloaded = bytes, totalBytes = total) }

    override suspend fun updateStatus(key: String, status: String, failure: String?) =
        change(key) { it.copy(status = status, failure = failure) }

    override suspend fun updateValidator(key: String, validator: String?) = change(key) { it.copy(validator = validator) }
    override suspend fun updateSidecars(key: String, subtitlePath: String?, posterPath: String?) =
        change(key) { it.copy(subtitlePath = subtitlePath, posterPath = posterPath) }

    override suspend fun requeueInterrupted(): Int = requeue(setOf("DOWNLOADING", "PAUSED"))
    override suspend fun requeuePaused(): Int = requeue(setOf("PAUSED"))

    private fun requeue(from: Set<String>): Int {
        var n = 0
        rows.update { m ->
            m.mapValues { (_, e) -> if (e.status in from) e.copy(status = "QUEUED", failure = null).also { n++ } else e }
        }
        return n
    }

    override suspend fun delete(key: String) = rows.update { it - key }
    override suspend fun deleteAll() = rows.update { emptyMap() }
}

/**
 * Serves one video file with Range/If-Range support like the CDN (nginx), plus a subtitle and a poster.
 * [failNext] makes the next video request answer that response instead.
 */
class FakeCdn(val video: ByteArray, val etag: String = "\"v1\"") : Dispatcher() {
    val videoRequests = CopyOnWriteArrayList<RecordedRequest>()
    val failNext = CopyOnWriteArrayList<MockResponse>()
    var ignoreRange = false
    var throttleBytesPerSecond: Long? = null
    var headAllowed = true

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: ""
        return when {
            path.startsWith("/sub.vtt") -> MockResponse().setBody("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nسلام\n")
            path.startsWith("/poster.jpg") -> MockResponse().setBody(Buffer().write(ByteArray(2048) { 7 }))
            path.startsWith("/video.mp4") -> video(request)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun video(request: RecordedRequest): MockResponse {
        if (request.method == "HEAD") {
            return if (headAllowed) MockResponse().setHeader("Content-Length", video.size) else MockResponse().setResponseCode(405)
        }
        videoRequests += request
        failNext.removeFirstOrNull()?.let { return it }
        val range = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
        val ifRange = request.getHeader("If-Range")
        val honour = range != null && !ignoreRange && (ifRange == null || ifRange == etag)
        val response = if (honour) {
            val start = range!!
            if (start >= video.size) {
                MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${video.size}")
            } else {
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-${video.size - 1}/${video.size}")
                    .setBody(Buffer().write(video, start.toInt(), video.size - start.toInt()))
            }
        } else {
            MockResponse().setBody(Buffer().write(video))
        }
        throttleBytesPerSecond?.let { response.throttleBody(it / 10, 100, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return response.setHeader("ETag", etag)
    }
}
