package app.bolbol.tv.feature.browse

import androidx.lifecycle.viewmodel.CreationExtras
import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.CatalogRepository
import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.Genre
import app.bolbol.tv.core.model.HomeFeed
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.model.TitleSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fake server: [totalPages] pages of [perPage] items, ids `(page - 1) * perPage + 1 ..` shifted back
 * by [overlap] on every page after the first (to simulate titles repeating across pages).
 */
private class FakeCatalog(
    var totalPages: Int = 3,
    private val perPage: Int = 12,
    private val overlap: Int = 0,
) : CatalogRepository {
    val calls = mutableListOf<Pair<ListQuery, Int>>()
    var failure: CatalogException? = null
    var gate: CompletableDeferred<Unit>? = null
    var emptyResult = false
    var genreList: List<Genre> = listOf(Genre(29, "اکشن"), Genre(13, "درام"))
    var genreFailure: Exception? = null
    var genreGate: CompletableDeferred<Unit>? = null
    val genreCalls = mutableListOf<Category>()

    override suspend fun genres(category: Category): List<Genre> {
        genreCalls += category
        genreGate?.await()
        genreFailure?.let { throw it }
        return genreList
    }

    override suspend fun list(query: ListQuery, page: Int): Page<TitleSummary> {
        calls += query to page
        gate?.await()
        failure?.let { throw it }
        if (emptyResult) return Page(emptyList(), page, 0)
        val start = (page - 1) * perPage + 1 - if (page > 1) overlap else 0
        return Page((start until start + perPage).map { title(it.toLong()) }, page, totalPages)
    }

    override fun homeFeed(): Flow<HomeFeed> = error("unused")
    override suspend fun details(id: Long): TitleDetails = error("unused")
    override suspend fun search(text: String, page: Int): Page<TitleSummary> = error("unused")
}

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {
    private val movies = ListQuery(Category.MOVIES)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(catalog: FakeCatalog, query: ListQuery = movies) = BrowseViewModel(query, "فیلم", catalog)

    private val BrowseViewModel.ids get() = state.value.items.map { it.id }

    @Test
    fun `loads page one on creation`() {
        val catalog = FakeCatalog()
        val vm = viewModel(catalog)

        val s = vm.state.value
        assertEquals(BrowsePhase.Content, s.phase)
        assertEquals((1L..12L).toList(), vm.ids)
        assertEquals(1, s.page)
        assertEquals(3, s.totalPages)
        assertEquals(listOf(movies to 1), catalog.calls)
        assertEquals("فیلم", s.title)
    }

    @Test
    fun `focusing a poster near the end loads the next page and only once`() {
        val catalog = FakeCatalog()
        val vm = viewModel(catalog)

        vm.onItemFocused(id = 1, index = 0) // first row of three: too early
        assertEquals(1, catalog.calls.size)

        vm.onItemFocused(id = 6, index = 5)
        assertEquals(2, catalog.calls.last().second)
        assertEquals((1L..24L).toList(), vm.ids)
        assertEquals(2, vm.state.value.page)
        assertFalse(vm.state.value.loadingMore)
    }

    @Test
    fun `a focus while a page is in flight does not start a second request`() {
        val catalog = FakeCatalog()
        val vm = viewModel(catalog)
        catalog.gate = CompletableDeferred()

        vm.onItemFocused(6, 5)
        assertTrue(vm.state.value.loadingMore)
        vm.onItemFocused(7, 6)
        vm.onItemFocused(8, 7)
        assertEquals(2, catalog.calls.size) // page 1 + one page 2

        catalog.gate!!.complete(Unit)
        assertEquals(24, vm.ids.size)
        assertFalse(vm.state.value.loadingMore)
    }

    @Test
    fun `stops at the last page`() {
        val catalog = FakeCatalog(totalPages = 2)
        val vm = viewModel(catalog)

        vm.onItemFocused(6, 5)
        assertEquals(24, vm.ids.size)

        vm.onItemFocused(20, 19)
        vm.onItemFocused(24, 23)
        vm.loadMore()
        assertEquals(listOf(1, 2), catalog.calls.map { it.second })
        assertFalse(vm.state.value.loadingMore)
        assertEquals(BrowsePhase.Content, vm.state.value.phase)
    }

    @Test
    fun `a single page list never asks for more`() {
        val catalog = FakeCatalog(totalPages = 1)
        val vm = viewModel(catalog)

        vm.onItemFocused(12, 11)
        assertEquals(1, catalog.calls.size)
    }

    @Test
    fun `titles repeated across pages appear once`() {
        val catalog = FakeCatalog(totalPages = 3, overlap = 4)
        val vm = viewModel(catalog)

        vm.onItemFocused(6, 5)
        vm.onItemFocused(20, 15)

        assertEquals(vm.ids.distinct(), vm.ids)
        assertEquals((1L..32L).toList(), vm.ids) // page 2 adds 13..20 (9..12 repeat), page 3 adds 21..32
        assertEquals(3, vm.state.value.page)
    }

    @Test
    fun `a page of only duplicates is skipped so paging cannot stall`() {
        val catalog = FakeCatalog(totalPages = 4, perPage = 12, overlap = 12)
        val vm = viewModel(catalog)

        vm.onItemFocused(6, 5)
        // Page 2 == page 1 (all dupes), so page 3 is fetched right away.
        assertEquals(listOf(1, 2, 3), catalog.calls.map { it.second })
        assertEquals(24, vm.ids.size)
        assertFalse(vm.state.value.loadingMore)
    }

    @Test
    fun `first page failure shows an error and retry recovers`() {
        val catalog = FakeCatalog().apply { failure = CatalogException.Offline() }
        val vm = viewModel(catalog)

        assertEquals(BrowsePhase.Error, vm.state.value.phase)
        assertEquals(MESSAGE_OFFLINE, vm.state.value.error)

        catalog.failure = null
        vm.retry()
        assertEquals(BrowsePhase.Content, vm.state.value.phase)
        assertNull(vm.state.value.error)
        assertEquals(12, vm.ids.size)
    }

    @Test
    fun `each failure kind maps to its message`() {
        val catalog = FakeCatalog().apply { failure = CatalogException.AllMirrorsDown() }
        assertEquals(MESSAGE_SERVER_DOWN, viewModel(catalog).state.value.error)
        catalog.failure = CatalogException.BadResponse()
        assertEquals(MESSAGE_BAD_RESPONSE, viewModel(catalog).state.value.error)
    }

    @Test
    fun `empty first page is the empty state`() {
        val catalog = FakeCatalog().apply { emptyResult = true }
        val vm = viewModel(catalog)

        assertEquals(BrowsePhase.Empty, vm.state.value.phase)
        vm.onItemFocused(1, 0) // nothing to focus really, but must not crash or load
        assertEquals(1, catalog.calls.size)
    }

    @Test
    fun `a failed later page keeps the grid, is not retried by focus, and retries on demand`() {
        val catalog = FakeCatalog()
        val vm = viewModel(catalog)
        catalog.failure = CatalogException.AllMirrorsDown()

        vm.onItemFocused(6, 5)
        var s = vm.state.value
        assertEquals(BrowsePhase.Content, s.phase)
        assertEquals(12, s.items.size)
        assertEquals(MESSAGE_SERVER_DOWN, s.loadMoreError)
        assertFalse(s.loadingMore)
        assertEquals(2, catalog.calls.size)

        vm.onItemFocused(7, 6) // no hammering
        assertEquals(2, catalog.calls.size)

        catalog.failure = null
        vm.loadMore()
        s = vm.state.value
        assertNull(s.loadMoreError)
        assertEquals(24, s.items.size)
        assertEquals(listOf(1, 2, 2), catalog.calls.map { it.second })
    }

    @Test
    fun `choosing a chip reloads from page one with the mapped query`() {
        val catalog = FakeCatalog()
        val vm = viewModel(catalog)
        vm.onItemFocused(6, 5)
        assertEquals(2, vm.state.value.page)

        vm.selectFilter(BrowseFilter.Iranian)

        assertEquals(BrowseFilter.Iranian, vm.state.value.selected)
        assertEquals(ListQuery(Category.MOVIES, country = 1199) to 1, catalog.calls.last())
        assertEquals(1, vm.state.value.page)
        assertEquals(12, vm.ids.size)

        vm.selectFilter(BrowseFilter.Top250)
        assertEquals(ListQuery(Category.MOVIES, imdbTop250 = true) to 1, catalog.calls.last())

        vm.selectFilter(BrowseFilter.All)
        assertEquals(movies to 1, catalog.calls.last())
        // Paging continues with the active query.
        vm.onItemFocused(6, 5)
        assertEquals(movies to 2, catalog.calls.last())
    }

    @Test
    fun `picking the selected chip again does not reload`() {
        val catalog = FakeCatalog()
        val vm = viewModel(catalog)
        vm.selectFilter(BrowseFilter.All)
        assertEquals(1, catalog.calls.size)
    }

    @Test
    fun `switching chip while a page is loading drops the stale result`() {
        val catalog = FakeCatalog()
        val vm = viewModel(catalog)
        catalog.gate = CompletableDeferred()
        vm.onItemFocused(6, 5) // page 2 of "all" now waiting on the gate

        vm.selectFilter(BrowseFilter.Turkish) // cancels it, loads Turkish page 1 (also waits)
        catalog.gate!!.complete(Unit)

        val s = vm.state.value
        assertEquals(BrowseFilter.Turkish, s.selected)
        assertEquals(1, s.page)
        assertEquals(12, s.items.size)
        assertFalse(s.loadingMore)
    }

    @Test
    fun `view-all queries get no chips and cannot be re-filtered`() {
        val query = ListQuery(Category.MOVIES, country = 1199)
        val catalog = FakeCatalog()
        val vm = viewModel(catalog, query)

        assertTrue(vm.state.value.filters.isEmpty())
        vm.selectFilter(BrowseFilter.Turkish)
        assertEquals(listOf(query to 1), catalog.calls)
    }

    @Test
    fun `genres load next to the grid and choosing one reloads page one with that genre only`() {
        val catalog = FakeCatalog()
        val vm = viewModel(catalog)
        assertEquals(listOf(Genre(29, "اکشن"), Genre(13, "درام")), vm.state.value.genres)
        assertEquals(listOf(Category.MOVIES), catalog.genreCalls)

        vm.selectFilter(BrowseFilter.Iranian)
        vm.onItemFocused(6, 5)
        vm.selectGenre(Genre(13, "درام"))

        val s = vm.state.value
        assertEquals(Genre(13, "درام"), s.selectedGenre)
        assertNull(s.selected) // the quick chip is deselected: filters never stack
        assertEquals(ListQuery(Category.MOVIES, genre = 13) to 1, catalog.calls.last())
        assertEquals(1, s.page)
        assertEquals(12, s.items.size)

        // Paging keeps the genre; going back to a quick chip drops it.
        vm.onItemFocused(6, 5)
        assertEquals(ListQuery(Category.MOVIES, genre = 13) to 2, catalog.calls.last())
        vm.selectFilter(BrowseFilter.All)
        assertNull(vm.state.value.selectedGenre)
        assertEquals(BrowseFilter.All, vm.state.value.selected)
        assertEquals(movies to 1, catalog.calls.last())
    }

    @Test
    fun `the grid does not wait for the genres`() {
        val catalog = FakeCatalog().apply { genreGate = CompletableDeferred() }
        val vm = viewModel(catalog)

        assertEquals(BrowsePhase.Content, vm.state.value.phase)
        assertTrue(vm.state.value.genres.isEmpty())
        catalog.genreGate!!.complete(Unit)
        assertEquals(2, vm.state.value.genres.size)
    }

    @Test
    fun `series ask for the series genres and a genre failure only hides the genre chips`() {
        val series = ListQuery(Category.SERIES)
        val catalog = FakeCatalog().apply { genreFailure = IllegalStateException("boom") }
        val vm = viewModel(catalog, series)

        assertEquals(listOf(Category.SERIES), catalog.genreCalls)
        assertTrue(vm.state.value.genres.isEmpty())
        assertEquals(BrowsePhase.Content, vm.state.value.phase)
        assertEquals(listOf(BrowseFilter.All, BrowseFilter.Top250), vm.state.value.filters)
    }

    @Test
    fun `view-all queries and unknown genres are ignored`() {
        val catalog = FakeCatalog()
        val filtered = viewModel(catalog, ListQuery(Category.MOVIES, country = 1199))
        assertTrue(catalog.genreCalls.isEmpty())
        filtered.selectGenre(Genre(13, "درام"))
        assertEquals(1, catalog.calls.size)

        val vm = viewModel(catalog)
        vm.selectGenre(Genre(999, "ناشناخته"))
        assertEquals(2, catalog.calls.size) // only the two initial page-1 loads
        assertNull(vm.state.value.selectedGenre)
    }

    @Test
    fun `picking the selected genre again does not reload`() {
        val catalog = FakeCatalog()
        val vm = viewModel(catalog)
        vm.selectGenre(Genre(29, "اکشن"))
        val calls = catalog.calls.size
        vm.selectGenre(Genre(29, "اکشن"))
        assertEquals(calls, catalog.calls.size)
    }

    @Test
    fun `remembers the last focused poster until the list is reloaded`() {
        val vm = viewModel(FakeCatalog())
        assertNull(vm.lastFocusedId)

        vm.onItemFocused(4, 3)
        vm.onItemFocused(9, 8)
        assertEquals(9L, vm.lastFocusedId)

        vm.selectFilter(BrowseFilter.Top250)
        assertNull(vm.lastFocusedId)
    }

    @Test
    fun `factory builds a view model that starts loading`() {
        val catalog = FakeCatalog()
        val vm = BrowseViewModel.factory(movies, "سریال", catalog).create(BrowseViewModel::class.java, CreationExtras.Empty)

        assertEquals("سریال", vm.state.value.title)
        assertEquals(listOf(movies to 1), catalog.calls)
    }
}
