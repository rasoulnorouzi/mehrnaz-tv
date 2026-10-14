package app.bolbol.tv.core.data

import app.bolbol.tv.core.model.Category
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogGenresTest {
    private val remote = FakeRemote()
    private val repo = CatalogRepositoryImpl(remote = remote, homeCache = FakeHomeCache())

    @Test
    fun genresComeFromTheStaticTableWithoutANetworkCall() = runTest {
        val movies = repo.genres(Category.MOVIES)
        assertEquals(23, movies.size)
        assertEquals(29, movies.first().id)
        assertEquals(108, repo.genres(Category.SERIES).first().id)
        assertTrue(repo.genres(Category.CARTOONS).isEmpty())
        assertEquals(0, remote.listCalls)
        assertEquals(0, remote.homeCalls)
    }
}
