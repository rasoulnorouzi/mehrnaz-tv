package app.bolbol.tv.core.designsystem

import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.WatchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FormattersTest {
    @Test
    fun durationWithHours() {
        assertEquals("۱:۲۳:۴۵", formatDurationFa((1 * 3600 + 23 * 60 + 45) * 1000L))
    }

    @Test
    fun durationWithoutHours() {
        assertEquals("۴۷:۱۲", formatDurationFa((47 * 60 + 12) * 1000L))
        assertEquals("۵:۰۳", formatDurationFa((5 * 60 + 3) * 1000L))
        assertEquals("۰:۰۰", formatDurationFa(0))
    }

    @Test
    fun durationClampsNegative() {
        assertEquals("۰:۰۰", formatDurationFa(-5_000))
    }

    @Test
    fun remainingRoundsUpToMinutes() {
        assertEquals("۲۴ دقیقه مانده", formatRemainingFa(23 * 60_000L + 10_000L))
        assertEquals("۲۴ دقیقه مانده", formatRemainingFa(24 * 60_000L))
        assertEquals("۱ دقیقه مانده", formatRemainingFa(5_000L))
    }

    @Test
    fun remainingWithHours() {
        assertEquals("۱ ساعت و ۵ دقیقه مانده", formatRemainingFa(65 * 60_000L))
        assertEquals("۲ ساعت مانده", formatRemainingFa(120 * 60_000L))
    }

    @Test
    fun remainingWhenFinished() {
        assertEquals("تمام شد", formatRemainingFa(0))
        assertEquals("تمام شد", formatRemainingFa(-1))
    }

    @Test
    fun runtime() {
        assertEquals("۴۵ دقیقه", formatRuntimeFa(45))
        assertEquals("۱ ساعت و ۴۲ دقیقه", formatRuntimeFa(102))
        assertEquals("۲ ساعت", formatRuntimeFa(120))
    }

    @Test
    fun episode() {
        assertEquals("فصل ۱ · قسمت ۳", formatEpisodeFa(1, 3))
        assertEquals("قسمت ۱۲", formatEpisodeFa(2, 12, includeSeason = false))
    }

    @Test
    fun imdbKeepsLatinDigits() {
        assertEquals("8.1", formatImdb(8.1f))
        assertEquals("7.0", formatImdb(7f))
        assertEquals("9.3", formatImdb(9.25f + 0.05f))
    }

    @Test
    fun persianDigits() {
        assertEquals("۲۰۲۵", "2025".toPersianDigits())
        assertEquals("قسمت ۳", "قسمت 3".toPersianDigits())
        assertEquals("۱۲", 12.toPersianDigits())
    }

    // ---- progress labels (QA-03: unknown length must never read «تمام شد») ----

    private fun progress(key: WatchKey, positionMs: Long, durationMs: Long, completed: Boolean = false) =
        PlaybackProgress(key, positionMs, durationMs, updatedAtMs = 1, completed = completed)

    @Test
    fun resumeFromShowsThePositionWithPersianDigits() {
        assertEquals("ادامه از ۱۲:۳۴", formatResumeFromFa((12 * 60 + 34) * 1000L))
        assertEquals("ادامه از ۱:۰۲:۰۳", formatResumeFromFa((3600 + 2 * 60 + 3) * 1000L))
        assertEquals("ادامه", formatResumeFromFa(0))
    }

    @Test
    fun unknownLengthShowsThePositionNotFinished() {
        val position = (12 * 60 + 34) * 1000L
        assertEquals("ادامه از ۱۲:۳۴", formatProgressLabelFa(position, durationMs = 0))
        assertEquals("ادامه از ۱۲:۳۴", formatProgressLabelFa(position, durationMs = -1))
    }

    @Test
    fun knownLengthShowsTheTimeLeft() {
        assertEquals("۲۴ دقیقه مانده", formatProgressLabelFa(36 * 60_000L, 60 * 60_000L))
    }

    @Test
    fun finishedIsShownOnlyForCompletedTitles() {
        assertEquals("تمام شد", formatProgressLabelFa(60 * 60_000L, 60 * 60_000L, completed = true))
        assertEquals("تمام شد", formatProgressLabelFa(1_000L, durationMs = 0, completed = true))
        // Unfinished but at (or past) the saved end: still not «تمام شد».
        assertEquals("۱ دقیقه مانده", formatProgressLabelFa(60 * 60_000L, 60 * 60_000L))
        assertEquals("۱ دقیقه مانده", formatProgressLabelFa(61 * 60_000L, 60 * 60_000L))
        assertNotEquals("تمام شد", formatProgressLabelFa(0, 0))
    }

    @Test
    fun continueSubtitleForEpisodesAndMovies() {
        val position = (12 * 60 + 34) * 1000L
        assertEquals("قسمت ۲ · ادامه از ۱۲:۳۴", formatContinueSubtitleFa(progress(WatchKey(1, 1, 2), position, 0)))
        assertEquals("فصل ۲ · قسمت ۱ · ادامه از ۱۲:۳۴", formatContinueSubtitleFa(progress(WatchKey(1, 2, 1), position, 0)))
        assertEquals("ادامه از ۱۲:۳۴", formatContinueSubtitleFa(progress(WatchKey(1), position, 0)))
        assertEquals("قسمت ۱ · ۲۹ دقیقه مانده", formatContinueSubtitleFa(progress(WatchKey(1, 1, 1), 1 * 60_000L, 30 * 60_000L)))
    }
}
