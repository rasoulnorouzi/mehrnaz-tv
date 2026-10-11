package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.TitleType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    // --- details: `post/{id}` and `post/player-links?id=` are requested together ------------------------------

    private val apiRoot = "/wp-json/mapi/v1/post"

    /** Serves fixed answers by path (the two details calls arrive in parallel, so enqueue order is meaningless). */
    private fun serve(server: MockWebServer, vararg routes: Pair<String, () -> MockResponse>) {
        val table = routes.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                table[request.requestUrl!!.encodedPath]?.invoke() ?: json("""{"success":false}""", code = 404)
        }
    }

    private fun sample(name: String, code: Int = 200): () -> MockResponse = { json(Samples.text(name), code) }

    @Test
    fun detailsMapsTheMovieSample() = runTest {
        serve(first, "$apiRoot/353371" to sample("movie_353371.json"), "$apiRoot/player-links" to sample("player-links_movie_353371.json"))
        val details = remote.details(353371)
        assertEquals(TitleType.MOVIE, details.type)
        assertEquals(6, details.streams.size)
        assertEquals(3, details.streams.count { it.audio == AudioType.SUBTITLED && it.subtitleUrl != null })
        assertEquals(3, details.streams.count { it.audio == AudioType.DUBBED && it.subtitleUrl == null })
        val paths = List(first.requestCount) { first.takeRequest().requestUrl!! }
        assertEquals(setOf("$apiRoot/353371", "$apiRoot/player-links"), paths.map { it.encodedPath }.toSet())
        assertEquals("353371", paths.single { it.encodedPath.endsWith("player-links") }.queryParameter("id"))
    }

    @Test
    fun detailsMapsTheSeriesSample() = runTest {
        serve(first, "$apiRoot/624236" to sample("serie_624236.json"))
        val details = remote.details(624236) // player-links 404: the embedded links are used
        assertEquals(TitleType.SERIES, details.type)
        assertEquals(2, details.seasons.single().episodes.size)
    }

    @Test
    fun streamsComeFromPlayerLinksWhenTheDetailHasEmptyLinks() = runTest {
        serve(first, "$apiRoot/623954" to { json(Samples.detailWithoutLinks("serie_623954_detail_empty_links.json")) }, "$apiRoot/player-links" to sample("player-links_serie_623954.json"))
        val details = remote.details(623954)
        assertEquals(TitleType.SERIES, details.type)
        assertEquals("Kill Jackie", details.summary.title)
        val episodes = details.seasons.single().episodes
        assertEquals(8, episodes.size)
        assertEquals(6, episodes[0].streams.size)
        assertEquals(3, episodes[7].streams.size)
        assertTrue(details.summary.hasDub && details.summary.hasSubtitle)
    }

    @Test
    fun streamsFallBackToTheEmbeddedLinksWhenPlayerLinksFails() = runTest {
        val detail = "$apiRoot/623954" to sample("serie_623954_detail_empty_links.json")
        // The HTML 500 case fails over to the second mirror (and keeps using it), so it must serve the detail too.
        serve(second, detail, "$apiRoot/player-links" to sample("post_player-links.json", 404))
        for (failure in listOf<() -> MockResponse>(sample("post_player-links.json", 404), { html(500) }, { json("""{"success":true,"data":[]}""") }, { json("not json") })) {
            serve(first, detail, "$apiRoot/player-links" to failure)
            val details = remote.details(623954)
            assertEquals(8, details.seasons.single().episodes.size)
        }
    }

    @Test
    fun aFailingPlayerLinksCallNeverFailsTheDetails() = runTest {
        serve(first, "$apiRoot/353371" to sample("movie_353371.json"), "$apiRoot/player-links" to { json("""{"success":true,"data":{"player_links":"oops"}}""") })
        assertEquals(6, remote.details(353371).streams.size)
    }

    @Test
    fun theTwoCallsAreInFlightAtTheSameTime() = runTest {
        val bothArrived = CountDownLatch(2)
        val sawBoth = AtomicBoolean(false)
        first.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                bothArrived.countDown()
                if (bothArrived.await(1500, TimeUnit.MILLISECONDS)) sawBoth.set(true)
                return when (request.requestUrl!!.encodedPath) {
                    "$apiRoot/353371" -> sample("movie_353371.json")()
                    else -> sample("player-links_movie_353371.json")()
                }
            }
        }
        remote.details(353371)
        assertTrue("the detail and player-links requests must overlap", sawBoth.get())
    }

    @Test
    fun details404IsNotFoundAndDoesNotFailOver() = runTest {
        serve(first, "$apiRoot/player-links" to sample("player-links_movie_353371.json"))
        try {
            remote.details(1) // `post/1` answers 404 even though player-links has links
            fail("expected NotFound")
        } catch (e: CatalogException.NotFound) {
            // expected
        }
        assertEquals(0, second.requestCount)
    }

    @Test
    fun detailsWithSuccessFalseOn200IsNotFound() = runTest {
        serve(first, "$apiRoot/1" to { json("""{"success":false,"message":"x"}""") })
        try {
            remote.details(1)
            fail("expected NotFound")
        } catch (e: CatalogException.NotFound) {
            // expected
        }
    }

    @Test
    fun detailsWithGarbageBodyIsBadResponse() = runTest {
        serve(first, "$apiRoot/1" to { json("""{"success":true,"data":"surprise"}""") })
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
