package app.bolbol.tv.core.download

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.DownloadFailure
import app.bolbol.tv.core.model.DownloadRequest
import app.bolbol.tv.core.model.DownloadStatus
import app.bolbol.tv.core.model.StartDownloadResult
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.model.WatchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class DownloadLogicTest {
    private val mb = 1024L * 1024

    // ---- SpaceCheck -----------------------------------------------------------------------------------------------

    @Test fun fitsWhenFilePlusMarginIsFree() {
        assertNull(SpaceCheck.check(fileBytes = 1_000 * mb, freeBytes = 1_300 * mb))
    }

    @Test fun refusesWhenTheMarginDoesNotFit() {
        val r = SpaceCheck.check(fileBytes = 1_000 * mb, freeBytes = 1_299 * mb)
        assertEquals(StartDownloadResult.NotEnoughSpace(neededBytes = 1_300 * mb, freeBytes = 1_299 * mb), r)
    }

    @Test fun unknownSizeStillNeedsTheMargin() {
        assertNull(SpaceCheck.check(fileBytes = null, freeBytes = 300 * mb))
        assertEquals(StartDownloadResult.NotEnoughSpace(300 * mb, 299 * mb), SpaceCheck.check(null, 299 * mb))
    }

    @Test fun otherQueuedDownloadsReserveTheirRemainingBytes() {
        // 3 GB free, another download still needs 2 GB: a 1 GB file no longer fits with the margin.
        val r = SpaceCheck.check(fileBytes = 1_000 * mb, freeBytes = 3_000 * mb, reservedBytes = 2_000 * mb)
        assertEquals(StartDownloadResult.NotEnoughSpace(1_300 * mb, 1_000 * mb), r)
    }

    @Test fun reservedCountsOnlyUnfinishedItemsWithAKnownTotal() {
        val rows = listOf(
            entity("1:0:0", DownloadStatus.DOWNLOADING, bytes = 100, total = 1_000),
            entity("2:0:0", DownloadStatus.QUEUED, bytes = 0, total = 500),
            entity("3:0:0", DownloadStatus.PAUSED, bytes = 50, total = null),
            entity("4:0:0", DownloadStatus.COMPLETED, bytes = 700, total = 700),
            entity("5:0:0", DownloadStatus.FAILED, bytes = 10, total = 900),
        )
        assertEquals(900L + 500L, SpaceCheck.reservedBytes(rows))
    }

    // ---- HTTP ranges ----------------------------------------------------------------------------------------------

    @Test fun parsesContentRange() {
        assertEquals(ContentRange(100, 199, 1000), ContentRange.parse("bytes 100-199/1000"))
        assertEquals(ContentRange(0, 0, null), ContentRange.parse("bytes 0-0/*"))
        assertEquals(ContentRange(null, null, 1000), ContentRange.parse("bytes */1000"))
        assertNull(ContentRange.parse(null))
        assertNull(ContentRange.parse("items 1-2/3"))
    }

    @Test fun partialContentAtOurOffsetAppends() {
        assertEquals(RangePlan.Append(1000), RangePlan.decide(206, have = 400, contentRange = "bytes 400-999/1000", contentLength = 600))
        // No total in the header: offset + body length.
        assertEquals(RangePlan.Append(1000), RangePlan.decide(206, have = 400, contentRange = "bytes 400-999/*", contentLength = 600))
    }

    @Test fun partialContentAtAnotherOffsetIsDiscarded() {
        assertEquals(RangePlan.Discard, RangePlan.decide(206, have = 400, contentRange = "bytes 0-999/1000", contentLength = 1000))
        assertEquals(RangePlan.Discard, RangePlan.decide(206, have = 400, contentRange = null, contentLength = 600))
    }

    @Test fun fullResponseRestarts() {
        // Range ignored, or If-Range saw a changed file.
        assertEquals(RangePlan.Restart(1000), RangePlan.decide(200, have = 400, contentRange = null, contentLength = 1000))
        assertEquals(RangePlan.Restart(null), RangePlan.decide(200, have = 0, contentRange = null, contentLength = -1))
    }

    @Test fun rangeNotSatisfiableMeansDoneOnlyWhenSizesMatch() {
        assertEquals(RangePlan.AlreadyComplete, RangePlan.decide(416, have = 1000, contentRange = "bytes */1000", contentLength = 0))
        assertEquals(RangePlan.Discard, RangePlan.decide(416, have = 1200, contentRange = "bytes */1000", contentLength = 0))
        assertEquals(RangePlan.Discard, RangePlan.decide(416, have = 1000, contentRange = null, contentLength = 0))
    }

    @Test fun clientErrorsAreGoneExceptTimeoutAndRateLimit() {
        assertEquals(RangePlan.Gone(404), RangePlan.decide(404, 0, null, 0))
        assertEquals(RangePlan.Gone(403), RangePlan.decide(403, 0, null, 0))
        assertEquals(RangePlan.Transient(408), RangePlan.decide(408, 0, null, 0))
        assertEquals(RangePlan.Transient(429), RangePlan.decide(429, 0, null, 0))
        assertEquals(RangePlan.Transient(503), RangePlan.decide(503, 0, null, 0))
    }

    @Test fun retryDelaysGrowAndCap() {
        assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 120_000L), (1..6).map(DownloadRetry::delayMs))
    }

    // ---- files ----------------------------------------------------------------------------------------------------

    @Test fun fileLayoutPerKey() {
        val files = DownloadFiles(File("/d"))
        val key = WatchKey(9001, 1, 2)
        assertEquals(File("/d/9001_1_2"), files.dirOf(key))
        assertEquals(File("/d/9001_1_2/video.mp4"), files.videoFile(key, "https://cdn/a/Show.S01E02.720p.mp4?token=1"))
        assertEquals(File("/d/9001_1_2/video.mkv"), files.videoFile(key, "https://cdn/a/b.MKV"))
        assertEquals(File("/d/9001_1_2/video.mp4.part"), files.partFile(files.videoFile(key, "https://cdn/x")))
        assertEquals("mp4", DownloadFiles.videoExtension("https://cdn/dl.php?id=3"))
        assertEquals("file:///d/9001_1_2/subtitle.vtt", DownloadFiles.uriOf(files.subtitleFile(key)))
    }

    // ---- mapping --------------------------------------------------------------------------------------------------

    @Test fun requestRoundTripsThroughTheEntity() {
        val request = DownloadRequest(WatchKey(9001, 1, 2), title, "فصل ۱ · قسمت ۲", stream)
        val e = request.toEntity(videoPath = "/d/9001_1_2/video.mp4", createdAtMs = 42, totalBytes = 1234)
        assertEquals("9001:1:2", e.key)
        assertEquals(DownloadStatus.QUEUED.name, e.status)
        val item = e.copy(bytesDownloaded = 617).toItem()
        assertEquals(request.key, item.key)
        assertEquals(title, item.title)
        assertEquals(request.subtitleLine, item.subtitleLine)
        assertEquals(stream, item.stream)
        assertEquals(DownloadStatus.QUEUED, item.status)
        assertEquals(50, item.percent)
        assertEquals(42L, item.createdAtMs)
    }

    @Test fun savedPosterReplacesTheRemoteOne() {
        val e = entity("1:0:0", DownloadStatus.COMPLETED, 10, 10).copy(posterUrl = "https://img/p.jpg", posterPath = "/d/1_0_0/poster.jpg")
        assertEquals("file:///d/1_0_0/poster.jpg", e.toItem().title.posterUrl)
        assertEquals("https://img/p.jpg", e.copy(posterPath = null).toItem().title.posterUrl)
    }

    @Test fun statusAndFailureNamesAreTolerant() {
        assertEquals(DownloadStatus.PAUSED, parseStatus("PAUSED"))
        assertEquals(DownloadStatus.FAILED, parseStatus("SOMETHING_NEW"))
        assertEquals(DownloadFailure.NO_SPACE, parseFailure("NO_SPACE"))
        assertEquals(DownloadFailure.UNKNOWN, parseFailure("WHAT"))
        assertNull(parseFailure(null))
        // A failure is only reported for FAILED/PAUSED items.
        val queued = entity("1:0:0", DownloadStatus.QUEUED, 0, null).copy(failure = "NETWORK")
        assertNull(queued.toItem().failure)
        assertEquals(DownloadFailure.NETWORK, queued.copy(status = "PAUSED").toItem().failure)
    }

    @Test fun completedWithoutKnownTotalReportsItsSize() {
        val item = entity("1:0:0", DownloadStatus.COMPLETED, bytes = 777, total = null).toItem()
        assertEquals(777L, item.totalBytes)
        assertEquals(100, item.percent)
    }

    @Test fun persianDigits() {
        assertEquals("۴۲", 42.toPersianDigits())
    }

    companion object {
        val title = TitleSummary(
            id = 9001, type = TitleType.SERIES, title = "Lego One Piece", faTitle = "وان پیس", posterUrl = "https://img/p.jpg",
            backdropUrl = null, year = 2025, imdbRating = 7.5f, hasDub = true, hasSubtitle = true,
        )
        val stream = StreamOption(720, AudioType.SUBTITLED, "https://cdn/x.mp4", "https://cdn/x.vtt", "WEB-DL 720p", "1.2 GB")

        fun entity(key: String, status: DownloadStatus, bytes: Long, total: Long?) =
            DownloadRequest(WatchKey.parse(key)!!, title, null, stream)
                .toEntity("/d/$key/video.mp4", 1, total)
                .copy(status = status.name, bytesDownloaded = bytes)
    }
}
