package app.bolbol.tv.feature.browse

import app.bolbol.tv.core.model.CatalogFacets
import app.bolbol.tv.core.model.CatalogFilter
import app.bolbol.tv.core.model.CatalogRepository
import app.bolbol.tv.core.model.CatalogSort
import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.HomeFeed
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrowseLocalPureTest {
    private val facets = CatalogFacets(
        genres = listOf("اکشن", "درام"),
        countries = listOf("کره جنوبی", "ایران"),
        decades = listOf(2020, 2010, CatalogFilter.OLDER),
        dubbedCount = 10,
        total = 100,
    )

    @Test
    fun `decade and sort labels use persian digits`() {
        assertEquals("دههٔ ۲۰۲۰", decadeLabel(2020))
        assertEquals("قدیمی‌تر", decadeLabel(CatalogFilter.OLDER))
        assertEquals("تازه‌ترین‌ها", sortLabel(CatalogSort.NEWEST_ADDED))
        assertEquals("بهترین IMDb", sortLabel(CatalogSort.TOP_IMDB))
        assertEquals("جدیدترین سال", sortLabel(CatalogSort.NEWEST_YEAR))
    }

    @Test
    fun `button labels show the chosen value`() {
        val f = CatalogFilter(country = "کره جنوبی", decade = 2020)
        assertEquals("ژانر: همه", f.buttonLabel(FilterKind.Genre))
        assertEquals("کشور: کره جنوبی", f.buttonLabel(FilterKind.Country))
        assertEquals("سال: دههٔ ۲۰۲۰", f.buttonLabel(FilterKind.Decade))
        assertEquals("مرتب‌سازی: تازه‌ترین‌ها", f.buttonLabel(FilterKind.Sort))
    }

    @Test
    fun `picker options start with all and keep facet order`() {
        assertEquals(listOf("همه", "اکشن", "درام"), pickerOptions(FilterKind.Genre, facets).map { it.label })
        assertEquals(listOf("همه", "کره جنوبی", "ایران"), pickerOptions(FilterKind.Country, facets).map { it.label })
        assertEquals(listOf("همه", "دههٔ ۲۰۲۰", "دههٔ ۲۰۱۰", "قدیمی‌تر"), pickerOptions(FilterKind.Decade, facets).map { it.label })
        assertEquals(listOf("تازه‌ترین‌ها", "بهترین IMDb", "جدیدترین سال"), pickerOptions(FilterKind.Sort, facets).map { it.label })
        assertEquals(listOf("همه"), pickerOptions(FilterKind.Genre, null).map { it.label })
    }

    @Test
    fun `picker focus starts on the current value`() {
        val options = pickerOptions(FilterKind.Country, facets)
        assertEquals(0, CatalogFilter().selectedIndex(FilterKind.Country, options))
        assertEquals(2, CatalogFilter(country = "ایران").selectedIndex(FilterKind.Country, options))
        val sorts = pickerOptions(FilterKind.Sort, facets)
        assertEquals(1, CatalogFilter(sort = CatalogSort.TOP_IMDB).selectedIndex(FilterKind.Sort, sorts))
        val decades = pickerOptions(FilterKind.Decade, facets)
        assertEquals(3, CatalogFilter(decade = CatalogFilter.OLDER).selectedIndex(FilterKind.Decade, decades))
    }

    @Test
    fun `withOption edits one field and all is null`() {
        val f = CatalogFilter(genre = "درام", dubbedOnly = true)
        val withCountry = f.withOption(FilterKind.Country, PickerOption("ایران", text = "ایران"))
        assertEquals(CatalogFilter(genre = "درام", country = "ایران", dubbedOnly = true), withCountry)
        assertNull(withCountry.withOption(FilterKind.Genre, PickerOption(LABEL_ALL)).genre)
        assertEquals(2020, f.withOption(FilterKind.Decade, PickerOption("x", number = 2020)).decade)
        assertEquals(CatalogSort.TOP_IMDB, f.withOption(FilterKind.Sort, PickerOption("x", sort = CatalogSort.TOP_IMDB)).sort)
    }

    @Test
    fun `clear keeps the type only and isActive notices every field`() {
        val f = CatalogFilter(type = TitleType.SERIES, genre = "درام", dubbedOnly = true, sort = CatalogSort.TOP_IMDB)
        assertEquals(CatalogFilter(type = TitleType.SERIES), f.cleared())
        assertFalse(CatalogFilter(type = TitleType.SERIES).isActive())
        assertTrue(f.isActive())
        assertTrue(CatalogFilter(sort = CatalogSort.NEWEST_YEAR).isActive())
        assertTrue(CatalogFilter(dubbedOnly = true).isActive())
    }

    @Test
    fun `summary lists active filters`() {
        assertEquals("", CatalogFilter().summary())
        assertEquals(
            "درام · کره جنوبی · دههٔ ۲۰۲۰ · دوبله فارسی · بهترین IMDb",
            CatalogFilter(genre = "درام", country = "کره جنوبی", decade = 2020, dubbedOnly = true, sort = CatalogSort.TOP_IMDB).summary(),
        )
    }

    @Test
    fun `count label is approximate across pages and exact on one`() {
        assertEquals("حدود ۷۲ عنوان", localCountLabel(BrowsePhase.Content, 3, 24))
        assertEquals("۷ عنوان", localCountLabel(BrowsePhase.Content, 1, 7))
        assertEquals("", localCountLabel(BrowsePhase.Loading, 0, 0))
    }

    @Test
    fun `route string round trips`() {
        val f = CatalogFilter(
            type = TitleType.MOVIE, genre = "علمی تخیلی", country = "کره جنوبی", dubbedOnly = true,
            decade = 2010, minImdb = 7.5f, addedWithinDays = 7, sort = CatalogSort.NEWEST_YEAR,
        )
        assertEquals(f, catalogFilterFromRoute(f.toRouteString()))
        assertNull(CatalogFilter().toRouteString())
        assertNull(catalogFilterFromRoute(null))
        assertNull(catalogFilterFromRoute(""))
        assertEquals(CatalogFilter(dubbedOnly = true), catalogFilterFromRoute("d=1"))
    }
}

private class LocalFakeCatalog(
    var indexed: Boolean = true,
    private val perPage: Int = 24,
    var totalPages: Int = 2,
) : CatalogRepository {
    val browseCalls = mutableListOf<Pair<CatalogFilter, Int>>()
    val listCalls = mutableListOf<Pair<ListQuery, Int>>()
    var facetCalls = 0
    var emptyFor: ((CatalogFilter) -> Boolean) = { false }
    val facetData = CatalogFacets(listOf("درام"), listOf("ایران"), listOf(2020), 5, 50)

    override suspend fun browse(filter: CatalogFilter, page: Int): Page<TitleSummary>? {
        browseCalls += filter to page
        if (!indexed) return null
        if (emptyFor(filter)) return Page(emptyList(), page, 0)
        val start = (page - 1) * perPage + 1
        return Page((start until start + perPage).map { title(it.toLong()) }, page, totalPages)
    }

    override suspend fun facets(type: TitleType): CatalogFacets? {
        facetCalls++
        return if (indexed) facetData else null
    }

    override suspend fun list(query: ListQuery, page: Int): Page<TitleSummary> {
        listCalls += query to page
        return Page((1..12).map { title(it.toLong()) }, page, 3)
    }

    override fun homeFeed(): Flow<HomeFeed> = error("unused")
    override suspend fun details(id: Long): TitleDetails = error("unused")
    override suspend fun search(text: String, page: Int): Page<TitleSummary> = error("unused")
}

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseLocalViewModelTest {
    private val movies = ListQuery(Category.MOVIES)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(c: LocalFakeCatalog, type: TitleType? = TitleType.MOVIE, initial: CatalogFilter? = null) =
        BrowseViewModel(movies, "فیلم‌ها", c, type, initial)

    @Test
    fun `tab with a type browses the local index and loads facets`() {
        val c = LocalFakeCatalog()
        val vm = vm(c)
        val s = vm.state.value
        assertEquals(BrowsePhase.Content, s.phase)
        assertEquals(CatalogFilter(type = TitleType.MOVIE), s.filter)
        assertEquals(listOf(CatalogFilter(type = TitleType.MOVIE) to 1), c.browseCalls)
        assertTrue(c.listCalls.isEmpty())
        assertEquals(c.facetData, s.facets)
        assertTrue(s.filters.isEmpty())
    }

    @Test
    fun `initial filter is used and type forced from the tab`() {
        val c = LocalFakeCatalog()
        val vm = vm(c, initial = CatalogFilter(dubbedOnly = true, addedWithinDays = 7))
        assertEquals(CatalogFilter(type = TitleType.MOVIE, dubbedOnly = true, addedWithinDays = 7), c.browseCalls.single().first)
        assertEquals(CatalogFilter(type = TitleType.MOVIE, dubbedOnly = true, addedWithinDays = 7), vm.state.value.filter)
    }

    @Test
    fun `initial filter without type still goes local but loads no facets`() {
        val c = LocalFakeCatalog()
        val vm = vm(c, type = null, initial = CatalogFilter(minImdb = 7f))
        assertEquals(BrowsePhase.Content, vm.state.value.phase)
        assertEquals(0, c.facetCalls)
        assertNull(vm.state.value.facets)
    }

    @Test
    fun `no type and no filter stays on the api page`() {
        val c = LocalFakeCatalog()
        val vm = vm(c, type = null)
        assertNull(vm.state.value.filter)
        assertTrue(c.browseCalls.isEmpty())
        assertEquals(listOf(movies to 1), c.listCalls)
        assertFalse(vm.state.value.filters.isEmpty())
    }

    @Test
    fun `missing index falls back to the api page with chips`() {
        val c = LocalFakeCatalog(indexed = false)
        val vm = vm(c)
        val s = vm.state.value
        assertNull(s.filter)
        assertEquals(BrowsePhase.Content, s.phase)
        assertEquals(listOf(movies to 1), c.listCalls)
        assertEquals(quickFiltersFor(movies), s.filters)
        assertEquals(BrowseFilter.All, s.selected)
        // the old chips work again
        vm.selectFilter(BrowseFilter.Top250)
        assertEquals(movies.copy(imdbTop250 = true), c.listCalls.last().first)
    }

    @Test
    fun `changing a filter reloads from page one with the new query`() {
        val c = LocalFakeCatalog()
        val vm = vm(c)
        vm.pick(FilterKind.Country, PickerOption("ایران", text = "ایران"))
        assertEquals(CatalogFilter(type = TitleType.MOVIE, country = "ایران"), c.browseCalls.last().first)
        assertEquals(1, c.browseCalls.last().second)
        assertTrue(vm.reloadFromChip)
        vm.toggleDubbed()
        assertEquals(CatalogFilter(type = TitleType.MOVIE, country = "ایران", dubbedOnly = true), c.browseCalls.last().first)
        vm.pick(FilterKind.Sort, PickerOption("x", sort = CatalogSort.TOP_IMDB))
        assertEquals(CatalogSort.TOP_IMDB, c.browseCalls.last().first.sort)
        assertEquals(1, vm.state.value.page)
    }

    @Test
    fun `picking the same value does not reload`() {
        val c = LocalFakeCatalog()
        val vm = vm(c)
        val calls = c.browseCalls.size
        vm.pick(FilterKind.Genre, PickerOption(LABEL_ALL))
        assertEquals(calls, c.browseCalls.size)
    }

    @Test
    fun `empty result shows the empty phase and clear brings the grid back`() {
        val c = LocalFakeCatalog()
        c.emptyFor = { it.country == "ایران" }
        val vm = vm(c)
        vm.pick(FilterKind.Country, PickerOption("ایران", text = "ایران"))
        assertEquals(BrowsePhase.Empty, vm.state.value.phase)
        assertTrue(vm.state.value.filter!!.isActive())
        vm.clearFilters()
        assertEquals(BrowsePhase.Content, vm.state.value.phase)
        assertEquals(CatalogFilter(type = TitleType.MOVIE), vm.state.value.filter)
    }

    @Test
    fun `later pages use the current filter and the facets survive a reload`() {
        val c = LocalFakeCatalog()
        val vm = vm(c)
        vm.toggleDubbed()
        vm.onItemFocused(id = 20, index = 19)
        assertEquals(2, c.browseCalls.last().second)
        assertTrue(c.browseCalls.last().first.dubbedOnly)
        assertEquals(48, vm.state.value.items.size)
        assertNotNull(vm.state.value.facets)
        assertEquals(1, c.facetCalls)
    }

    @Test
    fun `api chips are ignored in local mode`() {
        val c = LocalFakeCatalog()
        val vm = vm(c)
        vm.selectFilter(BrowseFilter.Top250)
        assertTrue(c.listCalls.isEmpty())
    }
}
