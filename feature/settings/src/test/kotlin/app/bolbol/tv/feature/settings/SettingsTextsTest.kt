package app.bolbol.tv.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA-07: a description that opens with a Latin run flips the whole paragraph (full stop and comma on the wrong ends). */
class SettingsTextsTest {

    @Test
    fun theOldFpsSentenceStartedLeftToRight() {
        assertFalse(startsWithRtl("FPS، مصرف حافظه و آمار پخش‌کننده را روی صفحه نشان می‌دهد."))
    }

    @Test
    fun thePerformanceDescriptionStartsWithAPersianWord() {
        assertTrue(startsWithRtl(PERFORMANCE_DESCRIPTION))
        assertEquals('ن', PERFORMANCE_DESCRIPTION.first())
        // The Latin term stays inside the sentence, followed by the Persian comma.
        assertTrue(PERFORMANCE_DESCRIPTION.contains("FPS، "))
    }

    @Test
    fun leadingDigitsPunctuationAndMarksAreSkipped() {
        assertTrue(startsWithRtl("۱۲ ، سلام"))
        assertFalse(startsWithRtl("(FPS) سلام")) // the first strong letter is the Latin "F"
        assertFalse(startsWithRtl("12 FPS"))
        assertTrue(startsWithRtl("... 123"))
    }
}
