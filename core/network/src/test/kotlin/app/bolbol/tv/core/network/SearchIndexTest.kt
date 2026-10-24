package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** v1.5: the site's daily `search-data.json`, streamed through the real stack (sample: research/webapp/api/search-data-sample.json). */
class SearchIndexTest {

    private lateinit var first: MockWebServer
    private lateinit var second: MockWebServer
    private lateinit var mirrors: MirrorList
    private lateinit var remote: RetrofitCatalogRemote

    private val sample: String = File("../../research/webapp/api/search-data-sample.json").readText()

    @Before
    fun setUp() {
        first = MockWebServer().apply { start() }
        second = MockWebServer().apply { start() }
        mirrors = MirrorList(listOf(first.url("/").toString(), second.url("/").toString()))
        val images = MirrorList(listOf("https://cdn.example/"))
        val client = OkHttpClient.Builder()
            .addInterceptor(MirrorInterceptor(mirrors))
            .addInterceptor(SearchIndexInterceptor(mirrors))
            .addInterceptor(ImageCdnInterceptor(images))
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

    private fun collect(since: String? = null): Pair<SearchIndexFetch, List<TitleSummary>> {
        val out = ArrayList<TitleSummary>()
        val result = kotlinx.coroutines.runBlocking { remote.fetchSearchIndex(since) { out += it } }
        return result to out
    }

    @Test
    fun streamsEveryEntryWithTitleTypeYearAndPoster() = runTest {
        first.enqueue(MockResponse().setHeader("Content-Type", "application/json").setHeader("Last-Modified", "LM1").setBody(sample))
        val (result, titles) = collect()
        assertEquals(SearchIndexFetch.Updated("LM1", 5), result)
        val carrie = titles.single { it.id == 624084L }
        assertEquals("Carrie", carrie.title)
        assertEquals("کری", carrie.faTitle)
        assertEquals(TitleType.SERIES, carrie.type)
        assertEquals(2026, carrie.year)
        assertTrue(carrie.posterUrl!!.endsWith(".webp"))
        assertEquals(TitleType.MOVIE, titles.single { it.id == 189358L }.type)
        val request = first.takeRequest()
        assertEquals("/wp-content/uploads/search-data.json", request.path)
        assertEquals("no-store", request.getHeader("Cache-Control"))
    }

    @Test
    fun notModifiedSendsTheConditionalHeaderAndYieldsNothing() = runTest {
        first.enqueue(MockResponse().setResponseCode(304))
        val (result, titles) = collect(since = "LM1")
        assertEquals(SearchIndexFetch.NotModified, result)
        assertTrue(titles.isEmpty())
        assertEquals("LM1", first.takeRequest().getHeader("If-Modified-Since"))
    }

    @Test
    fun aBrokenMirrorFailsOverToTheNextOneNotToTheImageCdn() = runTest {
        first.enqueue(MockResponse().setResponseCode(404))
        second.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(sample))
        val (result, titles) = collect()
        assertTrue(result is SearchIndexFetch.Updated)
        assertEquals(5, titles.size)
    }

    @Test
    fun aRedirectToANewDomainIsLearnedAsTheFirstMirror() = runTest {
        val moved = MockWebServer().apply { start() }
        try {
            moved.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true,"page":1,"total_page":1,"data":[]}"""))
            first.enqueue(MockResponse().setResponseCode(301).setHeader("Location", moved.url("/wp-json/mapi/v1/post/search?search=x")))
            var learned: List<String>? = null
            mirrors.onLearned = { learned = it }
            remote.search("x", 1)
            assertEquals(moved.url("/").host, mirrors.primary().host)
            assertEquals(moved.url("/").port, mirrors.primary().port)
            assertEquals(3, learned!!.size)
        } finally {
            moved.shutdown()
        }
    }
}
