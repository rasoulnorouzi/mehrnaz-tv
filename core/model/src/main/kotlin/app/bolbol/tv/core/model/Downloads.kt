package app.bolbol.tv.core.model

import kotlinx.coroutines.flow.Flow

// v1.4 offline saving (docs/plan.md §v1.4). Implemented in core:download; UI in feature:downloads
// and feature:details; the player plays a completed download instead of the network stream.

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

/** Why a download stopped; the UI shows a Persian message for each. */
enum class DownloadFailure { NO_SPACE, NETWORK, SOURCE_GONE, UNKNOWN }

/** One saved (or saving) movie or episode. */
data class DownloadItem(
    val key: WatchKey,
    /** Snapshot for painting with no internet (poster is a local file URI once saved). */
    val title: TitleSummary,
    /** Secondary line, e.g. "فصل ۱ · قسمت ۲", null for movies. */
    val subtitleLine: String?,
    /** The chosen quality/audio; [StreamOption.url] is the remote URL it was saved from. */
    val stream: StreamOption,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    /** null while the server hasn't told us the size yet. */
    val totalBytes: Long?,
    val failure: DownloadFailure? = null,
    val createdAtMs: Long,
) {
    /** 0..100, or null when the total size is unknown. */
    val percent: Int?
        get() = totalBytes?.takeIf { it > 0 }?.let { ((bytesDownloaded * 100) / it).toInt().coerceIn(0, 100) }
}

/** Free space where downloads are stored, and how much the downloads already use. */
data class StorageInfo(val freeBytes: Long, val usedByDownloadsBytes: Long)

/** What the user picked in the «دانلود» dialog. */
data class DownloadRequest(
    val key: WatchKey,
    val title: TitleSummary,
    val subtitleLine: String?,
    val stream: StreamOption,
)

sealed interface StartDownloadResult {
    data object Started : StartDownloadResult
    data object AlreadySaved : StartDownloadResult
    /** Refused: [neededBytes] (file + safety margin) is more than [freeBytes]. */
    data class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : StartDownloadResult
    data class Failed(val failure: DownloadFailure) : StartDownloadResult
}

/** A completed download, ready to play without internet. */
data class OfflineMedia(
    val key: WatchKey,
    /** Same quality/audio/label as when saved; [StreamOption.url] is the playable local URI or cache key. */
    val stream: StreamOption,
    /** Local URI of the saved WebVTT subtitle, null for dubbed streams or when none was available. */
    val subtitleUri: String?,
)

interface DownloadRepository {
    /** Every download, newest first. */
    fun downloads(): Flow<List<DownloadItem>>

    /** Downloads of one title (movie: at most one; series: one per saved episode). */
    fun downloadsForTitle(postId: Long): Flow<List<DownloadItem>>

    suspend fun storage(): StorageInfo

    /** File size of [stream] in bytes (one HEAD request, cached), null when the server doesn't say. */
    suspend fun sizeOf(stream: StreamOption): Long?

    suspend fun start(request: DownloadRequest): StartDownloadResult

    suspend fun retry(key: WatchKey)

    /** Cancels (if running) and deletes the files and the entry. */
    suspend fun remove(key: WatchKey)

    suspend fun removeAll()

    /** Non-null only for a COMPLETED download whose files are present. */
    suspend fun offlineMedia(key: WatchKey): OfflineMedia?
}
