package app.bolbol.tv.core.download

import android.content.Context
import android.os.StatFs
import app.bolbol.tv.core.database.DownloadDao
import app.bolbol.tv.core.database.DownloadEntity
import app.bolbol.tv.core.model.DownloadFailure
import app.bolbol.tv.core.model.DownloadItem
import app.bolbol.tv.core.model.DownloadRepository
import app.bolbol.tv.core.model.DownloadRequest
import app.bolbol.tv.core.model.DownloadStatus
import app.bolbol.tv.core.model.OfflineMedia
import app.bolbol.tv.core.model.StartDownloadResult
import app.bolbol.tv.core.model.StorageInfo
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.WatchKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * core:model's [DownloadRepository] (v1.4 offline saving; README.md). One per process, created by `:app`:
 * ```
 * val downloads = DownloadRepositoryImpl(appContext, data.okHttpClient, data.downloadDao)
 * ```
 * Construction is cheap; it starts the queue at once, so whatever was saving when the app was closed continues
 * (create it at app start, not lazily on the «دانلودها» screen). Files live in the app's private storage
 * (`filesDir/downloads/<postId>_<season>_<episode>/`), so no storage permission is needed and an uninstall removes them.
 */
class DownloadRepositoryImpl internal constructor(
    private val dao: DownloadDao,
    private val engine: DownloadEngine,
    private val files: DownloadFiles,
    private val probeFactory: Call.Factory,
    private val freeBytes: () -> Long,
    private val isNetworkAvailable: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String? = null,
) : DownloadRepository {

    /**
     * @param okHttpClient the app's shared client (`DataGraph.okHttpClient`); downloads use a derived client with the
     *   same pool but no disk cache (video bytes must never go into the 50 MB API cache) and download timeouts.
     * @param dao `DataGraph.downloadDao`.
     */
    constructor(context: Context, okHttpClient: OkHttpClient, dao: DownloadDao, userAgent: String? = null) :
        this(AndroidParts(context.applicationContext, okHttpClient, dao, userAgent))

    private constructor(parts: AndroidParts) : this(
        dao = parts.dao,
        engine = parts.engine,
        files = parts.files,
        probeFactory = parts.client,
        freeBytes = parts::freeBytes,
        isNetworkAvailable = parts.network::isAvailable,
        userAgent = parts.userAgent,
    ) {
        parts.network.watch(engine::onNetworkAvailable)
    }

    private val sizeCache = ConcurrentHashMap<String, Long>()

    init {
        engine.start()
    }

    override fun downloads(): Flow<List<DownloadItem>> = dao.observeAll().map { rows -> rows.map(DownloadEntity::toItem) }

    override fun downloadsForTitle(postId: Long): Flow<List<DownloadItem>> =
        dao.observeForTitle(postId).map { rows -> rows.map(DownloadEntity::toItem) }

    override suspend fun storage(): StorageInfo = withContext(io) {
        StorageInfo(freeBytes = freeBytes(), usedByDownloadsBytes = files.usedBytes())
    }

    override suspend fun sizeOf(stream: StreamOption): Long? {
        sizeCache[stream.url]?.let { return it }
        return withContext(io) { probeSize(stream.url) }?.also { sizeCache[stream.url] = it }
    }

    /**
     * - a COMPLETED item whose file is present → [StartDownloadResult.AlreadySaved] (whatever quality was asked);
     * - an unfinished item of the same stream → [StartDownloadResult.Started] (a FAILED/PAUSED one is retried, keeping
     *   the bytes already saved); of another stream → replaced (its partial file is deleted);
     * - no network → `Failed(NETWORK)`; size + [SpaceCheck.MARGIN_BYTES] (+ what other queued downloads still need)
     *   more than the free space → [StartDownloadResult.NotEnoughSpace].
     */
    override suspend fun start(request: DownloadRequest): StartDownloadResult = withContext(io) {
        try {
            startInternal(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DownloadLog.i("start ${request.key.asString()} failed: $e")
            StartDownloadResult.Failed(DownloadFailure.UNKNOWN)
        }
    }

    private suspend fun startInternal(request: DownloadRequest): StartDownloadResult {
        val key = request.key.asString()
        val existing = dao.get(key)
        if (existing != null) {
            val status = parseStatus(existing.status)
            when {
                status == DownloadStatus.COMPLETED && videoOf(existing) != null -> return StartDownloadResult.AlreadySaved
                status != DownloadStatus.COMPLETED && existing.url == request.stream.url -> {
                    engine.retry(key) // no-op while QUEUED/DOWNLOADING
                    return StartDownloadResult.Started
                }
                else -> remove(request.key)
            }
        }
        if (!isNetworkAvailable()) return StartDownloadResult.Failed(DownloadFailure.NETWORK)
        val size = sizeOf(request.stream)
        SpaceCheck.check(size, freeBytes(), SpaceCheck.reservedBytes(dao.all()))?.let { return it }
        val video = files.videoFile(request.key, request.stream.url)
        dao.upsert(request.toEntity(videoPath = video.path, createdAtMs = nowMs(), totalBytes = size))
        engine.kick()
        return StartDownloadResult.Started
    }

    override suspend fun retry(key: WatchKey) = engine.retry(key.asString())

    override suspend fun remove(key: WatchKey) {
        engine.remove(key.asString())
        withContext(io) { files.dirOf(key).deleteRecursively() }
    }

    override suspend fun removeAll() {
        for (row in dao.all()) remove(row.watchKey())
        // Also anything left behind by a crash between a delete and its files.
        withContext(io) { files.root.listFiles()?.forEach { it.deleteRecursively() } }
    }

    override suspend fun offlineMedia(key: WatchKey): OfflineMedia? = withContext(io) {
        val row = dao.get(key.asString()) ?: return@withContext null
        if (parseStatus(row.status) != DownloadStatus.COMPLETED) return@withContext null
        val video = videoOf(row) ?: return@withContext null
        val subtitleUri = row.subtitlePath?.let(::File)?.takeIf { it.isFile && it.length() > 0 }?.let(DownloadFiles::uriOf)
        val remote = row.remoteStream()
        OfflineMedia(
            key = key,
            // A subtitle that could not be saved still loads from the network when there is one.
            stream = remote.copy(url = DownloadFiles.uriOf(video), subtitleUrl = subtitleUri ?: remote.subtitleUrl),
            subtitleUri = subtitleUri,
        )
    }

    private fun videoOf(row: DownloadEntity): File? = File(row.videoPath).takeIf { it.isFile && it.length() > 0 }

    /** HEAD → Content-Length; servers that don't answer HEAD get `Range: bytes=0-0` → Content-Range total. */
    private fun probeSize(url: String): Long? {
        fun request(head: Boolean) = Request.Builder().url(url).apply {
            userAgent?.let { header("User-Agent", it) }
            if (head) head() else header("Range", "bytes=0-0")
        }.build()
        return try {
            val fromHead = probeFactory.newCall(request(head = true)).execute().use { r ->
                if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } else null
            }
            fromHead ?: probeFactory.newCall(request(head = false)).execute().use { r ->
                when (r.code) {
                    206 -> ContentRange.parse(r.header("Content-Range"))?.total
                    200 -> r.header("Content-Length")?.toLongOrNull()
                    else -> null
                }?.takeIf { it > 0 }
            }
        } catch (e: IOException) {
            DownloadLog.i("size probe failed: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            null // malformed URL
        }
    }

    /** Everything that needs a Context, built once. */
    private class AndroidParts(context: Context, okHttpClient: OkHttpClient, val dao: DownloadDao, val userAgent: String?) {
        val client: OkHttpClient = okHttpClient.newBuilder()
            .cache(null)
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        val files = DownloadFiles(File(context.filesDir, DIR_NAME).apply { mkdirs() })
        val network = NetworkMonitor(context)
        val engine = DownloadEngine(
            dao = dao,
            callFactory = client,
            files = files,
            freeBytes = ::freeBytes,
            isNetworkAvailable = network::isAvailable,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            listener = AndroidEngineListener(context),
            userAgent = userAgent,
        )

        fun freeBytes(): Long = try {
            files.root.mkdirs()
            StatFs(files.root.path).availableBytes
        } catch (e: IllegalArgumentException) {
            files.root.usableSpace
        }
    }

    private companion object {
        const val DIR_NAME = "downloads"
        const val CONNECT_TIMEOUT_S = 10L
        const val READ_TIMEOUT_S = 30L
    }
}
