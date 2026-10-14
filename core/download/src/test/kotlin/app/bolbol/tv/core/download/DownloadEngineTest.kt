package app.bolbol.tv.core.download

import app.bolbol.tv.core.database.DownloadEntity
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.DownloadFailure
import app.bolbol.tv.core.model.DownloadRequest
import app.bolbol.tv.core.model.DownloadStatus
import app.bolbol.tv.core.model.StartDownloadResult
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.WatchKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** The engine + repository against a fake CDN (MockWebServer) and an in-memory DAO, on real files. */
class DownloadEngineTest {
    private val video = Random(7).nextBytes(300_000)
    private val cdn = FakeCdn(video)
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val dao = FakeDownloadDao()
    private lateinit var root: File
    private lateinit var files: DownloadFiles
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val free = AtomicLong(10L * 1024 * 1024 * 1024)
    private val online = AtomicBoolean(true)
    private val events = mutableListOf<String>()
    private lateinit var engine: DownloadEngine
    private lateinit var repo: DownloadRepositoryImpl

    private val key = WatchKey(9001, 1, 2)
    private val k = key.asString()

    @Before fun setUp() {
        server.dispatcher = cdn
        server.start()
        root = Files.createTempDirectory("dl").toFile()
        files = DownloadFiles(root)
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
        root.deleteRecursively()
    }

    private fun build(retryDelayMs: Long = 50) {
        engine = DownloadEngine(
            dao = dao,
            callFactory = client,
            files = files,
            freeBytes = free::get,
            isNetworkAvailable = online::get,
            scope = scope,
            listener = object : EngineListener {
                override fun onActive(item: DownloadEntity, bytes: Long, total: Long?) {
                    synchronized(events) { events += "active" }
                }

                override fun onIdle() {
                    synchronized(events) { events += "idle" }
                }
            },
            retryDelayMs = { retryDelayMs },
            progressIntervalMs = 0,
        )
        repo = DownloadRepositoryImpl(dao, engine, files, client, free::get, online::get)
    }

    private fun stream(path: String = "/video.mp4") = StreamOption(
        quality = 720,
        audio = AudioType.SUBTITLED,
        url = server.url(path).toString(),
        subtitleUrl = server.url("/sub.vtt").toString(),
        label = "WEB-DL 720p",
        sizeText = null,
    )

    private fun request(s: StreamOption = stream()) = DownloadRequest(
        key = key,
        title = DownloadLogicTest.title.copy(posterUrl = server.url("/poster.jpg").toString()),
        subtitleLine = "فصل ۱ · قسمت ۲",
        stream = s,
    )

    private fun awaitStatus(status: DownloadStatus, timeoutMs: Long = 10_000): DownloadEntity = runBlocking {
        withTimeout(timeoutMs) {
            dao.rows.first { it[k]?.status == status.name }.getValue(k)
        }
    }

    private fun videoFile() = files.videoFile(key, stream().url)

    // ---- tests -------------------------------------------------------------------------------------------------

    @Test fun savesVideoSubtitleAndPosterThenPlaysOffline() = runBlocking<Unit> {
        build()
        assertEquals(StartDownloadResult.Started, repo.start(request()))
        val done = awaitStatus(DownloadStatus.COMPLETED)
        assertArrayEquals(video, videoFile().readBytes())
        assertFalse(files.partFile(videoFile()).exists())
        assertEquals(video.size.toLong(), done.bytesDownloaded)
        assertEquals(video.size.toLong(), done.totalBytes)
        assertNotNull(done.subtitlePath)
        assertTrue(File(done.subtitlePath!!).readText().startsWith("WEBVTT"))
        assertEquals(2048L, File(done.posterPath!!).length())
        assertEquals("\"v1\"", done.validator)

        val item = repo.downloads().first().single()
        assertEquals(100, item.percent)
        assertEquals(DownloadFiles.uriOf(File(done.posterPath!!)), item.title.posterUrl)

        val offline = repo.offlineMedia(key)!!
        assertEquals(DownloadFiles.uriOf(videoFile()), offline.stream.url)
        assertEquals(DownloadFiles.uriOf(files.subtitleFile(key)), offline.subtitleUri)
        assertEquals(offline.subtitleUri, offline.stream.subtitleUrl)
        assertEquals(720, offline.stream.quality)
        // Asking again does nothing.
        assertEquals(StartDownloadResult.AlreadySaved, repo.start(request()))
        delay(100)
        synchronized(events) { assertEquals("idle", events.last()) }
    }

    @Test fun resumesAPartialFileWithRangeAndIfRangeAfterARestart() = runBlocking<Unit> {
        val have = 120_000
        files.dirOf(key).mkdirs()
        files.partFile(videoFile()).writeBytes(video.copyOf(have))
        // The process died while DOWNLOADING: the next start queues it again.
        dao.upsert(
            request().toEntity(videoFile().path, 1, video.size.toLong())
                .copy(status = DownloadStatus.DOWNLOADING.name, bytesDownloaded = have.toLong(), validator = cdn.etag),
        )
        build()
        awaitStatus(DownloadStatus.COMPLETED)
        assertArrayEquals(video, videoFile().readBytes())
        val r = cdn.videoRequests.single()
        assertEquals("bytes=$have-", r.getHeader("Range"))
        assertEquals(cdn.etag, r.getHeader("If-Range"))
    }

    @Test fun changedFileOnTheServerStartsOver() = runBlocking<Unit> {
        files.dirOf(key).mkdirs()
        files.partFile(videoFile()).writeBytes(ByteArray(50_000) { 1 })
        dao.upsert(request().toEntity(videoFile().path, 1, null).copy(bytesDownloaded = 50_000, validator = "\"old\""))
        build()
        awaitStatus(DownloadStatus.COMPLETED)
        assertArrayEquals(video, videoFile().readBytes())
    }

    @Test fun serverWithoutRangeSupportStartsOver() = runBlocking<Unit> {
        cdn.ignoreRange = true
        files.dirOf(key).mkdirs()
        files.partFile(videoFile()).writeBytes(video.copyOf(10_000))
        dao.upsert(request().toEntity(videoFile().path, 1, null).copy(bytesDownloaded = 10_000))
        build()
        awaitStatus(DownloadStatus.COMPLETED)
        assertArrayEquals(video, videoFile().readBytes())
    }

    @Test fun droppedConnectionPausesThenResumesFromWhereItStopped() = runBlocking<Unit> {
        build()
        cdn.failNext += MockResponse().setBody(okio.Buffer().write(video)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        repo.start(request())
        awaitStatus(DownloadStatus.COMPLETED)
        assertArrayEquals(video, videoFile().readBytes())
        assertEquals(2, cdn.videoRequests.size)
        val resumeAt = cdn.videoRequests[1].getHeader("Range")
        assertNotNull("second request resumes", resumeAt)
        assertTrue(resumeAt!!.removePrefix("bytes=").removeSuffix("-").toLong() > 0)
    }

    @Test fun serverErrorIsRetriedAfterADelay() = runBlocking<Unit> {
        build()
        cdn.failNext += MockResponse().setResponseCode(503)
        repo.start(request())
        awaitStatus(DownloadStatus.COMPLETED)
        assertEquals(2, cdn.videoRequests.size)
    }

    @Test fun tooManyFailuresInARowFail() = runBlocking<Unit> {
        build(retryDelayMs = 1)
        repeat(DownloadRetry.MAX_ATTEMPTS) { cdn.failNext += MockResponse().setResponseCode(500) }
        repo.start(request())
        val failed = awaitStatus(DownloadStatus.FAILED)
        assertEquals(DownloadFailure.NETWORK.name, failed.failure)
        // «تلاش دوباره» starts a fresh budget and finishes.
        repo.retry(key)
        awaitStatus(DownloadStatus.COMPLETED)
    }

    @Test fun missingFileFailsAsSourceGone() = runBlocking<Unit> {
        build()
        repo.start(request(stream("/gone.mp4")))
        val failed = awaitStatus(DownloadStatus.FAILED)
        assertEquals(DownloadFailure.SOURCE_GONE.name, failed.failure)
        assertEquals(DownloadFailure.SOURCE_GONE, repo.downloads().first().single().failure)
        assertNull(repo.offlineMedia(key))
    }

    @Test fun noNetworkPausesUntilItComesBack() = runBlocking<Unit> {
        // Queued earlier; the app starts with the network down and the transfer fails.
        dao.upsert(request().toEntity(videoFile().path, 1, null))
        online.set(false)
        cdn.failNext += MockResponse().setResponseCode(503) // (a dropped connection is retried by OkHttp itself)
        build()
        val paused = awaitStatus(DownloadStatus.PAUSED)
        assertEquals(DownloadFailure.NETWORK.name, paused.failure)
        delay(300) // retry timers don't run while offline
        assertEquals(DownloadStatus.PAUSED.name, dao.row(k)!!.status)
        online.set(true)
        engine.onNetworkAvailable()
        awaitStatus(DownloadStatus.COMPLETED)
        assertArrayEquals(video, videoFile().readBytes())
    }

    @Test fun startRefusesWhenTheFileDoesNotFit() = runBlocking<Unit> {
        build()
        free.set(video.size + SpaceCheck.MARGIN_BYTES - 1)
        val r = repo.start(request())
        assertEquals(StartDownloadResult.NotEnoughSpace(video.size + SpaceCheck.MARGIN_BYTES, video.size + SpaceCheck.MARGIN_BYTES - 1), r)
        assertNull(dao.row(k))
    }

    @Test fun startOfflineFailsWithNetwork() = runBlocking<Unit> {
        build()
        online.set(false)
        assertEquals(StartDownloadResult.Failed(DownloadFailure.NETWORK), repo.start(request()))
    }

    @Test fun diskFillingUpMeanwhileFailsWithNoSpace() = runBlocking<Unit> {
        build()
        cdn.throttleBytesPerSecond = 100_000
        free.set(SpaceCheck.MARGIN_BYTES + video.size) // fits at start…
        repo.start(request())
        withTimeout(5_000) { dao.rows.first { (it[k]?.bytesDownloaded ?: 0) > 0 } }
        free.set(SpaceCheck.RUNNING_FLOOR_BYTES - 1) // …then another app fills the disk
        val failed = awaitStatus(DownloadStatus.FAILED)
        assertEquals(DownloadFailure.NO_SPACE.name, failed.failure)
    }

    @Test fun removeStopsARunningDownloadAndDeletesEverything() = runBlocking<Unit> {
        build()
        cdn.throttleBytesPerSecond = 50_000 // 6 s for the file
        repo.start(request())
        withTimeout(5_000) { dao.rows.first { (it[k]?.bytesDownloaded ?: 0) > 0 } }
        repo.remove(key)
        assertNull(dao.row(k))
        assertFalse(files.dirOf(key).exists())
        delay(300)
        assertNull("the run never writes the row back", dao.row(k))
        assertFalse(files.dirOf(key).exists())
    }

    @Test fun oneAtATimeInTheOrderAsked() = runBlocking<Unit> {
        build()
        cdn.throttleBytesPerSecond = 200_000
        val other = WatchKey(9001, 1, 3)
        repo.start(request())
        delay(20)
        repo.start(request().copy(key = other))
        withTimeout(5_000) { dao.rows.first { it[k]?.status == DownloadStatus.DOWNLOADING.name } }
        assertEquals(DownloadStatus.QUEUED.name, dao.row(other.asString())!!.status)
        withTimeout(10_000) { dao.rows.first { m -> m.values.all { it.status == DownloadStatus.COMPLETED.name } } }
    }

    @Test fun anotherQualityReplacesAnUnfinishedDownload() = runBlocking<Unit> {
        dao.upsert(request().toEntity(videoFile().path, 1, null).copy(status = DownloadStatus.FAILED.name, failure = "NETWORK"))
        files.dirOf(key).mkdirs()
        files.partFile(videoFile()).writeBytes(ByteArray(10) { 9 }) // not a prefix of the 1080p file
        build()
        val s1080 = stream().copy(quality = 1080, url = server.url("/video.mp4?q=1080").toString())
        assertEquals(StartDownloadResult.Started, repo.start(request(s1080)))
        assertEquals(1080, awaitStatus(DownloadStatus.COMPLETED).quality)
        assertArrayEquals(video, videoFile().readBytes())
        assertNull("no Range: the old partial file was deleted", cdn.videoRequests.single().getHeader("Range"))
    }

    @Test fun sameStreamAgainRetriesAFailedDownload() = runBlocking<Unit> {
        dao.upsert(request().toEntity(videoFile().path, 1, null).copy(status = DownloadStatus.FAILED.name, failure = "NETWORK"))
        build()
        assertEquals(StartDownloadResult.Started, repo.start(request()))
        awaitStatus(DownloadStatus.COMPLETED)
    }

    @Test fun sizeComesFromHeadOnceThenFromTheCache() = runBlocking<Unit> {
        build()
        assertEquals(video.size.toLong(), repo.sizeOf(stream()))
        assertEquals(video.size.toLong(), repo.sizeOf(stream()))
        assertEquals(1, server.requestCount)
    }

    @Test fun sizeFallsBackToARangeProbe() = runBlocking<Unit> {
        build()
        cdn.headAllowed = false
        assertEquals(video.size.toLong(), repo.sizeOf(stream()))
        assertEquals("bytes=0-0", cdn.videoRequests.single().getHeader("Range"))
        assertNull(repo.sizeOf(stream("/nothing")))
    }

    @Test fun removeAllEmptiesTheFolder() = runBlocking<Unit> {
        build()
        repo.start(request())
        awaitStatus(DownloadStatus.COMPLETED)
        File(root, "orphan_0_0").mkdirs()
        assertTrue(repo.storage().usedByDownloadsBytes >= video.size)
        repo.removeAll()
        assertTrue(dao.rows.value.isEmpty())
        assertEquals(0, root.listFiles()!!.size)
        assertEquals(0L, repo.storage().usedByDownloadsBytes)
    }
}
