package app.bolbol.tv.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleSuggestionIndexTest {
    private fun index(vararg titles: String) = TitleSuggestionIndex(Dispatchers.Unconfined).apply {
        add(titles.mapIndexed { i, t -> summary(i.toLong(), t).copy(faTitle = "") })
    }

    private fun TitleSuggestionIndex.ask(prefix: String, limit: Int = 6) = runBlocking { suggest(prefix, limit) }

    @Test
    fun wholeTitlePrefixBeatsWordPrefixAndShorterBeatsLonger() {
        val idx = index("The Dark Knight", "Dark City", "Dark", "Darkest Hour", "Into Darkness")
        // "Dark" itself adds nothing; "Dark City" and "Darkest Hour" start with it; the others have it mid-title.
        assertEquals(listOf("Dark City", "Darkest Hour", "Into Darkness", "The Dark Knight"), idx.ask("dark"))
    }

    @Test
    fun latinIsCaseInsensitiveAndTrailingSpaceMeansNextWord() {
        val idx = index("The Dark Knight", "Darkest Hour")
        assertEquals(listOf("The Dark Knight"), idx.ask("DARK K"))
        assertEquals(listOf("The Dark Knight"), idx.ask("the "))
    }

    @Test
    fun arabicYehKafAndZwnjAndDigitsAreFolded() {
        val ky = listOf(0x0643, 0x064A).joinToString("") { it.toChar().toString() } // Arabic kaf + yeh
        val zwnj = 0x200C.toChar()
        val idx = index("کیمیا و خاطره", "می${zwnj}خواهم", "فصل ۲ سریال")
        assertEquals(listOf("کیمیا و خاطره"), idx.ask(ky))
        assertEquals(listOf("می${zwnj}خواهم"), idx.ask("می"))
        assertEquals(listOf("می${zwnj}خواهم"), idx.ask("خوا")) // word after the half-space
        assertEquals(listOf("فصل ۲ سریال"), idx.ask("فصل 2"))
    }

    @Test
    fun limitAndEmptyInput() {
        val idx = index("aa1", "aa2", "aa3", "aa4")
        assertEquals(2, idx.ask("aa", 2).size)
        assertTrue(idx.ask("").isEmpty())
        assertTrue(idx.ask("zz").isEmpty())
    }

    @Test
    fun indexesBothTitlesAndIsBounded() {
        val idx = TitleSuggestionIndex(Dispatchers.Unconfined, maxTitles = 3)
        idx.add(listOf(summary(1, "Alpha").copy(faTitle = "آلفا"), summary(2, "Beta").copy(faTitle = "")))
        idx.add(listOf(summary(3, "Gamma").copy(faTitle = "")))
        assertEquals(3, idx.size()) // Alpha was evicted
        assertEquals(listOf("آلفا"), runBlocking { idx.suggest("آل", 6) })
    }
}
