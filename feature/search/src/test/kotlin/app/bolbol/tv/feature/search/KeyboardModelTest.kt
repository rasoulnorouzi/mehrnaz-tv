package app.bolbol.tv.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardModelTest {

    private val persianLetters = "ا ب پ ت ث ج چ ح خ د ذ ر ز ژ س ش ص ض ط ظ ع غ ف ق ک گ ل م ن و ه ی آ ئ ء".split(' ')

    private fun KeyboardLayout.labels() = rows.flatten().map { it.label }

    @Test
    fun persianLayoutContainsEveryLetterOfTheAlphabet() {
        val labels = KeyboardLayouts.Persian.labels().toSet()
        persianLetters.forEach { assertTrue("missing $it", it in labels) }
    }

    @Test
    fun englishLayoutContainsAllLatinLetters() {
        val labels = KeyboardLayouts.English.labels().toSet()
        ('a'..'z').forEach { assertTrue("missing $it", it.toString() in labels) }
    }

    @Test
    fun digitsRowIsPersianInThePersianLayoutAndAsciiInTheEnglishOne() {
        val fa = KeyboardLayouts.Persian.characterRows[0]
        val en = KeyboardLayouts.English.characterRows[0]
        assertEquals("۱۲۳۴۵۶۷۸۹۰", fa.joinToString("") { it.label })
        assertEquals("1234567890", en.joinToString("") { it.label })
        // The inserted text is what the label shows.
        assertEquals(fa.map { it.label }, fa.map { (it.action as KeyAction.Insert).text })
        assertEquals(en.map { it.label }, en.map { (it.action as KeyAction.Insert).text })
    }

    @Test
    fun characterRowsAreTenKeysWide() {
        listOf(KeyboardLayouts.Persian, KeyboardLayouts.English).forEach { layout ->
            layout.characterRows.forEach { row -> assertEquals(10, row.size) }
        }
    }

    @Test
    fun keyIdsAreUniqueInsideEachLayout() {
        listOf(KeyboardLayouts.Persian, KeyboardLayouts.English).forEach { layout ->
            val ids = layout.rows.flatten().map { it.id }
            assertEquals(ids.size, ids.toSet().size)
        }
    }

    @Test
    fun keyIdsOfSharedPositionsMatchAcrossLayoutsSoFocusSurvivesASwitch() {
        val fa = KeyboardLayouts.Persian
        val en = KeyboardLayouts.English
        assertEquals(fa.characterRows[0].map { it.id }, en.characterRows[0].map { it.id })
        // The switch key is the first key of the function row in both layouts.
        assertEquals("switch", fa.functionRow.first().id)
        assertEquals("switch", en.functionRow.first().id)
        assertEquals(fa.functionRow.first { it.id == "space" }.action, en.functionRow.first { it.id == "space" }.action)
    }

    @Test
    fun functionRowHasSwitchSpaceBackspaceAndClear() {
        listOf(KeyboardLayouts.Persian, KeyboardLayouts.English).forEach { layout ->
            val actions = layout.functionRow.map { it.action }
            assertTrue(KeyAction.SwitchLanguage in actions)
            assertTrue(KeyAction.Space in actions)
            assertTrue(KeyAction.Backspace in actions)
            assertTrue(KeyAction.Clear in actions)
            assertTrue(layout.functionRow.all { it.kind == KeyKind.Function })
        }
    }

    @Test
    fun holdingBackspaceClearsTheQuery() {
        val backspace = KeyboardLayouts.English.functionRow.first { it.action == KeyAction.Backspace }
        assertEquals(KeyAction.Clear, backspace.longPress)
    }

    @Test
    fun persianLayoutOffersHalfSpaceKeyEnglishDoesNot() {
        val fa = KeyboardLayouts.Persian.functionRow.map { it.action }
        val en = KeyboardLayouts.English.functionRow.map { it.action }
        assertTrue(KeyAction.Insert(KeyboardLayouts.ZWNJ) in fa)
        assertFalse(KeyAction.Insert(KeyboardLayouts.ZWNJ) in en)
    }

    @Test
    fun yehAndKafHaveArabicAlternatesOnLongPress() {
        val arabicYeh = 0x064A.toChar().toString()
        val arabicKaf = 0x0643.toChar().toString()
        val yeh = KeyboardLayouts.Persian.rows.flatten().first { it.label == "ی" }
        val kaf = KeyboardLayouts.Persian.rows.flatten().first { it.label == "ک" }
        assertEquals(KeyAction.Insert(arabicYeh), yeh.longPress)
        assertEquals(arabicYeh, yeh.hint)
        assertEquals(KeyAction.Insert(arabicKaf), kaf.longPress)
        assertEquals(arabicKaf, kaf.hint)
        // The base letters must not equal their alternates (Persian yeh U+06CC vs Arabic yeh U+064A).
        assertEquals(0x06CC, yeh.label.single().code)
        assertEquals(0x06A9, kaf.label.single().code)
    }

    @Test
    fun switchKeyNamesTheLayoutItSwitchesTo() {
        assertEquals("EN", KeyboardLayouts.Persian.functionRow.first().label)
        assertEquals("فا", KeyboardLayouts.English.functionRow.first().label)
    }

    @Test
    fun layoutLookupAndStoredCodes() {
        assertEquals(KeyboardLanguage.Persian, KeyboardLayouts.Persian.language)
        assertEquals(KeyboardLanguage.English, KeyboardLayouts.of(KeyboardLanguage.English).language)
        assertEquals(KeyboardLanguage.English, KeyboardLanguage.fromCode("en"))
        assertEquals(KeyboardLanguage.Persian, KeyboardLanguage.fromCode("fa"))
        assertEquals(KeyboardLanguage.Persian, KeyboardLanguage.fromCode(null))
        assertEquals(KeyboardLanguage.Persian, KeyboardLanguage.fromCode("de"))
    }

    // --- reducer ---

    @Test
    fun insertAppendsText() {
        val s = KeyboardState().reduce(KeyAction.Insert("د")).reduce(KeyAction.Insert("ا"))
        assertEquals("دا", s.text)
    }

    @Test
    fun insertStopsAtTheMaximumLength() {
        var s = KeyboardState()
        repeat(MAX_QUERY_LENGTH + 5) { s = s.reduce(KeyAction.Insert("a")) }
        assertEquals(MAX_QUERY_LENGTH, s.text.length)
    }

    @Test
    fun spaceNeverLeadsAndNeverDoubles() {
        var s = KeyboardState().reduce(KeyAction.Space)
        assertEquals("", s.text)
        s = s.reduce(KeyAction.Insert("a")).reduce(KeyAction.Space).reduce(KeyAction.Space)
        assertEquals("a ", s.text)
        s = s.reduce(KeyAction.Insert("b"))
        assertEquals("a b", s.text)
    }

    @Test
    fun backspaceRemovesTheLastCharacter() {
        val s = KeyboardState("سلام").reduce(KeyAction.Backspace)
        assertEquals("سلا", s.text)
        assertEquals("", KeyboardState("").reduce(KeyAction.Backspace).text)
    }

    @Test
    fun backspaceRemovesAWholeSurrogatePair() {
        val s = KeyboardState("a😀").reduce(KeyAction.Backspace)
        assertEquals("a", s.text)
    }

    @Test
    fun clearEmptiesTheQuery() {
        assertEquals("", KeyboardState("friends").reduce(KeyAction.Clear).text)
    }

    @Test
    fun switchTogglesTheLayoutAndKeepsTheText() {
        val fa = KeyboardState("سلام", KeyboardLanguage.Persian)
        val en = fa.reduce(KeyAction.SwitchLanguage)
        assertEquals(KeyboardLanguage.English, en.language)
        assertEquals("سلام", en.text)
        assertEquals(KeyboardLanguage.Persian, en.reduce(KeyAction.SwitchLanguage).language)
    }

    @Test
    fun noOpActionsReturnTheSameState() {
        val empty = KeyboardState()
        assertTrue(empty === empty.reduce(KeyAction.Clear))
        assertTrue(empty === empty.reduce(KeyAction.Backspace))
        assertNull(KeyboardLayouts.Persian.rows.flatten().firstOrNull { it.label == "?" })
    }
}
