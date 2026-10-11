package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.TitleType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The whole network stack (Retrofit + converter + mirror interceptor + mappers) against MockWebServer. */
class RetrofitCatalogRemoteTest {

    private lateinit var first: MockWebServer
    private lateinit var second: MockWebServer
    private lateinit var remote: RetrofitCatalogRemote

    @Before
    fun setUp() {
        first = MockWebServer().apply { start() }
        second = MockWebServer().apply { start() }
        val mirrors = MirrorList(listOf(first.url("/").toString(), second.url("/").toString()))
        val client = OkHttpClient.Builder()
            .addInterceptor(MirrorInterceptor(mirrors))
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        remote = RetrofitCatalogRemote(createService(client, mirrors))
    }

    @After
    fun tearDown() {
        runCatching { first.shutdown() }
        runCatching { second.shutdown() }
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=UTF-8").setBody(body)

    private fun html(code: Int = 503) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "text/html").setBody("<html>down</html>")

    @Test
    fun homeJsonIsReturnedVerbatimAndParses() = runTest {
        val body = Samples.text("post_all.json")
        first.enqueue(json(body))
        val raw = remote.fetchHomeJson()
        assertEquals(body, raw)
        assertEquals(12, remote.parseHome(raw).size)
        assertEquals("/wp-json/mapi/v1/post/all", first.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun homeFailsOverWhenTheFirstMirrorReturnsHtml() = runTest {
        first.enqueue(html(200))
        second.enqueue(json(Samples.text("post_all.json")))
        assertEquals(12, remote.parseHome(remote.fetchHomeJson()).size)
        assertEquals(1, first.requestCount)
        assertEquals(1, second.requestCount)
    }

    @Test
    fun detailsMapsTheMovieSample() = runTest {
        first.enqueue(json(Samples.text("movie_353371.json")))
        val details = remote.details(353371)
        assertEquals(TitleType.MOVIE, details.type)
        assertEquals(6, details.streams.size)
        assertEquals("/wp-json/mapi/v1/post/353371", first.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun detailsMapsTheSeriesSample() = runTest {
        first.enqueue(json(Samples.text("serie_624236.json")))
        val details = remote.details(624236)
        assertEquals(TitleType.SERIES, details.type)
        assertEquals(2, details.seasons.single().episodes.size)
    }

    @Test
    fun details404IsNotFoundAndDoesNotFailOver() = runTest {
        first.enqueue(json(Samples.text("post_player-links.json"), code = 404))
        try {
            remote.details(1)
            fail("expected NotFound")
        } catch (e: CatalogException.NotFound) {
            // expected
        }
        assertEquals(0, second.requestCount)
    }

    @Test
    fun detailsWithSuccessFalseOn200IsNotFound() = runTest {
        first.enqueue(json("""{"success":false,"message":"x"}"""))
        try {
            remote.details(1)
            fail("expected NotFound")
        } catch (e: CatalogException.NotFound) {
            // expected
        }
    }

    @Test
    fun detailsWithGarbageBodyIsBadResponse() = runTest {
        first.enqueue(json("""{"success":true,"data":"surprise"}"""))
        try {
            remote.details(1)
            fail("expected BadResponse")
        } catch (e: CatalogException.BadResponse) {
            // expected
        }
    }

    @Test
    fun listSendsTheQueryParametersAndMapsThePage() = runTest {
        first.enqueue(json(Samples.text("post_movies.json")))
        val page = remote.list(ListQuery(Category.MOVIES, country = 1199, genre = 13, imdbTop250 = true), page = 3)
        val url = first.takeRequest().requestUrl!!
        assertEquals("/wp-json/mapi/v1/post/movies", url.encodedPath)
        assertEquals("3", url.queryParameter("page"))
        assertEquals("1199", url.queryParameter("country"))
        assertEquals("13", url.queryParameter("genres"))
        assertEquals("1", url.queryParameter("imdbtop250"))
        assertEquals(12, page.items.size)
        assertEquals(1, page.page) // the server's own page number wins
        assertEquals(2337, page.totalPages)
    }

    @Test
    fun listOmitsFiltersThatAreNotSet() = runTest {
        first.enqueue(json(Samples.text("post_series.json")))
        remote.list(ListQuery(Category.SERIES), page = 1)
        val url = first.takeRequest().requestUrl!!
        assertEquals("/wp-json/mapi/v1/post/series", url.encodedPath)
        assertEquals(setOf("page"), url.queryParameterNames)
    }

    @Test
    fun listUsesTheCategoryPath() = runTest {
        for ((category, path) in listOf(
            Category.CARTOONS to "cartoons",
            Category.DOCUMENTARIES to "documentaries",
            Category.SUGGESTIONS to "suggestions",
        )) {
            first.enqueue(json(Samples.text("post_suggestions.json")))
            remote.list(ListQuery(category), page = 1)
            assertEquals("/wp-json/mapi/v1/post/$path", first.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test
    fun listPastTheEndOr404IsAnEmptyPage() = runTest {
        first.enqueue(json(Samples.text("post_player-links.json"), code = 404))
        val page = remote.list(ListQuery(Category.MOVIES), page = 99999)
        assertTrue(page.items.isEmpty())
        assertEquals(false, page.hasNext)
    }

    @Test
    fun searchEncodesPersianTextAndMapsThePage() = runTest {
        first.enqueue(json(Samples.text("post_search_friends.json")))
        val page = remote.search("دوستان", page = 2)
        val url = first.takeRequest().requestUrl!!
        assertEquals("/wp-json/mapi/v1/post/search", url.encodedPath)
        assertEquals("دوستان", url.queryParameter("search"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals(12, page.items.size)
    }

    @Test
    fun blankSearchMakesNoRequest() = runTest {
        val page = remote.search("   ", page = 1)
        assertTrue(page.items.isEmpty())
        assertEquals(0, first.requestCount)
        assertEquals(0, second.requestCount)
    }

    @Test
    fun searchWithoutResultsIs404OrEmptyAndBecomesAnEmptyPage() = runTest {
        first.enqueue(json(Samples.text("post_player-links.json"), code = 404))
        assertTrue(remote.search("zzzz").items.isEmpty())
        first.enqueue(json("""{"success":true,"per_page":12,"page":1,"total_page":0,"data":[]}"""))
        assertTrue(remote.search("zzzz").items.isEmpty())
    }

    @Test
    fun allMirrorsErroringIsAllMirrorsDown() = runTest {
        first.enqueue(html(503))
        second.enqueue(html(502))
        try {
            remote.fetchHomeJson()
            fail("expected AllMirrorsDown")
        } catch (e: CatalogException.AllMirrorsDown) {
            // expected
        }
    }

    @Test
    fun noMirrorReachableIsOffline() = runTest {
        first.shutdown()
        second.shutdown()
        try {
            remote.details(1)
            fail("expected Offline")
        } catch (e: CatalogException.Offline) {
            // expected
        }
    }
}
