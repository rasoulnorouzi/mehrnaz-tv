package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.network.dto.HomeEnvelopeDto
import app.bolbol.tv.core.network.mapper.categoryFromPath
import app.bolbol.tv.core.network.mapper.toHomeSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMappingTest {

    private val home = Samples.home()
    private fun section(id: String) = home.single { it.id == id }

    @Test
    fun emptyRowsAreHidden() {
        // Row 4 (Korean films) and row 12 (cartoons) are empty in the capture.
        assertEquals(12, home.size)
        assertTrue(home.none { it.id == "4" || it.id == "12" })
        assertTrue(home.all { it.items.isNotEmpty() })
    }

    @Test
    fun idsAreUniqueIndexStringsOfTheServerOrder() {
        assertEquals(home.size, home.map { it.id }.toSet().size)
        assertEquals(listOf("0", "1", "2", "3", "5", "6", "7", "8", "9", "10", "11", "13"), home.map { it.id })
    }

    @Test
    fun itemIdsAreUniqueInsideEachRow() {
        for (row in home) assertEquals(row.id, row.items.size, row.items.map { it.id }.toSet().size)
    }

    @Test
    fun viewAllQueriesComeFromPathAndParameters() {
        assertEquals(ListQuery(Category.MOVIES), section("0").viewAll)
        assertEquals(ListQuery(Category.MOVIES, imdbTop250 = true), section("1").viewAll)
        assertEquals(ListQuery(Category.MOVIES, country = 1199), section("2").viewAll)
        assertEquals(ListQuery(Category.MOVIES, country = 1198), section("3").viewAll)
        assertEquals(ListQuery(Category.MOVIES, genre = 13), section("5").viewAll)
        assertEquals(ListQuery(Category.MOVIES, genre = 29), section("6").viewAll)
        assertEquals(ListQuery(Category.MOVIES, genre = 379), section("7").viewAll)
        assertEquals(ListQuery(Category.MOVIES, genre = 468), section("8").viewAll)
        assertEquals(ListQuery(Category.MOVIES, genre = 520), section("9").viewAll)
        assertEquals(ListQuery(Category.SERIES, imdbTop250 = true), section("11").viewAll)
        assertEquals(ListQuery(Category.SUGGESTIONS), section("13").viewAll)
    }

    @Test
    fun newestSeriesRowDropsTheMovieGenreCopiedByTheServer() {
        // Server bug: /post/series?genres=520 where 520 is the movies sci-fi term (docs/api.md 5.1).
        assertEquals(ListQuery(Category.SERIES), section("10").viewAll)
    }

    @Test
    fun seriesRowKeepsItsOwnGenreWhenNoMovieRowUsesIt() {
        val envelope = BolboljanJson.decodeFromString(
            HomeEnvelopeDto.serializer(),
            """
            {"success":true,"data":{"sections":[
              {"key":"movies","title":"M","view_all":{"url":"https://h/wp-json/mapi/v1/post/movies","paramters":{"genres":29}},"items":[{"id":1,"title":"A"}]},
              {"key":"series","title":"S","view_all":{"url":"https://h/wp-json/mapi/v1/post/series","paramters":{"genres":108}},"items":[{"id":2,"title":"B"}]},
              {"key":"series","title":"S2","view_all":{"url":"https://h/wp-json/mapi/v1/post/series","paramters":{"genres":29}},"items":[{"id":3,"title":"C"}]}
            ]}}
            """,
        )
        val rows = envelope.toHomeSections()
        assertEquals(108, rows[1].viewAll!!.genre)
        assertNull(rows[2].viewAll!!.genre)
    }

    @Test
    fun cartoonsRowDoesNotMakeItsSeriesGenreLookLikeAMovieGenre() {
        // The cartoons row points at /post/movies with a *series* genre id; a real series row may use it.
        val envelope = BolboljanJson.decodeFromString(
            HomeEnvelopeDto.serializer(),
            """
            {"success":true,"data":{"sections":[
              {"key":"cartoons","title":"C","view_all":{"url":"https://h/wp-json/mapi/v1/post/movies","paramters":{"genres":94519}},"items":[{"id":1,"title":"A"}]},
              {"key":"series","title":"S","view_all":{"url":"https://h/wp-json/mapi/v1/post/series","paramters":{"genres":94519}},"items":[{"id":2,"title":"B"}]}
            ]}}
            """,
        )
        assertEquals(94519, envelope.toHomeSections()[1].viewAll!!.genre)
    }

    @Test
    fun itemTypeComesFromTheRowPath() {
        for (id in listOf("0", "1", "2", "3", "5", "6", "7", "8", "9")) {
            assertTrue("row $id", section(id).items.all { it.type == TitleType.MOVIE })
        }
        for (id in listOf("10", "11")) {
            assertTrue("row $id", section(id).items.all { it.type == TitleType.SERIES })
        }
        // Suggestions: unknown until details are opened.
        assertTrue(section("13").items.all { it.type == null })
    }

    @Test
    fun itemsAreCleanedAndComplete() {
        val items = home.flatMap { it.items }
        assertEquals(216, items.size)
        assertTrue(items.all { it.title.isNotBlank() })
        assertTrue(items.none { it.title.contains("&#") || it.title.contains("&amp;") })
        assertTrue(items.none { it.title.startsWith("فیلم ") || it.title.startsWith("سریال ") })
        assertTrue(items.none { it.title.startsWith("انیمه ") || it.title.startsWith("انیمیشن ") })
        assertTrue(items.all { it.posterUrl != null })
        // Home items carry no release year.
        assertTrue(items.all { it.year == null })
    }

    @Test
    fun imdbZeroPointZeroMeansNoRating() {
        val items = home.flatMap { it.items }
        assertEquals(27, items.count { it.imdbRating == null })
        assertTrue(items.mapNotNull { it.imdbRating }.all { it > 0f && it <= 10f })
    }

    @Test
    fun categoryFromPathHandlesUrlsAndKeys() {
        assertEquals(Category.MOVIES, categoryFromPath("https://euploadi.com/wp-json/mapi/v1/post/movies"))
        assertEquals(Category.SERIES, categoryFromPath("https://uuploadi.ir/wp-json/mapi/v1/post/series/"))
        assertEquals(Category.CARTOONS, categoryFromPath("https://x/wp-json/mapi/v1/post/cartoons?page=2"))
        assertEquals(Category.DOCUMENTARIES, categoryFromPath("https://x/wp-json/mapi/v1/post/documentaries"))
        assertEquals(Category.SUGGESTIONS, categoryFromPath("suggestions"))
        assertNull(categoryFromPath("https://x/wp-json/mapi/v1/post/all"))
        assertNull(categoryFromPath(""))
    }

    @Test
    fun sectionsWithoutViewAllFallBackToTheKeyForTheItemType() {
        val envelope = BolboljanJson.decodeFromString(
            HomeEnvelopeDto.serializer(),
            """{"success":true,"data":{"sections":[{"key":"series","title":"S","items":[{"id":2,"title":"B"}]}]}}""",
        )
        val row = envelope.toHomeSections().single()
        assertNull(row.viewAll)
        assertEquals(TitleType.SERIES, row.items.single().type)
    }

    @Test
    fun malformedViewAllAndParametersAreTolerated() {
        val envelope = BolboljanJson.decodeFromString(
            HomeEnvelopeDto.serializer(),
            """
            {"success":true,"data":{"sections":[
              {"key":"movies","title":"A","view_all":{"url":"https://h/wp-json/mapi/v1/post/movies","paramters":[]},"items":[{"id":1,"title":"A"}]},
              {"key":"movies","title":"B","view_all":[],"items":[{"id":2,"title":"B"}]},
              {"key":"movies","title":"C","view_all":{"url":"https://h/wp-json/mapi/v1/post/movies","paramters":{"country":"494","genres":"x"}},"items":{"1":{"id":3,"title":"C"}}}
            ]}}
            """,
        )
        val rows = envelope.toHomeSections()
        assertEquals(ListQuery(Category.MOVIES), rows[0].viewAll)
        assertNull(rows[1].viewAll)
        assertEquals(TitleType.MOVIE, rows[1].items.single().type)
        assertEquals(ListQuery(Category.MOVIES, country = 494), rows[2].viewAll)
        assertEquals("C", rows[2].items.single().title)
    }

    @Test
    fun duplicateIdsWithinARowAreDropped() {
        val envelope = BolboljanJson.decodeFromString(
            HomeEnvelopeDto.serializer(),
            """{"success":true,"data":{"sections":[{"key":"movies","title":"M","items":[{"id":1,"title":"A"},{"id":1,"title":"A"},{"id":0,"title":"zero"}]}]}}""",
        )
        assertEquals(1, envelope.toHomeSections().single().items.size)
    }
}
