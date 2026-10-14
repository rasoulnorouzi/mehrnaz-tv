package app.bolbol.tv.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class BytesFormatTest {
    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024

    @Test
    fun gigabytesUseTwoDecimalsBelowTenAndOneAbove() {
        assertEquals("۲٫۴۲ گیگابایت", formatBytesFa((2.42 * gib).toLong()))
        assertEquals("۲٫۴ گیگابایت", formatBytesFa((2.4 * gib).toLong()))
        assertEquals("۳ گیگابایت", formatBytesFa(3 * gib))
        assertEquals("۱۲٫۳ گیگابایت", formatBytesFa((12.3 * gib).toLong()))
    }

    @Test
    fun smallerSizesAndEdgeCases() {
        assertEquals("۸۵۰ مگابایت", formatBytesFa(850 * mib))
        assertEquals("۱۲ کیلوبایت", formatBytesFa(12 * 1024L))
        assertEquals("۰ کیلوبایت", formatBytesFa(-5))
    }
}
