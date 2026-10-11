package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.network.dto.ApiErrorDto
import app.bolbol.tv.core.network.dto.DetailEnvelopeDto
import app.bolbol.tv.core.network.dto.HomeEnvelopeDto
import app.bolbol.tv.core.network.dto.PagedEnvelopeDto
import app.bolbol.tv.core.network.dto.PlayerLinksEnvelopeDto
import app.bolbol.tv.core.network.mapper.toDetails
import app.bolbol.tv.core.network.mapper.toHomeSections
import app.bolbol.tv.core.network.mapper.toPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Every saved API capture must decode and map without throwing. */
class SampleParsingTest {

    @Test
    fun everySampleFileIsCoveredByAParsingTest() {
        // Guards against adding a capture to resources without a test: list must match the directory.
        val dir = java.io.File(Samples::class.java.classLoader!!.getResource("post_all.json")!!.toURI()).parentFile
        val onDisk = dir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name }.toSet()
        assertEquals(onDisk, Samples.ALL_FILES.toSet())
    }

    @Test
    fun homeParsesAndMaps() {
        val envelope = BolboljanJson.decodeFromString(HomeEnvelopeDto.serializer(), Samples.text("post_all.json"))
        assertTrue(envelope.success)
        assertEquals(14, envelope.data!!.sections.size)
        assertEquals(216, envelope.data!!.sections.sumOf { it.items.size })
        assertTrue(envelope.toHomeSections().isNotEmpty())
    }

    @Test
    fun moviesListParsesAndMaps() {
        val page = Samples.paged("post_movies.json")
        assertTrue(page.success)
        assertEquals(12, page.perPage)
        assertEquals(1, page.page)
        assertEquals(2337, page.totalPage)
        val mapped = page.toPage(1, Category.MOVIES)
        assertEquals(12, mapped.items.size)
        assertEquals(2337, mapped.totalPages)
        assertTrue(mapped.hasNext)
        assertTrue(mapped.items.all { it.type == TitleType.MOVIE })
    }

    @Test
    fun seriesListParsesAndMaps() {
        val mapped = Samples.paged("post_series.json").toPage(1, Category.SERIES)
        assertEquals(12, mapped.items.size)
        assertEquals(859, mapped.totalPages)
        assertTrue(mapped.items.all { it.type == TitleType.SERIES })
    }

    @Test
    fun suggestionsListParsesAndMaps() {
        val mapped = Samples.paged("post_suggestions.json").toPage(1, Category.SUGGESTIONS)
        assertEquals(12, mapped.items.size)
        assertEquals(22, mapped.totalPages)
        // Item `type` is present in list payloads, so it wins over the (unknown) category type.
        assertTrue(mapped.items.all { it.type != null })
    }

    /** Search returns the ordinary paged envelope with the full list-item schema, including `type`. */
    @Test
    fun searchParsesAsPagedList() {
        val dto = Samples.paged("post_search_friends.json")
        assertTrue(dto.success)
        assertEquals(5, dto.totalPage)
        val mapped = dto.toPage(1, category = null)
        assertEquals(12, mapped.items.size)
        assertEquals(5, mapped.totalPages)
        assertTrue(mapped.hasNext)
        // Mixed kinds, taken from each item's own `type`.
        assertTrue(mapped.items.any { it.type == TitleType.MOVIE })
        assertTrue(mapped.items.any { it.type == TitleType.SERIES })
        // Entity decoded and label stripped: "سریال Your Friends &#038; Neighbors".
        assertTrue(mapped.items.any { it.title == "Your Friends & Neighbors" })
        assertTrue(mapped.items.none { it.title.contains("&#") })
        assertTrue(mapped.items.all { it.posterUrl != null && it.year != null })
    }

    @Test
    fun movieDetailParsesAndMaps() {
        val envelope = BolboljanJson.decodeFromString(DetailEnvelopeDto.serializer(), Samples.text("movie_353371.json"))
        assertTrue(envelope.success)
        val details = envelope.data!!.toDetails()
        assertEquals(353371L, details.summary.id)
        assertEquals(TitleType.MOVIE, details.type)
    }

    @Test
    fun seriesDetailParsesAndMaps() {
        val envelope = BolboljanJson.decodeFromString(DetailEnvelopeDto.serializer(), Samples.text("serie_624236.json"))
        assertTrue(envelope.success)
        val details = envelope.data!!.toDetails()
        assertEquals(624236L, details.summary.id)
        assertEquals(TitleType.SERIES, details.type)
    }

    @Test
    fun dubbedSeriesSamplesParseAndMap() {
        val detail = BolboljanJson.decodeFromString(DetailEnvelopeDto.serializer(), Samples.text("serie_623954_detail_empty_links.json"))
        assertTrue(detail.success)
        assertEquals(623954L, detail.data!!.toDetails().summary.id)

        val series = BolboljanJson.decodeFromString(PlayerLinksEnvelopeDto.serializer(), Samples.text("player-links_serie_623954.json"))
        assertTrue(series.success)
        assertEquals(6, series.data.playerLinks.size)
        assertEquals(6, series.data.downloadLinks.size)

        val movie = BolboljanJson.decodeFromString(PlayerLinksEnvelopeDto.serializer(), Samples.text("player-links_movie_353371.json"))
        assertTrue(movie.success)
        assertEquals(6, movie.data.playerLinks.size)
        assertEquals(6, movie.data.downloadLinks.size)

        // The per-season endpoint is not used by the app; it only has to stay valid JSON with the documented keys.
        val season = Json.parseToJsonElement(Samples.text("serie_623954_season_1.json")).jsonObject
        assertEquals(setOf("download_links", "player_links"), season.getValue("data").jsonObject.keys)
    }

    @Test
    fun errorSamplesDecodeAsApiErrors() {
        for (name in listOf("post_player-links.json", "post_search_s_friends.json")) {
            val error = BolboljanJson.decodeFromString(ApiErrorDto.serializer(), Samples.text(name))
            assertFalse(name, error.success)
            assertTrue(name, error.message.isNotBlank())
        }
    }

    @Test
    fun errorBodyDecodedAsDetailEnvelopeHasNoData() {
        // A failed detail call must be recognisable as "no data", not crash the decoder.
        val envelope = BolboljanJson.decodeFromString(DetailEnvelopeDto.serializer(), Samples.text("post_player-links.json"))
        assertFalse(envelope.success)
        assertEquals(null, envelope.data)
    }

    @Test
    fun errorBodyDecodedAsPagedEnvelopeIsAnEmptyUnsuccessfulPage() {
        val page = BolboljanJson.decodeFromString(PagedEnvelopeDto.serializer(), Samples.text("post_search_s_friends.json"))
        assertFalse(page.success)
        assertTrue(page.data.isEmpty())
    }

    @Test
    fun parseHomeRejectsNonJsonAndAcceptsTheSample() {
        val remote = RetrofitCatalogRemote(service = NoService)
        assertNotNull(remote.parseHome(Samples.text("post_all.json")))
        try {
            remote.parseHome("<html>maintenance</html>")
            fail("expected BadResponse")
        } catch (e: CatalogException.BadResponse) {
            // expected
        }
        try {
            remote.parseHome(Samples.text("post_player-links.json"))
            fail("expected BadResponse for an error envelope")
        } catch (e: CatalogException.BadResponse) {
            // expected
        }
    }
}

/** A service that must never be called (parse-only tests). */
internal object NoService : BolboljanService {
    override suspend fun home() = error("unused")
    override suspend fun list(category: String, page: Int, country: Int?, genres: Int?, imdbTop250: Int?) = error("unused")
    override suspend fun search(text: String, page: Int) = error("unused")
    override suspend fun detail(id: Long) = error("unused")
    override suspend fun playerLinks(id: Long) = error("unused")
}
