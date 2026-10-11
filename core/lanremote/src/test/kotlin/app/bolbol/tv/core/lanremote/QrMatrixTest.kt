package app.bolbol.tv.core.lanremote

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QrMatrixTest {
    private val url = "http://192.168.1.20:8123/?t=0123456789abcdef0123456789abcdef"

    private fun decode(matrix: QrMatrix, scale: Int): String {
        val side = matrix.size * scale
        val source = RGBLuminanceSource(side, side, matrix.toArgb(scale))
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun sampleUrlEncodesToAValidQrSize() {
        val matrix = encodeQr(url)
        val modules = matrix.size - 2 * QR_QUIET_ZONE_MODULES
        // A QR symbol of version v is 4v + 17 modules wide; this URL fits comfortably in versions 1..10.
        assertEquals(1, modules % 4)
        assertTrue("modules=$modules", modules in 21..57)
    }

    @Test
    fun quietZoneIsWhiteOnAllFourSides() {
        val matrix = encodeQr(url)
        for (i in 0 until QR_QUIET_ZONE_MODULES) {
            for (j in 0 until matrix.size) {
                assertFalse(matrix[i, j])
                assertFalse(matrix[j, i])
                assertFalse(matrix[matrix.size - 1 - i, j])
                assertFalse(matrix[j, matrix.size - 1 - i])
            }
        }
    }

    @Test
    fun customQuietZoneWidthIsHonoured() {
        assertEquals(encodeQr(url, quietZone = 0).size + 2, encodeQr(url, quietZone = 1).size)
        assertEquals(encodeQr(url, quietZone = 0).size + 8, encodeQr(url).size)
    }

    @Test
    fun finderPatternsSitInThreeCorners() {
        val matrix = encodeQr(url)
        val q = QR_QUIET_ZONE_MODULES
        val far = matrix.size - q - 7
        for ((ox, oy) in listOf(q to q, far to q, q to far)) {
            for (y in 0 until 7) {
                for (x in 0 until 7) {
                    val ring = x == 0 || x == 6 || y == 0 || y == 6
                    val core = x in 2..4 && y in 2..4
                    assertEquals("finder at ($ox,$oy) cell ($x,$y)", ring || core, matrix[ox + x, oy + y])
                }
            }
        }
    }

    @Test
    fun outOfRangeModulesAreLight() {
        val matrix = encodeQr(url)
        assertFalse(matrix[-1, 0])
        assertFalse(matrix[0, -1])
        assertFalse(matrix[matrix.size, 0])
        assertFalse(matrix[0, matrix.size])
    }

    @Test
    fun pixelsAreBlackOnWhite() {
        val matrix = encodeQr(url)
        val scale = 3
        val side = matrix.size * scale
        val pixels = matrix.toArgb(scale)
        assertEquals(side * side, pixels.size)
        assertTrue(pixels.all { it == 0xFF000000.toInt() || it == 0xFFFFFFFF.toInt() })
        assertEquals(0xFFFFFFFF.toInt(), pixels[0]) // top-left corner is quiet zone
        val q = QR_QUIET_ZONE_MODULES
        assertEquals(0xFF000000.toInt(), pixels[(q * scale) * side + q * scale]) // first finder module
    }

    @Test
    fun roundTripsThroughARealQrDecoder() {
        val matrix = encodeQr(url)
        for (scale in listOf(2, 4, 9)) assertEquals("scale $scale", url, decode(matrix, scale))
    }

    @Test
    fun roundTripsPersianAndLongContent() {
        val persian = "بلبل تی" + 0x200C.toChar() + "وی ۱۲۳"
        assertEquals(persian, decode(encodeQr(persian), 4))
        val long = "http://10.0.0.5:8999/?t=" + "f".repeat(32) + "&x=" + "y".repeat(80)
        assertEquals(long, decode(encodeQr(long), 4))
    }

    @Test
    fun contentThatCannotBeEncodedThrows() {
        try {
            encodeQr("")
            fail("expected an exception for empty content")
        } catch (_: Exception) {
            // WriterException or IllegalArgumentException: QrCode() catches both and draws nothing.
        }
    }
}
