package app.bolbol.tv.feature.search

import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLogicTest {

    private fun title(id: Long) = TitleSummary(
        id = id,
        type = TitleType.MOVIE,
        title = "Title $id",
        faTitle = "عنوان $id",
        posterUrl = null,
        backdropUrl = null,
        year = 2020,
        imdbRating = 7.5f,
        hasDub = true,
        hasSubtitle = false,
    )

    // --- recent searches ---

    @Test
    fun newSearchGoesFirst() {
        assertEquals(listOf("dune", "friends"), listOf("friends").withRecent("dune"))
    }

    @Test
    fun repeatedSearchMovesToTheFrontWithoutDuplicating() {
        val result = listOf("a1", "b2", "c3").withRecent("c3")
        assertEquals(listOf("c3", "a1", "b2"), result)
    }

    @Test
    fun duplicatesAreDetectedIgnoringLatinCase() {
        assertEquals(listOf("DUNE", "friends"), listOf("dune", "friends").withRecent("DUNE"))
    }

    @Test
    fun recentsAreCappedAtEight() {
        var list = emptyList<String>()
        for (i in 1..12) list = list.withRecent("query$i")
        assertEquals(MAX_RECENTS, list.size)
        assertEquals("query12", list.first())
        assertEquals("query5", list.last())
    }

    @Test
    fun tooShortAndBlankQueriesAreIgnored() {
        val base = listOf("dune")
        assertSame(base, base.withRecent("a"))
        assertSame(base, base.withRecent("   "))
    }

    @Test
    fun recentsStoreTheNormalisedQuery() {
        val arabicYeh = 0x064A.toChar()
        val saved = emptyList<String>().withRecent("  عل$arabicYeh   ")
        assertEquals(listOf("علی"), saved)
    }

    @Test
    fun recentsSurviveAnEncodeDecodeRoundTrip() {
        val list = listOf("دوست‌داشتنی", "Friends 2", "سریال")
        assertEquals(list, decodeRecents(encodeRecents(list)))
    }

    @Test
    fun decodingGarbageGivesACleanList() {
        assertEquals(emptyList<String>(), decodeRecents(null))
        assertEquals(emptyList<String>(), decodeRecents(""))
        assertEquals(listOf("dune"), decodeRecents("x\n\n  \ndune\nDUNE"))
        val many = (1..20).joinToString("\n") { "query$it" }
        assertEquals(MAX_RECENTS, decodeRecents(many).size)
    }

    // --- pagination ---

    @Test
    fun mergeAppendsNewTitlesInOrder() {
        val merged = mergePages(listOf(title(1), title(2)), listOf(title(3), title(4)))
        assertEquals(listOf(1L, 2L, 3L, 4L), merged.map { it.id })
    }

    @Test
    fun mergeDropsTitlesAlreadyPresent() {
        val merged = mergePages(listOf(title(1), title(2)), listOf(title(2), title(3)))
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
    }

    @Test
    fun mergeDropsRepeatsInsideTheIncomingPage() {
        val merged = mergePages(emptyList(), listOf(title(5), title(5), title(6)))
        assertEquals(listOf(5L, 6L), merged.map { it.id })
    }

    @Test
    fun mergeReturnsTheSameListWhenNothingIsNew() {
        val existing = listOf(title(1), title(2))
        assertSame(existing, mergePages(existing, listOf(title(2), title(1))))
        assertSame(existing, mergePages(existing, emptyList()))
    }

    @Test
    fun lastRowDetection() {
        // 12 items in 3 columns: rows start at 0, 3, 6, 9.
        assertFalse(isInLastRow(8, total = 12, columns = 3))
        assertTrue(isInLastRow(9, total = 12, columns = 3))
        assertTrue(isInLastRow(11, total = 12, columns = 3))
        // 10 items: the last row holds only index 9.
        assertFalse(isInLastRow(8, total = 10, columns = 3))
        assertTrue(isInLastRow(9, total = 10, columns = 3))
        // 4 columns.
        assertFalse(isInLastRow(7, total = 12, columns = 4))
        assertTrue(isInLastRow(8, total = 12, columns = 4))
    }

    @Test
    fun lastRowDetectionHandlesDegenerateInput() {
        assertFalse(isInLastRow(0, total = 0, columns = 3))
        assertFalse(isInLastRow(-1, total = 12, columns = 3))
        assertFalse(isInLastRow(0, total = 12, columns = 0))
        assertTrue(isInLastRow(0, total = 2, columns = 3))
    }

    // --- errors ---

    @Test
    fun catalogExceptionsMapToPersianMessages() {
        assertEquals(SearchError.Offline, CatalogException.Offline().toSearchError())
        assertEquals(SearchError.AllMirrorsDown, CatalogException.AllMirrorsDown().toSearchError())
        assertEquals(SearchError.BadResponse, CatalogException.BadResponse().toSearchError())
        assertEquals(SearchError.Unknown, IllegalStateException("boom").toSearchError())
    }

    @Test
    fun everyErrorHasADistinctPersianMessage() {
        val messages = SearchError.entries.map { it.message }
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(messages.all { m -> m.any { it.code in 0x0600..0x06FF } })
    }
}
