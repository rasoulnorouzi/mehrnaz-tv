package app.bolbol.tv.feature.tvhome

import app.bolbol.tv.core.model.WatchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinksTest {

    @Test
    fun `play link format`() {
        assertEquals("mehrnaz://play/353371/0/0", DeepLinks.playString(WatchKey(353371)))
        assertEquals("mehrnaz://play/7/1/2", DeepLinks.playString(WatchKey(7, 1, 2)))
        assertEquals("mehrnaz", DeepLinks.SCHEME)
    }

    @Test
    fun `round trip for movies and episodes`() {
        listOf(
            WatchKey(1),
            WatchKey(353371),
            WatchKey(7, 1, 2),
            WatchKey(Long.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
            WatchKey(42, 0, 5), // odd but representable: must still round trip
        ).forEach { key ->
            assertEquals(key, DeepLinks.parseString(DeepLinks.playString(key)))
        }
    }

    @Test
    fun `scheme and host are case-insensitive`() {
        assertEquals(WatchKey(7, 1, 2), DeepLinks.parseString("MEHRNAZ://Play/7/1/2"))
    }

    @Test
    fun `rejects null and unrelated input`() {
        assertNull(DeepLinks.parseString(null))
        assertNull(DeepLinks.parseString(""))
        assertNull(DeepLinks.parseString("mehrnaz://"))
        assertNull(DeepLinks.parseString("https://example.com/play/7/1/2"))
        assertNull(DeepLinks.parseString("7:1:2"))
    }

    @Test
    fun `rejects wrong scheme or host`() {
        assertNull(DeepLinks.parseString("http://play/7/1/2"))
        assertNull(DeepLinks.parseString("mehrnazx://play/7/1/2"))
        assertNull(DeepLinks.parseString("mehrnaz://watch/7/1/2"))
        assertNull(DeepLinks.parseString("mehrnaz://playx/7/1/2"))
        assertNull(DeepLinks.parseString("mehrnaz:play/7/1/2"))
    }

    @Test
    fun `rejects wrong segment count`() {
        assertNull(DeepLinks.parseString("mehrnaz://play"))
        assertNull(DeepLinks.parseString("mehrnaz://play/"))
        assertNull(DeepLinks.parseString("mehrnaz://play/7"))
        assertNull(DeepLinks.parseString("mehrnaz://play/7/1"))
        assertNull(DeepLinks.parseString("mehrnaz://play/7/1/2/3"))
        assertNull(DeepLinks.parseString("mehrnaz://play/7/1/2/"))
        assertNull(DeepLinks.parseString("mehrnaz://play//1/2"))
        assertNull(DeepLinks.parseString("mehrnaz://play/7//2"))
    }

    @Test
    fun `rejects non numeric negative or out of range numbers`() {
        assertNull(DeepLinks.parseString("mehrnaz://play/x/1/2"))
        assertNull(DeepLinks.parseString("mehrnaz://play/7/a/2"))
        assertNull(DeepLinks.parseString("mehrnaz://play/7/1/b"))
        assertNull(DeepLinks.parseString("mehrnaz://play/-7/1/2"))
        assertNull(DeepLinks.parseString("mehrnaz://play/7/-1/2"))
        assertNull(DeepLinks.parseString("mehrnaz://play/7/1/-2"))
        assertNull(DeepLinks.parseString("mehrnaz://play/+7/1/2"))
        assertNull(DeepLinks.parseString("mehrnaz://play/7.5/1/2"))
        assertNull(DeepLinks.parseString("mehrnaz://play/ 7/1/2"))
        assertNull(DeepLinks.parseString("mehrnaz://play/۷/1/2")) // Persian digit
        assertNull(DeepLinks.parseString("mehrnaz://play/0/0/0")) // no such post
        assertNull(DeepLinks.parseString("mehrnaz://play/99999999999999999999/1/2")) // Long overflow
        assertNull(DeepLinks.parseString("mehrnaz://play/7/2147483648/2")) // Int overflow
        assertNull(DeepLinks.parseString("mehrnaz://play/7/1/2147483648"))
    }

    @Test
    fun `rejects query and fragment`() {
        assertNull(DeepLinks.parseString("mehrnaz://play/7/1/2?t=5"))
        assertNull(DeepLinks.parseString("mehrnaz://play/7/1/2#x"))
    }
}
