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
}
