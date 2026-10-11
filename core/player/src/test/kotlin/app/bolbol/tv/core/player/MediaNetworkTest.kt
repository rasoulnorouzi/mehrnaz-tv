package app.bolbol.tv.core.player

import app.bolbol.tv.core.model.AudioType.DUBBED
import app.bolbol.tv.core.model.AudioType.SUBTITLED
import app.bolbol.tv.core.model.StreamOption
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class MediaNetworkTest {

    // ---- MediaHttp ------------------------------------------------------------------------

    @Test fun mediaClientDropsTheCacheShortensTimeoutsAndSharesThePool() {
        val dir: File = Files.createTempDirectory("http").toFile()
        val shared = OkHttpClient.Builder()
            .cache(Cache(dir, 1024 * 1024))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val media = MediaHttp.mediaClient(shared)
        assertNotNull(shared.cache)
        assertNull(media.cache)
        assertEquals(6_000, media.connectTimeoutMillis)
        assertEquals(10_000, media.readTimeoutMillis)
        assertSame(shared.connectionPool, media.connectionPool)
        assertSame(shared.dispatcher, media.dispatcher)
        dir.deleteRecursively()
    }

    @Test fun nonOkHttpFactoriesPassThrough() {
        val factory = okhttp3.Call.Factory { throw UnsupportedOperationException() }
        assertSame(factory, MediaHttp.mediaCallFactory(factory))
    }

    // ---- MediaRetry -----------------------------------------------------------------------

    @Test fun clientErrorsAreFatalExceptTimeoutAndRateLimit() {
        assertTrue(MediaRetry.isRetryable(null)) // timeouts, resets, DNS
        assertTrue(MediaRetry.isRetryable(500))
        assertTrue(MediaRetry.isRetryable(503))
        assertTrue(MediaRetry.isRetryable(408))
        assertTrue(MediaRetry.isRetryable(429))
        assertFalse(MediaRetry.isRetryable(404))
        assertFalse(MediaRetry.isRetryable(403))
        assertFalse(MediaRetry.isRetryable(410))
    }

    @Test fun retriesComeQuicklyAndCapAtTwoSeconds() {
        assertEquals(listOf(200L, 500L, 1_000L, 2_000L, 2_000L), (1..5).map(MediaRetry::delayMs))
    }

    // ---- PrewarmPlan ----------------------------------------------------------------------

    private fun s(q: Int, url: String, a: app.bolbol.tv.core.model.AudioType = SUBTITLED) = StreamOption(q, a, url, null, "$q", null)

    // The Naked Gun 2025 (postId 353371) as served by player-links on 2026-10-11.
    private val movie = listOf(
        s(1080, "https://dl20.acenteri.ir/zr/m/Movie.1080p.SoftSub.mp4"),
        s(720, "https://dl2.ecenteri.ir/zr/m/Movie.720p.SoftSub.mp4"),
        s(480, "https://dl20.acenteri.ir/zr/m/Movie.480p.SoftSub.mp4"),
        s(1080, "https://dl20.acenteri.ir/zr/m/Movie.1080p.Dubbed.mp4", DUBBED),
        s(720, "https://dl2.ecenteri.ir/zr/m/Movie.720p.Dubbed.mp4", DUBBED),
        s(480, "https://dl20.acenteri.ir/zr/m/Movie.480p.Dubbed.mp4", DUBBED),
    )

    @Test fun watching720WarmsOnlyThe1080Host() {
        assertEquals(
            listOf("https://dl20.acenteri.ir/zr/m/Movie.1080p.SoftSub.mp4"),
            PrewarmPlan.pick(movie, currentUrl = movie[1].url, lastWarmedAtMs = emptyMap(), nowMs = 0),
        )
    }

    @Test fun watching1080WarmsThe720Host() {
        assertEquals(
            listOf("https://dl2.ecenteri.ir/zr/m/Movie.720p.SoftSub.mp4"),
            PrewarmPlan.pick(movie, currentUrl = movie[0].url, lastWarmedAtMs = emptyMap(), nowMs = 0),
        )
    }

    @Test fun seriesOnOneHostNeedsNoWarmUp() {
        val series = listOf(
            s(1080, "https://dl1.hostikadeh.ir/s/S01/1080p/E01.mp4"),
            s(720, "https://dl1.hostikadeh.ir/s/S01/720p/E01.mp4"),
            s(480, "https://dl1.hostikadeh.ir/s/S01/480p/E01.mp4"),
        )
        assertEquals(emptyList<String>(), PrewarmPlan.pick(series, series[1].url, emptyMap(), nowMs = 0))
    }

    @Test fun oneUrlPerHostHighestQualityFirst() {
        val picked = PrewarmPlan.pick(movie, currentUrl = null, lastWarmedAtMs = emptyMap(), nowMs = 0)
        assertEquals(
            listOf("https://dl20.acenteri.ir/zr/m/Movie.1080p.SoftSub.mp4", "https://dl2.ecenteri.ir/zr/m/Movie.720p.SoftSub.mp4"),
            picked,
        )
    }

    @Test fun recentlyWarmedHostsAreSkippedForAMinute() {
        val warmed = mapOf("dl20.acenteri.ir" to 10_000L)
        assertEquals(emptyList<String>(), PrewarmPlan.pick(movie, movie[1].url, warmed, nowMs = 69_999))
        assertEquals(1, PrewarmPlan.pick(movie, movie[1].url, warmed, nowMs = 70_000).size)
    }

    @Test fun badUrlsAreIgnored() {
        val streams = listOf(s(1080, "not a url"), s(720, "https://DL2.ecenteri.ir/a.mp4"))
        assertEquals(listOf("https://DL2.ecenteri.ir/a.mp4"), PrewarmPlan.pick(streams, null, emptyMap(), 0))
        assertEquals("dl2.ecenteri.ir", PrewarmPlan.hostOf("https://DL2.ecenteri.ir/a.mp4"))
        assertNull(PrewarmPlan.hostOf("not a url"))
    }

    // ---- MediaTransferTracker -------------------------------------------------------------

    @Test fun trackerRecordsOnlyTheWatchedUrl() {
        var now = 100L
        val tracker = MediaTransferTracker { now }
        tracker.onBytes("a", 10) // before watch: ignored
        tracker.watch("b")
        assertEquals(-1, tracker.lastByteAtMs())
        now = 150; tracker.onRequest("b")
        now = 400; tracker.onResponse("b")
        now = 500; tracker.onBytes("b", 1_000)
        tracker.onBytes("a", 5) // the old stream winding down
        now = 900; tracker.onRequest("b")
        now = 1_100; tracker.onResponse("b")
        now = 1_200; tracker.onBytes("b", 2_000)
        assertEquals(TransferSnapshot(100, 2, 400, 1_100, 900, 1_200, 3_000), tracker.snapshot())
        assertEquals(1_200, tracker.lastByteAtMs())
        now = 2_000; tracker.watch("a")
        assertEquals(TransferSnapshot(watchedAtMs = 2_000), tracker.snapshot())
    }
}
