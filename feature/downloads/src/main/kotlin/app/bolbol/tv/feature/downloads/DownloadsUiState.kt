package app.bolbol.tv.feature.downloads

import androidx.compose.runtime.Immutable
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.DownloadFailure
import app.bolbol.tv.core.model.DownloadItem
import app.bolbol.tv.core.model.DownloadStatus
import app.bolbol.tv.core.model.StorageInfo
import app.bolbol.tv.core.model.WatchKey

/** What a row says about its download; the screen maps each to a Persian label. */
enum class RowStatus { QUEUED, DOWNLOADING, PAUSED, FAILED, COMPLETED }

/** One row of the «دانلودها» list. Everything the row paints is here; no repository calls while drawing. */
@Immutable
data class DownloadRowUi(
    val key: WatchKey,
    /** Persian title, else the original one. */
    val titleText: String,
    /** «فصل ۱ · قسمت ۲» for an episode, null for a movie. */
    val subtitleLine: String?,
    val quality: Int,
    val audio: AudioType,
    /** A local file URI once saved (works with no internet); may be a remote URL while the poster is not saved yet. */
    val posterUrl: String?,
    val status: RowStatus,
    /** 0..100, null while the size is unknown. */
    val percent: Int?,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val failure: DownloadFailure?,
    /** The item itself, handed to `onPlay`. */
    val item: DownloadItem,
) {
    /** Only a finished download can be played. */
    val canPlay: Boolean get() = status == RowStatus.COMPLETED

    /** A failed download can be retried. */
    val canRetry: Boolean get() = status == RowStatus.FAILED

    /** 0..1 for the progress bar: full when completed, empty while the size is unknown. */
    val progressFraction: Float
        get() = when {
            status == RowStatus.COMPLETED -> 1f
            percent != null -> percent / 100f
            else -> 0f
        }

    /** The bar is shown while the download is not finished. */
    val showProgress: Boolean get() = status != RowStatus.COMPLETED
}

internal fun DownloadStatus.toRowStatus(): RowStatus = when (this) {
    DownloadStatus.QUEUED -> RowStatus.QUEUED
    DownloadStatus.DOWNLOADING -> RowStatus.DOWNLOADING
    DownloadStatus.PAUSED -> RowStatus.PAUSED
    DownloadStatus.COMPLETED -> RowStatus.COMPLETED
    DownloadStatus.FAILED -> RowStatus.FAILED
}

internal fun DownloadItem.toRow(): DownloadRowUi = DownloadRowUi(
    key = key,
    titleText = title.faTitle.ifBlank { title.title },
    subtitleLine = subtitleLine,
    quality = stream.quality,
    audio = stream.audio,
    posterUrl = title.posterUrl,
    status = status.toRowStatus(),
    percent = percent,
    bytesDownloaded = bytesDownloaded,
    totalBytes = totalBytes,
    failure = failure,
    item = this,
)

/** What the used / free line shows; null storage = unknown (the line is hidden). */
@Immutable
data class StorageSummary(val usedByDownloadsBytes: Long, val freeBytes: Long)

internal fun StorageInfo.toSummary() = StorageSummary(usedByDownloadsBytes, freeBytes)

@Immutable
sealed interface DownloadsUiState {
    data object Loading : DownloadsUiState

    /** [rows] empty = the «هنوز چیزی دانلود نکرده‌اید» empty state. */
    data class Content(val rows: List<DownloadRowUi>, val storage: StorageSummary?) : DownloadsUiState
}

/** A confirmation waiting for the user's answer. Nothing is deleted before it is confirmed. */
@Immutable
sealed interface DownloadsConfirm {
    data class RemoveOne(val key: WatchKey, val titleText: String, val subtitleLine: String?) : DownloadsConfirm
    data class RemoveAll(val count: Int) : DownloadsConfirm
}
