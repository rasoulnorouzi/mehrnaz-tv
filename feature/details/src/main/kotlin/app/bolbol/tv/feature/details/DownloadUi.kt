package app.bolbol.tv.feature.details

import androidx.compose.runtime.Immutable
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.DownloadFailure
import app.bolbol.tv.core.model.DownloadItem
import app.bolbol.tv.core.model.DownloadStatus
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.WatchKey

/**
 * What the «دانلود» button / the per-episode download icon shows. `null` where it is used means "offline saving
 * is not wired in": the button is hidden.
 */
@Immutable
sealed interface DownloadButtonState {
    /** Nothing saved: «دانلود». */
    data object Idle : DownloadButtonState

    /** Waiting for its turn (one download runs at a time): «در صف دانلود». */
    data object Queued : DownloadButtonState

    /** «در حال دانلود ۴۵٪»; [percent] is null while the size is unknown. */
    data class Downloading(val percent: Int?) : DownloadButtonState

    /** Stopped by the user or the system, can continue: «متوقف». */
    data class Paused(val percent: Int?) : DownloadButtonState

    /** «دانلود شده ✓». */
    data object Completed : DownloadButtonState

    /** Stopped with an error: tapping retries. */
    data class Failed(val failure: DownloadFailure?) : DownloadButtonState

    /** True when there is something on disk or in the queue that «حذف دانلود» would remove. */
    val removable: Boolean get() = this !is Idle
}

internal fun DownloadItem?.toButtonState(): DownloadButtonState = when (this?.status) {
    null -> DownloadButtonState.Idle
    DownloadStatus.QUEUED -> DownloadButtonState.Queued
    DownloadStatus.DOWNLOADING -> DownloadButtonState.Downloading(percent)
    DownloadStatus.PAUSED -> DownloadButtonState.Paused(percent)
    DownloadStatus.COMPLETED -> DownloadButtonState.Completed
    DownloadStatus.FAILED -> DownloadButtonState.Failed(failure)
}

/** Extra room the engine insists on besides the file itself (docs/plan.md §v1.4: «size + 300 MB margin»). */
internal const val DOWNLOAD_SAFETY_MARGIN_BYTES = 300L * 1024 * 1024

/**
 * Whether an option can be saved: the file plus the safety margin must fit in the free space. Unknown size or
 * unknown free space is not a reason to refuse here; the engine still checks and answers `NotEnoughSpace`.
 */
internal fun optionFits(sizeBytes: Long?, freeBytes: Long?): Boolean =
    sizeBytes == null || freeBytes == null || sizeBytes + DOWNLOAD_SAFETY_MARGIN_BYTES <= freeBytes

/** One row of the download dialog. */
@Immutable
data class DownloadOptionUi(
    val stream: StreamOption,
    /** From `sizeOf`; null until it answers or when the server doesn't say. */
    val sizeBytes: Long?,
    /** True until `sizeOf` has answered for this option. */
    val sizeLoading: Boolean,
    /** False when the file plus margin exceeds the free space (the row is disabled). */
    val fits: Boolean,
)

/** Why a start did not go through; shown inside the dialog. */
@Immutable
sealed interface DownloadMessage {
    data class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : DownloadMessage
    data class Failed(val failure: DownloadFailure) : DownloadMessage
}

/** The «دانلود» dialog: every quality × audio of one movie / episode. */
@Immutable
data class DownloadPickState(
    val key: WatchKey,
    /** «فصل ۱ · قسمت ۲» for an episode, null for a movie. */
    val subtitleLine: String?,
    val options: List<DownloadOptionUi>,
    /** The TV's free space; null until `storage()` answers. */
    val freeBytes: Long?,
    val message: DownloadMessage? = null,
    /** True while a start request is running (the rows ignore clicks). */
    val starting: Boolean = false,
)

/** A modal over the details page. */
@Immutable
sealed interface DetailsOverlay {
    data class Pick(val state: DownloadPickState) : DetailsOverlay

    /** «حذف دانلود؟» for [key]; [subtitleLine] names the episode (null for a movie). */
    data class ConfirmRemove(val key: WatchKey, val subtitleLine: String?) : DetailsOverlay
}

/** The dialog rows for [streams]: highest quality first, dubbed before subtitled, sizes still loading. */
internal fun initialOptions(streams: List<StreamOption>, freeBytes: Long?): List<DownloadOptionUi> =
    streams
        .sortedWith(compareByDescending<StreamOption> { it.quality }.thenBy { if (it.audio == AudioType.DUBBED) 0 else 1 })
        .map { DownloadOptionUi(it, sizeBytes = null, sizeLoading = true, fits = optionFits(null, freeBytes)) }

/** Applies a finished `sizeOf` (null = unknown) to [stream]'s row and recomputes whether it fits. */
internal fun DownloadPickState.withSize(stream: StreamOption, size: Long?): DownloadPickState =
    copy(
        options = options.map {
            if (it.stream == stream) {
                it.copy(sizeBytes = size, sizeLoading = false, fits = optionFits(size, freeBytes))
            } else {
                it
            }
        },
    )

/** Applies the free space once known: every row's `fits` is recomputed from the sizes already loaded. */
internal fun DownloadPickState.withFree(free: Long): DownloadPickState =
    copy(freeBytes = free, options = options.map { it.copy(fits = optionFits(it.sizeBytes, free)) })
