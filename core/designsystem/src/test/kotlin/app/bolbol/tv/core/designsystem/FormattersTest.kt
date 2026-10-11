package app.bolbol.tv.core.designsystem

import org.junit.Assert.assertEquals
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
}
