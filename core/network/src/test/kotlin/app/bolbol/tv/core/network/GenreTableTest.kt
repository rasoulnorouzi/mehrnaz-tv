package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.Genre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreTableTest {
    private val movies = GenreTable.forCategory(Category.MOVIES)
    private val series = GenreTable.forCategory(Category.SERIES)

    private fun id(table: List<Genre>, name: String) = table.first { it.name == name }.id

    @Test
    fun `tables have researched sizes and most useful genres first`() {
        assertEquals(23, movies.size)
        assertEquals(17, series.size)
        assertEquals(listOf("اکشن", "درام", "کمدی", "جنایی"), movies.take(4).map { it.name })
        assertEquals(listOf("اکشن", "درام", "کمدی", "جنایی"), series.take(4).map { it.name })
    }

    @Test
    fun `ids and names are unique inside a kind and never shared between kinds`() {
        for (table in listOf(movies, series)) {
            assertEquals(table.size, table.map { it.id }.toSet().size)
            assertEquals(table.size, table.map { it.name }.toSet().size)
        }
        assertTrue("quirk 13: one id must not serve both kinds", movies.map { it.id }.intersect(series.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun `same genre has different ids per kind`() {
        assertEquals(29, id(movies, "اکشن"))
        assertEquals(108, id(series, "اکشن"))
        assertEquals(13, id(movies, "درام"))
        assertEquals(74, id(series, "درام"))
        assertEquals(379, id(movies, "کمدی"))
        assertEquals(94526, id(series, "کمدی"))
        assertEquals(56, id(movies, "ماجراجویی"))
        assertEquals(109, id(series, "ماجراجویی"))
    }

    @Test
    fun `other kinds have no genre list`() {
        for (c in listOf(Category.CARTOONS, Category.DOCUMENTARIES, Category.SUGGESTIONS)) {
            assertTrue(GenreTable.forCategory(c).isEmpty())
        }
    }

    @Test
    fun `home row genre ids of each kind exist in that kind's table`() {
        val rows = Samples.home().mapNotNull { it.viewAll }.filter { it.genre != null }
        assertTrue(rows.isNotEmpty())
        for (q in rows) {
            val table = GenreTable.forCategory(q.category)
            if (table.isEmpty()) continue
            assertTrue("genre ${q.genre} of ${q.category}", table.any { it.id == q.genre })
        }
    }

    @Test
    fun `genre names of the detail samples exist in their kind's table`() {
        assertTrue(movies.map { it.name }.containsAll(Samples.movie().genres))
        assertTrue(series.map { it.name }.containsAll(Samples.series().genres))
    }
}
