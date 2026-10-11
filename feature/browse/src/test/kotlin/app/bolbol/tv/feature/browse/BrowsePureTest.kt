package app.bolbol.tv.feature.browse

import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun title(id: Long) = TitleSummary(
    id = id, type = TitleType.MOVIE, title = "Title $id", faTitle = "عنوان $id", posterUrl = null,
    backdropUrl = null, year = 2020, imdbRating = 7f, hasDub = false, hasSubtitle = true,
)

internal fun titles(vararg ids: Long) = ids.map { title(it) }

private fun ids(list: List<TitleSummary>) = list.map { it.id }

class MergeAndPagingTest {

    @Test
    fun `merge appends new items in order`() {
        val merged = mergeUnique(titles(1, 2, 3), titles(4, 5))
        assertEquals(listOf(1L, 2, 3, 4, 5), ids(merged))
    }

    @Test
    fun `merge drops ids already loaded and duplicates inside the new page`() {
        val merged = mergeUnique(titles(1, 2, 3), titles(3, 4, 4, 2, 5))
        assertEquals(listOf(1L, 2, 3, 4, 5), ids(merged))
    }

    @Test
    fun `merge returns the same list when nothing is new`() {
        val existing = titles(1, 2)
        assertSame(existing, mergeUnique(existing, titles(2, 1)))
        assertSame(existing, mergeUnique(existing, emptyList()))
    }

    @Test
    fun `merge into empty keeps the page and dedupes it`() {
        assertEquals(listOf(7L, 8L), ids(mergeUnique(emptyList(), titles(7, 8, 7))))
    }

    @Test
    fun `append tracks the requested page and total, and stops after the last page`() {
        var state = PagingState()
        assertTrue(state.hasMore)
        assertEquals(1, state.nextPage)

        state = state.append(1, Page(titles(1, 2), page = 1, totalPages = 2))
        assertEquals(1, state.page)
        assertEquals(2, state.nextPage)
        assertTrue(state.hasMore)

        state = state.append(2, Page(titles(2, 3), page = 2, totalPages = 2))
        assertEquals(listOf(1L, 2L, 3L), ids(state.items))
        assertFalse(state.hasMore)
    }

    @Test
    fun `a server that echoes the wrong page cannot stall paging`() {
        val state = PagingState().append(1, Page(titles(1), page = 99, totalPages = 3))
        assertEquals(1, state.page)
        assertEquals(2, state.nextPage)
    }

    @Test
    fun `zero pages means nothing more to load`() {
        assertFalse(PagingState().append(1, Page(emptyList(), page = 1, totalPages = 0)).hasMore)
    }
}

class ShouldLoadMoreTest {
    // 12 items in 5 columns: rows 0..2 (5 + 5 + 2).
    private fun decide(index: Int, count: Int = 12, hasMore: Boolean = true, busy: Boolean = false) =
        shouldLoadMore(index, count, columns = 5, hasMore = hasMore, busy = busy)

    @Test
    fun `focus in the first row of a long list does not load`() {
        assertFalse(decide(index = 0, count = 36))
        assertFalse(decide(index = 4, count = 36))
    }

    @Test
    fun `focus in the last two rows loads`() {
        // 36 items: rows 0..7; rows 6 and 7 start at index 30.
        assertFalse(decide(index = 29, count = 36))
        assertTrue(decide(index = 30, count = 36))
        assertTrue(decide(index = 35, count = 36))
    }

    @Test
    fun `a short first page loads as soon as the focus leaves row zero`() {
        assertFalse(decide(index = 4))
        assertTrue(decide(index = 5))
        assertTrue(decide(index = 11))
    }

    @Test
    fun `a single row always qualifies`() {
        assertTrue(decide(index = 0, count = 3))
    }

    @Test
    fun `does nothing at the end of the list, while busy or with a bad index`() {
        assertFalse(decide(index = 11, hasMore = false))
        assertFalse(decide(index = 11, busy = true))
        assertFalse(decide(index = -1))
        assertFalse(decide(index = 12))
        assertFalse(decide(index = 0, count = 0))
    }
}

class FilterMappingTest {
    private val movies = ListQuery(Category.MOVIES)
    private val series = ListQuery(Category.SERIES)

    @Test
    fun `movies tab offers all four chips, series only the generic ones`() {
        assertEquals(
            listOf(BrowseFilter.All, BrowseFilter.Top250, BrowseFilter.Iranian, BrowseFilter.Turkish),
            quickFiltersFor(movies),
        )
        assertEquals(listOf(BrowseFilter.All, BrowseFilter.Top250), quickFiltersFor(series))
    }

    @Test
    fun `no chips for view-all rows or other categories`() {
        assertTrue(quickFiltersFor(ListQuery(Category.MOVIES, country = 1199)).isEmpty())
        assertTrue(quickFiltersFor(ListQuery(Category.MOVIES, genre = 13)).isEmpty())
        assertTrue(quickFiltersFor(ListQuery(Category.SERIES, imdbTop250 = true)).isEmpty())
        assertTrue(quickFiltersFor(ListQuery(Category.CARTOONS)).isEmpty())
        assertTrue(quickFiltersFor(ListQuery(Category.SUGGESTIONS)).isEmpty())
    }

    @Test
    fun `chips map to list queries`() {
        assertEquals(movies, movies.withFilter(BrowseFilter.All))
        assertEquals(ListQuery(Category.MOVIES, imdbTop250 = true), movies.withFilter(BrowseFilter.Top250))
        assertEquals(ListQuery(Category.MOVIES, country = 1199), movies.withFilter(BrowseFilter.Iranian))
        assertEquals(ListQuery(Category.MOVIES, country = 1198), movies.withFilter(BrowseFilter.Turkish))
        assertEquals(ListQuery(Category.SERIES, imdbTop250 = true), series.withFilter(BrowseFilter.Top250))
    }

    @Test
    fun `chip labels`() {
        assertEquals(
            listOf("همه", "۲۵۰ برتر IMDb", "ایرانی", "ترکی"),
            BrowseFilter.entries.map { it.label },
        )
    }
}

class StateTextTest {
    @Test
    fun `page line uses Persian digits and only shows with content`() {
        assertEquals("صفحه ۲ از ۸۵۹", pageCountLabel(BrowsePhase.Content, 2, 859, 24))
        assertEquals("۷ مورد", pageCountLabel(BrowsePhase.Content, 1, 1, 7))
        assertEquals("", pageCountLabel(BrowsePhase.Loading, 0, 0, 0))
        assertEquals("", pageCountLabel(BrowsePhase.Error, 0, 0, 0))
    }

    @Test
    fun `every catalog failure has its own Persian message`() {
        assertEquals(MESSAGE_OFFLINE, CatalogException.Offline().browseMessage())
        assertEquals(MESSAGE_SERVER_DOWN, CatalogException.AllMirrorsDown().browseMessage())
        assertEquals(MESSAGE_NOT_FOUND, CatalogException.NotFound().browseMessage())
        assertEquals(MESSAGE_BAD_RESPONSE, CatalogException.BadResponse().browseMessage())
        assertEquals(MESSAGE_UNKNOWN, IllegalStateException("x").browseMessage())
        assertEquals(5, setOf(MESSAGE_OFFLINE, MESSAGE_SERVER_DOWN, MESSAGE_NOT_FOUND, MESSAGE_BAD_RESPONSE, MESSAGE_UNKNOWN).size)
    }
}
