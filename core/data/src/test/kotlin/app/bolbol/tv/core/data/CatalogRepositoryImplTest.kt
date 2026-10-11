package app.bolbol.tv.core.data

import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.HomeFeed
import app.bolbol.tv.core.model.ListQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRepositoryImplTest {

    private val remote = FakeRemote()
    private val cache = FakeHomeCache()
    private var now = 1_000L

    private fun TestScope.repo(cacheSize: Int = 32, ttlMs: Long = 600_000L) = CatalogRepositoryImpl(
        remote = remote,
        homeCache = cache,
        io = UnconfinedTestDispatcher(testScheduler),
        clock = { now },
        detailsCacheSize = cacheSize,
        detailsTtlMs = ttlMs,
    )

    private fun HomeFeed.ids() = sections.map { it.id }

    // --- homeFeed -----------------------------------------------------------------------------

    @Test
    fun cachedRowsComeFirstThenFreshRows() = runTest {
        cache.stored = CachedHome("rows:old", fetchedAtMs = 5)
        remote.homeJson = "rows:new1,new2"

        val feeds = repo().homeFeed().toList()

        assertEquals(2, feeds.size)
        assertTrue(feeds[0].fromCache)
        assertEquals(listOf("old"), feeds[0].ids())
        assertFalse(feeds[1].fromCache)
        assertEquals(listOf("new1", "new2"), feeds[1].ids())
    }

    @Test
    fun freshRawJsonIsStoredVerbatimWithTheFetchTime() = runTest {
        cache.stored = CachedHome("rows:old", fetchedAtMs = 5)
        remote.homeJson = "rows:new"

        repo().homeFeed().toList()

        assertEquals(CachedHome("rows:new", fetchedAtMs = 1_000L), cache.stored)
        assertEquals(1, cache.saves)
    }

    @Test
    fun withoutCacheOnlyTheFreshFeedIsEmitted() = runTest {
        val feeds = repo().homeFeed().toList()
        assertEquals(1, feeds.size)
        assertFalse(feeds.single().fromCache)
        assertEquals(1, cache.saves)
    }

    @Test
    fun networkFailureWithCacheCompletesQuietly() = runTest {
        cache.stored = CachedHome("rows:old", 5)
        remote.homeError = CatalogException.Offline()

        val feeds = repo().homeFeed().toList() // must not throw

        assertEquals(1, feeds.size)
        assertTrue(feeds.single().fromCache)
        assertEquals(CachedHome("rows:old", 5), cache.stored)
    }

    @Test
    fun networkFailureWithoutCacheThrows() = runTest {
        remote.homeError = CatalogException.AllMirrorsDown()
        try {
            repo().homeFeed().toList()
            fail("expected AllMirrorsDown")
        } catch (e: CatalogException.AllMirrorsDown) {
            // expected
        }
    }

    @Test
    fun unparsableCacheIsTreatedAsNoCache() = runTest {
        cache.stored = CachedHome("<<garbage>>", 5)
        remote.homeJson = "rows:new"

        val feeds = repo().homeFeed().toList()

        assertEquals(1, feeds.size)
        assertFalse(feeds.single().fromCache)
    }

    @Test
    fun unreadableCacheIsTreatedAsNoCache() = runTest {
        cache.failLoad = true
        val feeds = repo().homeFeed().toList()
        assertEquals(listOf(false), feeds.map { it.fromCache })
    }

    @Test
    fun badFreshPayloadKeepsTheGoodCache() = runTest {
        cache.stored = CachedHome("rows:old", 5)
        remote.homeJson = "<html>maintenance</html>"

        val feeds = repo().homeFeed().toList()

        assertEquals(listOf(true), feeds.map { it.fromCache })
        assertEquals(CachedHome("rows:old", 5), cache.stored)
    }

    @Test
    fun emptyFreshHomeIsRejectedAndNeverOverwritesTheCache() = runTest {
        cache.stored = CachedHome("rows:old", 5)
        remote.homeJson = "empty"

        val feeds = repo().homeFeed().toList()

        assertEquals(listOf(true), feeds.map { it.fromCache })
        assertEquals(CachedHome("rows:old", 5), cache.stored)
    }

    @Test
    fun emptyFreshHomeWithoutCacheIsABadResponse() = runTest {
        remote.homeJson = "empty"
        try {
            repo().homeFeed().toList()
            fail("expected BadResponse")
        } catch (e: CatalogException.BadResponse) {
            // expected
        }
    }

    @Test
    fun failingToSaveTheCacheStillDeliversFreshRows() = runTest {
        cache.failSave = true
        val feeds = repo().homeFeed().toList()
        assertEquals(listOf(false), feeds.map { it.fromCache })
    }

    @Test
    fun everyCollectionRefetches() = runTest {
        val repo = repo()
        repo.homeFeed().toList()
        repo.homeFeed().toList()
        assertEquals(2, remote.homeCalls)
    }

    // --- details ------------------------------------------------------------------------------

    @Test
    fun detailsAreServedFromMemoryOnTheSecondCall() = runTest {
        val repo = repo()
        val first = repo.details(7)
        val second = repo.details(7)
        assertEquals(first, second)
        assertEquals(1, remote.detailsCalls)
    }

    @Test
    fun detailsExpireAfterTheTtl() = runTest {
        val repo = repo(ttlMs = 1_000L)
        repo.details(7)
        now += 999
        repo.details(7)
        assertEquals(1, remote.detailsCalls)
        now += 1
        repo.details(7)
        assertEquals(2, remote.detailsCalls)
    }

    @Test
    fun theLeastRecentlyUsedDetailsAreEvictedBeyond32() = runTest {
        val repo = repo()
        for (id in 1L..32L) repo.details(id)
        repo.details(1) // touch 1 so 2 becomes the eldest
        repo.details(33) // evicts 2
        assertEquals(33, remote.detailsCalls)

        repo.details(1) // still cached
        repo.details(33)
        assertEquals(33, remote.detailsCalls)

        repo.details(2) // evicted -> refetch
        assertEquals(34, remote.detailsCalls)
    }

    @Test
    fun failedDetailsAreNotCached() = runTest {
        val repo = repo()
        remote.failingDetails += 9
        try {
            repo.details(9)
            fail("expected Offline")
        } catch (e: CatalogException.Offline) {
            // expected
        }
        remote.failingDetails.clear()
        assertNotNull(repo.details(9))
        assertEquals(2, remote.detailsCalls)
    }

    // --- list / search ------------------------------------------------------------------------

    @Test
    fun listIsPassedThrough() = runTest {
        val page = repo().list(ListQuery(Category.SERIES), page = 2)
        assertEquals(2, page.page)
        assertEquals(1, remote.listCalls)
    }

    @Test
    fun blankSearchReturnsAnEmptyPageWithoutARequest() = runTest {
        val repo = repo()
        for (text in listOf("", "   ", "\n\t")) {
            val page = repo.search(text)
            assertTrue(page.items.isEmpty())
            assertFalse(page.hasNext)
        }
        assertEquals(0, remote.searchCalls)
    }

    @Test
    fun searchIsTrimmedAndForwarded() = runTest {
        val page = repo().search("  friends ", page = 3)
        assertEquals(3, page.page)
        assertEquals(1, remote.searchCalls)
    }
}
