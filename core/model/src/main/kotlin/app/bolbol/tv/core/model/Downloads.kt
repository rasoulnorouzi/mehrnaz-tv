package app.bolbol.tv.core.model

import kotlinx.coroutines.flow.Flow

/** Lifecycle of one offline copy (v1.3 downloads). */
enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, FAILED, COMPLETED }

/** One downloaded (or downloading) movie or episode. Keyed like progress: `postId:season:episode`. */
data class DownloadItem(
    val key: WatchKey,
    /** Snapshot of the title, so the Downloads page and offline playback work without the network. */
    val title: TitleSummary,
    /** Secondary line, e.g. "فصل ۱ · قسمت ۲", null for movies. */
    val subtitleLine: String?,
    val quality: Int,
    val audio: AudioType,
    val state: DownloadState,
    val downloadedBytes: Long,
    /** Full size in bytes; -1 while unknown (before the first response). */
    val totalBytes: Long,
    /** Short Persian reason when [state] is FAILED, else null. */
    val failureReason: String?,
    val createdAtMs: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** Space numbers for the Downloads page. */
data class StorageInfo(
    /** Bytes taken by this app's downloads (finished and partial). */
    val usedByDownloadsBytes: Long,
    /** Free bytes on the volume the downloads live on. */
    val freeBytes: Long,
)

/** Thrown by [DownloadRepository.start] when the file would not fit (a safety margin is kept free). */
class NotEnoughSpaceException(val neededBytes: Long, val freeBytes: Long) :
    Exception("not enough space: need $neededBytes, free $freeBytes")

/**
 * Offline copies of movies and episodes (implemented in core:download). One download runs at a time
 * (2 GB TV, slow flash); the rest wait in QUEUED. Files live in app-specific storage and are removed by
 * [delete] / [deleteAll] or when the app is uninstalled.
 */
interface DownloadRepository {
    /** All downloads, newest first. */
    fun downloads(): Flow<List<DownloadItem>>

    /** One download, or null when there is none for [key]. */
    fun download(key: WatchKey): Flow<DownloadItem?>

    /** Downloads of one title (the movie, or every downloaded episode of a series). */
    fun downloadsForTitle(postId: Long): Flow<List<DownloadItem>>

    fun storage(): Flow<StorageInfo>

    /**
     * Queues [stream] of [key] for download (a no-op when that key is already queued, running or done).
     * @throws NotEnoughSpaceException when the known size does not fit.
     */
    suspend fun start(title: TitleSummary, key: WatchKey, subtitleLine: String?, stream: StreamOption)

    suspend fun pause(key: WatchKey)

    suspend fun resume(key: WatchKey)

    /** Cancels if running and deletes the files and the row. */
    suspend fun delete(key: WatchKey)

    suspend fun deleteAll()

    /**
     * The finished local copy as a playable stream (`file://` video URL, local `file://` VTT or null),
     * or null when [key] is not completely downloaded.
     */
    suspend fun offlineStream(key: WatchKey): StreamOption?
}
