package app.bolbol.tv.core.data

import app.bolbol.tv.core.model.CatalogFilter
import app.bolbol.tv.core.model.CatalogSort
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.network.CatalogRemoteDataSource
import app.bolbol.tv.core.network.IndexedTitle
import app.bolbol.tv.core.network.SearchIndexFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** v1.6: combinable filters, facets and Home rows from the on-device index. */
class CatalogBrowseTest {

    @get:Rule val tmp = TemporaryFolder()

    private val day = 86_400L
    private val nowMs = 1_800_000_000_000L
    private val nowSec = nowMs / 1000

    private fun e(
        id: Long, type: TitleType, year: Int, imdb: Float, genres: List<String>, countries: List<String>,
        dub: Boolean, addedDaysAgo: Long,
    ) = IndexedTitle(
        TitleSummary(id, type, "T$id", "ت$id", null, null, year, imdb, dub, true),
        genres, countries, nowSec - addedDaysAgo * day,
    )

    private val catalog = listOf(
        e(1, TitleType.SERIES, 2026, 7.6f, listOf("درام", "ترسناک"), listOf("آمریکا"), dub = false, addedDaysAgo = 1),
        e(2, TitleType.SERIES, 2024, 8.1f, listOf("درام", "عاشقانه"), listOf("کره جنوبی"), dub = true, addedDaysAgo = 3),
        e(3, TitleType.SERIES, 2019, 7.0f, listOf("عاشقانه"), listOf("کره جنوبی"), dub = true, addedDaysAgo = 30),
        e(4, TitleType.MOVIE, 2025, 6.9f, listOf("اکشن"), listOf("ایران"), dub = true, addedDaysAgo = 2),
        e(5, TitleType.MOVIE, 1975, 8.5f, listOf("درام"), listOf("ایتالیا"), dub = false, addedDaysAgo = 400),
        e(6, TitleType.MOVIE, 2012, 5.0f, listOf("کمدی"), listOf("ترکیه"), dub = true, addedDaysAgo = 10),
    ) + (10L..20L).map { e(it, TitleType.SERIES, 2023, 7.2f, listOf("درام"), listOf("کره جنوبی"), dub = true, addedDaysAgo = 50) }

    private fun repo(): CatalogRepositoryImpl {
        val remote = object : CatalogRemoteDataSource by FakeRemote() {
            override suspend fun fetchSearchIndex(ifModifiedSince: String?, sink: (IndexedTitle) -> Unit): SearchIndexFetch {
                catalog.forEach(sink)
                return SearchIndexFetch.Updated("LM", catalog.size)
            }
        }
        val index = LocalSearchIndex(tmp.root, remote, Dispatchers.Unconfined, clock = { nowMs })
        return CatalogRepositoryImpl(remote, FakeHomeCache(), Dispatchers.Unconfined, clock = { nowMs }, searchIndex = index)
    }

    @Test fun noIndexMeansNullSoTheUiFallsBackToTheApi() = runBlocking {
        val r = repo()
        assertNull(r.browse(CatalogFilter(), 1))
        assertNull(r.facets(TitleType.MOVIE))
    }

    @Test fun filtersCombineAndSort() = runBlocking {
        val r = repo()
        r.refreshSearchIndex()
        val dubbedKoreanRomance = r.browse(
            CatalogFilter(type = TitleType.SERIES, dubbedOnly = true, country = "کره جنوبی", genre = "عاشقانه"), 1,
        )!!
        assertEquals(listOf(2L, 3L), dubbedKoreanRomance.items.map { it.id }) // newest added first
        assertEquals(listOf(5L, 4L, 6L), r.browse(CatalogFilter(type = TitleType.MOVIE, sort = CatalogSort.TOP_IMDB), 1)!!.items.map { it.id })
        assertEquals(listOf(4L, 6L, 5L), r.browse(CatalogFilter(type = TitleType.MOVIE, sort = CatalogSort.NEWEST_YEAR), 1)!!.items.map { it.id })
        assertEquals(listOf(5L), r.browse(CatalogFilter(decade = CatalogFilter.OLDER), 1)!!.items.map { it.id })
        assertEquals(listOf(1L, 4L, 2L), r.browse(CatalogFilter(addedWithinDays = 7), 1)!!.items.map { it.id })
    }

    @Test fun pagingIs24PerPage() = runBlocking {
        val r = repo()
        r.refreshSearchIndex()
        val first = r.browse(CatalogFilter(), 1)!!
        assertEquals(17, first.items.size)
        assertEquals(1, first.totalPages) // 17 titles
        assertTrue(r.browse(CatalogFilter(), 2)!!.items.isEmpty())
    }

    @Test fun facetsAreMostCommonFirst() = runBlocking {
        val r = repo()
        r.refreshSearchIndex()
        val series = r.facets(TitleType.SERIES)!!
        assertEquals("درام", series.genres.first())
        assertEquals("کره جنوبی", series.countries.first())
        assertEquals(listOf(2020, 2010), series.decades)
        assertEquals(13, series.dubbedCount)
    }

    @Test fun homeRowsRecommendNewThisWeekDubbedAndTopGenre() = runBlocking {
        val r = repo()
        r.refreshSearchIndex()
        val rows = r.localHomeRows(watchedIds = listOf(2L)).associateBy { it.id }
        val forYou = rows.getValue("local-for-you")
        assertEquals("چون «ت2» را دیدید", forYou.title)
        assertTrue("never recommends what was watched", forYou.items.none { it.id == 2L })
        // same score (shared genre + Korea + series): higher IMDb first; the lone comedy (6) is never recommended
        assertTrue(3L in forYou.items.map { it.id })
        assertTrue(forYou.items.none { it.id == 6L || it.id == 4L })
        assertEquals(listOf(1L, 4L, 2L), rows.getValue("local-week").items.map { it.id })
        assertEquals(2L, rows.getValue("local-dubbed").items.first().id)
    }

    @Test fun noHistoryMeansNoForYouRow() = runBlocking {
        val r = repo()
        r.refreshSearchIndex()
        assertTrue(r.localHomeRows(emptyList()).none { it.id == "local-for-you" })
    }
}
