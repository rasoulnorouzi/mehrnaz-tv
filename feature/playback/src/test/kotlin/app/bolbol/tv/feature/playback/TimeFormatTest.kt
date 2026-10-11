package app.bolbol.tv.feature.playback

import app.bolbol.tv.core.designsystem.toPersianDigits
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test fun minutesAndSeconds() {
        assertEquals("00:00", formatPlaybackTime(0))
        assertEquals("00:09", formatPlaybackTime(9_999))
        assertEquals("47:12", formatPlaybackTime(2_832_000))
    }

    @Test fun hoursWhenLongOrForced() {
        assertEquals("1:02:03", formatPlaybackTime(3_723_000))
        assertEquals("0:47:12", formatPlaybackTime(2_832_000, withHours = true))
    }

    @Test fun negativeIsZero() {
        assertEquals("00:00", formatPlaybackTime(-5))
    }

    @Test fun persianDigits() {
        assertEquals("۴۷:۱۲", formatPlaybackTime(2_832_000).toPersianDigits())
    }

    @Test fun subtitleOffsetSeconds() {
        val lri = '\u2066'
        val pdi = '\u2069'
        assertEquals("۰٫۰", formatSubtitleOffset(0))
        assertEquals("$lri+۱٫۵$pdi", formatSubtitleOffset(1_500))
        assertEquals("$lri-۰٫۵$pdi", formatSubtitleOffset(-500))
        assertEquals("$lri+۲٫۰$pdi", formatSubtitleOffset(2_000))
        assertEquals("$lri+۱۲٫۵$pdi", formatSubtitleOffset(12_500)) // the user's example
        assertEquals("$lri-۵۰٫۰$pdi", formatSubtitleOffset(-50_000))
        assertEquals("$lri+۱۲۰٫۰$pdi", formatSubtitleOffset(120_000))
    }

    @Test fun fourSubtitleSizesInOrder() {
        assertEquals(listOf(0.8f, 1.0f, 1.25f, 1.5f), SubtitleSizes.map { it.second })
        assertEquals(R.string.player_subtitle_xlarge, SubtitleSizes.last().first)
    }
}
