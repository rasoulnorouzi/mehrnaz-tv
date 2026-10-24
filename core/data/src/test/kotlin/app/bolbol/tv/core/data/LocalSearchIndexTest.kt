package app.bolbol.tv.core.data

import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.network.CatalogRemoteDataSource
import app.bolbol.tv.core.network.SearchIndexFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** v1.5: the site's daily index makes titles added after a build searchable ("Carrie 2026" was not). */
class LocalSearchIndexTest {

    @get:Rule val tmp = TemporaryFolder()

    private class IndexRemote(var titles: List<TitleSummary>) : CatalogRemoteDataSource by FakeRemote() {
        var lastModified = "Sat, 24 Oct 2026 03:19:51 GMT"
        var calls = 0
        var seenSince: String? = null
        override suspend fun fetchSearchIndex(ifModifiedSince: String?, sink: (TitleSummary) -> Unit): SearchIndexFetch {
            calls++
            seenSince = ifModifiedSince
            if (ifModifiedSince == lastModified) return SearchIndexFetch.NotModified
            titles.forEach(sink)
            return SearchIndexFetch.Updated(lastModified, titles.size)
        }
    }

    private fun t(id: Long, title: String, fa: String, year: Int?, type: TitleType = TitleType.SERIES) = TitleSummary(
        id = id, type = type, title = title, faTitle = fa, posterUrl = "https://cdn/p$id.webp", backdropUrl = null,
        year = year, imdbRating = 6.5f, hasDub = id % 2 == 0L, hasSubtitle = true,
    )

    private val catalog = listOf(
        t(624084, "Carrie", "کری", 2026),
        t(189358, "Carrie 2013", "کری 2013", 2013, TitleType.MOVIE),
        t(186892, "The Carrie Diaries", "خاطرات کری", 2013),
        t(192341, "Carriers 2009", "ناقلان مرگ", 2009, TitleType.MOVIE),
        t(624982, "Dustfall", "گرد و غبار", 2026),
    )

    private var now = 1_000_000L
    private fun index(remote: CatalogRemoteDataSource) =
        LocalSearchIndex(tmp.root, remote, Dispatchers.Unconfined, clock = { now })

    @Test fun noIndexYetMeansNull() = runBlocking {
        assertNull(index(IndexRemote(catalog)).search("carrie"))
    }

    @Test fun newTitlesAreFoundByLatinAndPersianNameAndYear() = runBlocking {
        val idx = index(IndexRemote(catalog))
        assertTrue(idx.refreshIfStale())
        val carrie = idx.search("carrie")!!
        // whole-key prefix first (newest first inside), then word-start ("The Carrie Diaries")
        assertEquals(listOf(624084L, 192341L, 189358L, 186892L), carrie.map { it.id })
        assertEquals(listOf(624084L), idx.search("carrie 2026")!!.map { it.id })
        assertEquals(624982L, idx.search("گرد")!!.single().id)
        assertEquals(listOf(624084L, 189358L, 186892L), idx.search("کری")!!.map { it.id })
        val first = carrie.first()
        assertEquals("Carrie", first.title)
        assertEquals("کری", first.faTitle)
        assertEquals(2026, first.year)
        assertEquals(TitleType.SERIES, first.type)
        assertEquals("https://cdn/p624084.webp", first.posterUrl)
    }

    @Test fun refreshIsConditionalAndThrottled() = runBlocking {
        val remote = IndexRemote(catalog)
        val idx = index(remote)
        idx.refreshIfStale()
        idx.refreshIfStale()
        assertEquals("fresh copy: no second request", 1, remote.calls)
        now += LocalSearchIndex.MAX_AGE_MS + 1
        idx.refreshIfStale()
        assertEquals(2, remote.calls)
        assertEquals("conditional request", remote.lastModified, remote.seenSince)
        assertEquals("304 keeps the old copy", 624084L, idx.search("carrie")!!.first().id)
    }

    @Test fun aChangedIndexReplacesTheOldOne() = runBlocking {
        val remote = IndexRemote(catalog)
        val idx = index(remote)
        idx.refreshIfStale()
        remote.titles = catalog + t(625200, "Brand New Show", "سریال تازه", 2026)
        remote.lastModified = "Sun, 25 Oct 2026 03:00:00 GMT"
        idx.refreshIfStale(force = true)
        assertEquals(625200L, idx.search("brand new")!!.single().id)
    }

    @Test fun anEmptyOrFailedDownloadNeverWipesAGoodIndex() = runBlocking {
        val remote = IndexRemote(catalog)
        val idx = index(remote)
        idx.refreshIfStale()
        remote.titles = emptyList()
        remote.lastModified = "changed"
        assertFalse(idx.refreshIfStale(force = true))
        assertEquals(624084L, idx.search("carrie")!!.first().id)
        val broken = LocalSearchIndex(tmp.root, object : CatalogRemoteDataSource by FakeRemote() {
            override suspend fun fetchSearchIndex(ifModifiedSince: String?, sink: (TitleSummary) -> Unit): SearchIndexFetch =
                throw java.io.IOException("offline")
        }, Dispatchers.Unconfined, clock = { now })
        assertFalse(broken.refreshIfStale(force = true))
        assertEquals(624084L, broken.search("carrie")!!.first().id)
    }

    @Test fun suggestionsUseTheScriptOfTheQuery() = runBlocking {
        val idx = index(IndexRemote(catalog))
        idx.refreshIfStale()
        assertEquals("Carrie", idx.suggest("carr", 3)!!.first())
        assertEquals("کری", idx.suggest("کر", 3)!!.first())
    }

    @Test fun repositorySearchUsesTheIndexAndFallsBackToTheApi() = runBlocking {
        val remote = IndexRemote(catalog)
        val idx = index(remote)
        val repo = CatalogRepositoryImpl(remote, FakeHomeCache(), Dispatchers.Unconfined, searchIndex = idx)
        // no index yet: the API search answers (FakeRemote)
        val beforeIndex: Page<TitleSummary> = repo.search("carrie")
        assertTrue(beforeIndex.items.none { it.id == 624084L })
        repo.refreshSearchIndex()
        val page = repo.search("carrie")
        assertEquals(624084L, page.items.first().id)
        assertEquals(1, page.totalPages)
    }

    @Test fun encodeDecodeRoundTripKeepsEveryField() {
        val title = t(1, "Tab\tTitle", "فارسی", null, TitleType.MOVIE)
        val back = LocalSearchIndex.decode(LocalSearchIndex.encode(title))!!
        assertEquals(title.copy(title = "Tab Title"), back)
    }
}
