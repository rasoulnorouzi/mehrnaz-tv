package app.bolbol.tv.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryTextTest {

    // Arabic forms built from code points so they cannot be confused with the Persian ones.
    private val arabicYeh = 0x064A.toChar()
    private val alefMaksura = 0x0649.toChar()
    private val arabicKaf = 0x0643.toChar()
    private val persianYeh = 0x06CC.toChar()
    private val persianKaf = 0x06A9.toChar()
    private val zwnj = 0x200C.toChar()

    @Test
    fun trimsAndCollapsesWhitespace() {
        assertEquals("Friends 2", normalizeQuery("  Friends \t  2  \n"))
        assertEquals("a b", normalizeQuery("a  b"))
    }

    @Test
    fun foldsArabicYehAndKafToPersian() {
        assertEquals("${persianYeh}ک", normalizeQuery("${arabicYeh}$arabicKaf"))
        assertEquals("${persianKaf}${persianYeh}", normalizeQuery("${arabicKaf}${alefMaksura}"))
    }

    @Test
    fun alreadyPersianTextIsUnchanged() {
        assertEquals("کتاب علی", normalizeQuery("کتاب علی"))
    }

    @Test
    fun dropsTatweelHarakatAndBidiMarks() {
        val tatweel = 0x0640.toChar()
        val fatha = 0x064E.toChar()
        val rlm = 0x200F.toChar()
        val lrm = 0x200E.toChar()
        assertEquals("سلام", normalizeQuery("سل${tatweel}ا${fatha}م$rlm$lrm"))
    }

    @Test
    fun keepsHalfSpaceInsideWordsButTrimsItAtTheEnds() {
        assertEquals("سایه${zwnj}ها", normalizeQuery("سایه${zwnj}ها"))
        assertEquals("سایه", normalizeQuery("سایه$zwnj"))
        assertEquals("سایه", normalizeQuery("$zwnj سایه $zwnj"))
    }

    @Test
    fun leavesDigitsAndLatinAsTyped() {
        assertEquals("Spider-Man 2", normalizeQuery("Spider-Man 2"))
        assertEquals("بوبو ۲", normalizeQuery("بوبو ۲"))
    }

    @Test
    fun emptyAndBlankBecomeEmpty() {
        assertEquals("", normalizeQuery(""))
        assertEquals("", normalizeQuery("   \t "))
    }

    @Test
    fun searchableNeedsTwoCharactersAfterNormalising() {
        assertFalse(isSearchable(normalizeQuery("a")))
        assertFalse(isSearchable(normalizeQuery(" a ")))
        assertTrue(isSearchable(normalizeQuery("ab")))
        assertTrue(isSearchable(normalizeQuery("دو")))
    }

    // --- sanitizeInput (phone / voice text) ---

    @Test
    fun sanitizeCollapsesSpacesButKeepsOneTrailingSpace() {
        assertEquals("dune ", sanitizeInput("  dune \n "))
        assertEquals("a b", sanitizeInput("a   b"))
        assertEquals("", sanitizeInput("   "))
    }

    @Test
    fun sanitizeDoesNotFoldLettersButRemovesMarks() {
        val rlm = 0x200F.toChar()
        assertEquals("${arabicYeh}", sanitizeInput("$rlm$arabicYeh"))
    }

    @Test
    fun sanitizeCutsToTheMaximumLengthWithoutSplittingASurrogatePair() {
        val long = "a".repeat(MAX_QUERY_LENGTH - 1) + "😀"
        val cut = sanitizeInput(long)
        assertEquals(MAX_QUERY_LENGTH - 1, cut.length)
        assertEquals(MAX_QUERY_LENGTH, sanitizeInput("b".repeat(100)).length)
    }

    // --- script detection ---

    @Test
    fun detectsTheScriptOfTheFirstLetter() {
        assertEquals(KeyboardLanguage.Persian, detectLanguage("سلام"))
        assertEquals(KeyboardLanguage.English, detectLanguage("Friends"))
        assertEquals(KeyboardLanguage.English, detectLanguage("2 Fast"))
        assertEquals(KeyboardLanguage.Persian, detectLanguage("۲ مرد"))
        assertEquals(KeyboardLanguage.Persian, detectLanguage("فیلم Dune"))
        assertEquals(KeyboardLanguage.English, detectLanguage("Dune فیلم"))
    }

    @Test
    fun noScriptWhenThereIsNoLetter() {
        assertNull(detectLanguage(""))
        assertNull(detectLanguage("123 -"))
    }

    @Test
    fun queryDirectionFollowsTheTextThenTheLayout() {
        assertTrue(isRtlQuery("سلام", KeyboardLanguage.English))
        assertFalse(isRtlQuery("dune", KeyboardLanguage.Persian))
        assertTrue(isRtlQuery("12", KeyboardLanguage.Persian))
        assertFalse(isRtlQuery("12", KeyboardLanguage.English))
    }
}
