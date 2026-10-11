package app.bolbol.tv.feature.home

import app.bolbol.tv.core.model.TitleType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Continue Watching menu: which entries it has, in which order, and the movie / series wording. */
class ContinueMenuTest {

    private fun strings(): Map<String, String> {
        val xml = File("src/main/res/values/strings.xml").readText()
        return Regex("""<string name="(\w+)">(.*?)</string>""").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `the page entry comes right after resume`() {
        assertEquals(
            listOf(
                ContinueAction.RESUME,
                ContinueAction.OPEN_TITLE,
                ContinueAction.MARK_WATCHED,
                ContinueAction.REMOVE,
                ContinueAction.CANCEL,
            ),
            ContinueMenuActions,
        )
    }

    @Test
    fun `an episode card is a series even when the title type is unknown`() {
        assertTrue(resumable(1, season = 2, episode = 3).isSeries())
    }

    @Test
    fun `a movie card with no type is a movie`() {
        assertFalse(resumable(1).isSeries())
    }

    @Test
    fun `a known series title is a series even without an episode key`() {
        val item = resumable(1).let { it.copy(title = it.title.copy(type = TitleType.SERIES)) }
        assertTrue(item.isSeries())
    }

    @Test
    fun `a known movie title is a movie`() {
        val item = resumable(1).let { it.copy(title = it.title.copy(type = TitleType.MOVIE)) }
        assertFalse(item.isSeries())
    }

    @Test
    fun `the page entry reads movie page or series page`() {
        val s = strings()
        assertEquals("رفتن به صفحهٔ فیلم", s["home_menu_open_movie"])
        assertEquals("رفتن به صفحهٔ سریال", s["home_menu_open_series"])
        assertEquals(R.string.home_menu_open_movie, ContinueAction.OPEN_TITLE.labelRes(isSeries = false))
        assertEquals(R.string.home_menu_open_series, ContinueAction.OPEN_TITLE.labelRes(isSeries = true))
    }

    @Test
    fun `the other entries read the same for movies and series`() {
        for (action in ContinueMenuActions - ContinueAction.OPEN_TITLE) {
            assertEquals(action.labelRes(false), action.labelRes(true))
        }
    }
}
