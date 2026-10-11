package app.bolbol.tv.core.lanremote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTextTest {
    private fun s(vararg codes: Int) = codes.joinToString("") { String(Character.toChars(it)) }

    @Test
    fun arabicYehAndKafBecomePersian() {
        assertEquals(s(0x06CC), normalizeRemoteText(s(0x064A)))
        assertEquals(s(0x06A9), normalizeRemoteText(s(0x0643)))
        assertEquals(
            s(0x06A9, 0x06CC, 0x0627, 0x0646),
            normalizeRemoteText(s(0x0643, 0x064A, 0x0627, 0x0646)),
        )
    }

    @Test
    fun persianLettersAreUnchanged() {
        val text = s(0x06A9, 0x06CC, 0x0627, 0x0646) + " " + s(0x067E, 0x0698, 0x0686, 0x06AF)
        assertEquals(text, normalizeRemoteText(text))
    }

    @Test
    fun zeroWidthNonJoinerAndDigitsAreKept() {
        val text = s(0x0645, 0x06CC, 0x200C, 0x0634, 0x0648, 0x062F) + " " + s(0x06F1, 0x06F2) + " 12"
        assertEquals(text, normalizeRemoteText(text))
    }

    @Test
    fun latinTextAndSpacesAreUnchanged() {
        assertEquals("  Dune (2024) ", normalizeRemoteText("  Dune (2024) "))
        assertEquals("", normalizeRemoteText(""))
    }

    @Test
    fun lineBreaksBecomeSpacesAndControlCharactersAreDropped() {
        assertEquals("a b c d", normalizeRemoteText("a\nb\r" + "c\td"))
        assertEquals("ab", normalizeRemoteText("a" + s(0x00) + s(0x07) + s(0x7F) + s(0x85) + "b"))
    }

    @Test
    fun bidiControlsAndBomAreDropped() {
        val marks = s(0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0xFEFF)
        assertEquals("ab", normalizeRemoteText("a" + marks + "b"))
    }

    @Test
    fun longTextIsCutToTheLimit() {
        assertEquals(MAX_REMOTE_TEXT_CHARS, normalizeRemoteText("x".repeat(1000)).length)
        assertEquals(MAX_REMOTE_TEXT_CHARS, normalizeRemoteText("x".repeat(MAX_REMOTE_TEXT_CHARS)).length)
    }

    @Test
    fun surrogatePairsAreKeptWholeAndNeverSplitAtTheLimit() {
        val emoji = s(0x1F3AC)
        assertEquals("a${emoji}b", normalizeRemoteText("a${emoji}b"))
        val atLimit = normalizeRemoteText("x".repeat(MAX_REMOTE_TEXT_CHARS - 1) + emoji)
        assertEquals(MAX_REMOTE_TEXT_CHARS - 1, atLimit.length)
        val fits = normalizeRemoteText("x".repeat(MAX_REMOTE_TEXT_CHARS - 2) + emoji)
        assertEquals(MAX_REMOTE_TEXT_CHARS, fits.length)
    }

    @Test
    fun unpairedSurrogatesAreDropped() {
        assertEquals("ab", normalizeRemoteText("a" + 0xD83C.toChar() + "b"))
        assertEquals("ab", normalizeRemoteText("a" + 0xDFAC.toChar() + "b"))
        assertEquals("a", normalizeRemoteText("a" + 0xD83C.toChar()))
    }
}
