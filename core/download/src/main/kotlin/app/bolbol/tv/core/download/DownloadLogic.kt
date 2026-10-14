package app.bolbol.tv.core.download

import app.bolbol.tv.core.database.DownloadEntity
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.DownloadFailure
import app.bolbol.tv.core.model.DownloadItem
import app.bolbol.tv.core.model.DownloadRequest
import app.bolbol.tv.core.model.DownloadStatus
import app.bolbol.tv.core.model.StartDownloadResult
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.model.WatchKey
import java.io.File

// Pure logic of the download engine (unit-tested): free-space rule, HTTP range handling, retry policy, file layout and
// the DownloadEntity ↔ core:model mapping.

/** Free-space rule of docs/plan.md §v1.4: refuse when file + 300 MB margin doesn't fit. */
object SpaceCheck {
    /** Safety margin left free on the 16 GB TV (the system and other apps need room too). */
    const val MARGIN_BYTES: Long = 300L * 1024 * 1024

    /** While writing: stop with NO_SPACE when free space falls under this (the user may fill the disk meanwhile). */
    const val RUNNING_FLOOR_BYTES: Long = 64L * 1024 * 1024

    /**
     * @param fileBytes size of the new file, null when the server didn't say (then only the margin is checked).
     * @param freeBytes free space where downloads are stored.
     * @param reservedBytes bytes still to come for downloads already queued or running (they share the disk).
     * @return null when it fits, else the NotEnoughSpace to return from `start()`. Its `freeBytes` is what is left
     *   for the new file after the other downloads.
     */
    fun check(fileBytes: Long?, freeBytes: Long, reservedBytes: Long = 0): StartDownloadResult.NotEnoughSpace? {
        val needed = (fileBytes ?: 0L).coerceAtLeast(0) + MARGIN_BYTES
        val available = (freeBytes - reservedBytes.coerceAtLeast(0)).coerceAtLeast(0)
        return if (needed > available) StartDownloadResult.NotEnoughSpace(needed, available) else null
    }

    /** Bytes still to come for [items] (queued, running or paused with a known total). */
    fun reservedBytes(items: List<DownloadEntity>): Long = items
        .filter { it.status in ACTIVE_STATUSES }
        .sumOf { e -> e.totalBytes?.let { (it - e.bytesDownloaded).coerceAtLeast(0) } ?: 0L }

    private val ACTIVE_STATUSES = setOf(
        DownloadStatus.QUEUED.name,
        DownloadStatus.DOWNLOADING.name,
        DownloadStatus.PAUSED.name,
    )
}

// `Content-Range: bytes 100-199/1000` (the total may be a star), or `bytes */1000` on a 416.
internal data class ContentRange(val start: Long?, val end: Long?, val total: Long?) {
    companion object {
        private val FULL = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
        private val UNSATISFIED = Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)

        fun parse(header: String?): ContentRange? {
            val value = header?.trim() ?: return null
            FULL.matchEntire(value)?.let { m ->
                val (s, e, t) = m.destructured
                return ContentRange(s.toLongOrNull(), e.toLongOrNull(), t.toLongOrNull())
            }
            UNSATISFIED.matchEntire(value)?.let { m -> return ContentRange(null, null, m.groupValues[1].toLongOrNull()) }
            return null
        }
    }
}

/** What to do with the video response, decided from status + headers only (no I/O). */
internal sealed interface RangePlan {
    /** Append the body to the partial file, which is [have] bytes long; [total] is the full size if known. */
    data class Append(val total: Long?) : RangePlan

    /** The server sent the whole file (no range support, or If-Range said the file changed): truncate, then write. */
    data class Restart(val total: Long?) : RangePlan

    /** 416 and the partial file already holds all [total] bytes. */
    data object AlreadyComplete : RangePlan

    /** The partial file can't be trusted (bad range, 416 with another size): delete it and try again later. */
    data object Discard : RangePlan

    /** 4xx: the file is gone / forbidden (stream URLs rotate). */
    data class Gone(val code: Int) : RangePlan

    /** 5xx, 408, 429 or anything unexpected: try again later. */
    data class Transient(val code: Int) : RangePlan

    companion object {
        /**
         * @param have bytes already in the partial file (a `Range: bytes=have-` request was sent when > 0).
         * @param contentLength the body length (-1 when unknown).
         */
        fun decide(code: Int, have: Long, contentRange: String?, contentLength: Long): RangePlan {
            val length = contentLength.takeIf { it >= 0 }
            return when {
                code == 206 -> {
                    val range = ContentRange.parse(contentRange)
                    if (range?.start != have) Discard else Append(range.total ?: length?.let { have + it })
                }
                code == 200 -> Restart(length)
                code == 416 -> {
                    val total = ContentRange.parse(contentRange)?.total
                    if (have > 0 && total == have) AlreadyComplete else Discard
                }
                code == 408 || code == 429 -> Transient(code)
                code in 400..499 -> Gone(code)
                else -> Transient(code)
            }
        }
    }
}

/** Retry rules for network trouble (one download at a time; don't hammer the CDN: CLAUDE.md hard rule 6). */
object DownloadRetry {
    /** Failed attempts in a row (with the network up and no byte saved) before the item becomes FAILED/NETWORK. */
    const val MAX_ATTEMPTS = 8

    /** Wait before attempt n+1 after [attempt] failures: 5 s, 15 s, 30 s, 60 s, then 2 min. */
    fun delayMs(attempt: Int): Long = when {
        attempt <= 1 -> 5_000L
        attempt == 2 -> 15_000L
        attempt == 3 -> 30_000L
        attempt == 4 -> 60_000L
        else -> 120_000L
    }
}

/** Where one item's files live: `<root>/<postId>_<season>_<episode>/`. */
internal class DownloadFiles(val root: File) {
    fun dirOf(key: WatchKey): File = File(root, dirName(key))

    fun videoFile(key: WatchKey, url: String): File = File(dirOf(key), "video." + videoExtension(url))

    fun partFile(video: File): File = File(video.path + PART_SUFFIX)

    fun subtitleFile(key: WatchKey): File = File(dirOf(key), "subtitle.vtt")

    fun posterFile(key: WatchKey): File = File(dirOf(key), "poster.jpg")

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val PART_SUFFIX = ".part"

        fun dirName(key: WatchKey): String = "${key.postId}_${key.season}_${key.episode}"

        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts")

        /** The URL's extension when it is a video one (mp4, mkv …), else mp4. ExoPlayer sniffs the format anyway. */
        fun videoExtension(url: String): String {
            val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
            val ext = path.substringAfterLast('.', "").lowercase()
            return if (ext in VIDEO_EXTENSIONS) ext else "mp4"
        }

        /** `file:///abs/path`, playable by ExoPlayer's DefaultDataSource and loadable by Coil. */
        fun uriOf(file: File): String = "file://" + file.absolutePath
    }
}

// ---- mapping ---------------------------------------------------------------------------------------------------

internal fun parseStatus(name: String): DownloadStatus =
    DownloadStatus.entries.firstOrNull { it.name == name } ?: DownloadStatus.FAILED

internal fun parseFailure(name: String?): DownloadFailure? =
    name?.let { n -> DownloadFailure.entries.firstOrNull { it.name == n } ?: DownloadFailure.UNKNOWN }

internal fun DownloadEntity.watchKey(): WatchKey = WatchKey(postId, season, episode)

/** The remote stream this item is saved from. */
internal fun DownloadEntity.remoteStream(): StreamOption = StreamOption(
    quality = quality,
    audio = AudioType.entries.firstOrNull { it.name == audio } ?: AudioType.SUBTITLED,
    url = url,
    subtitleUrl = subtitleUrl,
    label = label,
    sizeText = sizeText,
)

internal fun DownloadEntity.toItem(): DownloadItem {
    val status = parseStatus(status)
    return DownloadItem(
        key = watchKey(),
        title = TitleSummary(
            id = postId,
            type = type?.let { t -> TitleType.entries.firstOrNull { it.name == t } },
            title = title,
            faTitle = faTitle,
            // The saved copy once it exists, so the «دانلودها» screen paints with no internet.
            posterUrl = posterPath?.let { DownloadFiles.uriOf(File(it)) } ?: posterUrl,
            backdropUrl = backdropUrl,
            year = year,
            imdbRating = imdbRating,
            hasDub = hasDub,
            hasSubtitle = hasSubtitle,
        ),
        subtitleLine = subtitleLine,
        stream = remoteStream(),
        status = status,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes ?: if (status == DownloadStatus.COMPLETED) bytesDownloaded else null,
        failure = if (status == DownloadStatus.FAILED || status == DownloadStatus.PAUSED) parseFailure(failure) else null,
        createdAtMs = createdAtMs,
    )
}

internal fun DownloadRequest.toEntity(videoPath: String, createdAtMs: Long, totalBytes: Long?): DownloadEntity =
    DownloadEntity(
        key = key.asString(),
        postId = key.postId,
        season = key.season,
        episode = key.episode,
        type = title.type?.name,
        title = title.title,
        faTitle = title.faTitle,
        posterUrl = title.posterUrl,
        backdropUrl = title.backdropUrl,
        year = title.year,
        imdbRating = title.imdbRating,
        hasDub = title.hasDub,
        hasSubtitle = title.hasSubtitle,
        subtitleLine = subtitleLine,
        quality = stream.quality,
        audio = stream.audio.name,
        url = stream.url,
        subtitleUrl = stream.subtitleUrl?.takeIf { it.isNotBlank() },
        label = stream.label,
        sizeText = stream.sizeText,
        status = DownloadStatus.QUEUED.name,
        bytesDownloaded = 0,
        totalBytes = totalBytes,
        failure = null,
        createdAtMs = createdAtMs,
        videoPath = videoPath,
        subtitlePath = null,
        posterPath = null,
        validator = null,
    )

/** Persian digits for the notification text. */
internal fun Int.toPersianDigits(): String = toString().map { c -> if (c in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[c - '0'] else c }.joinToString("")
