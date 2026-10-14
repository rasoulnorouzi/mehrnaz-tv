package app.bolbol.tv.feature.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The top bar's tabs: the labels in `strings.xml` and `TabDestinations` must stay in step. */
class HomeTabsTest {

    /** The `<item>`s of `R.array.home_tabs`, read from the resource file (unit tests have no Android resources). */
    private fun tabLabels(): List<String> {
        val xml = File("src/main/res/values/strings.xml").readText()
        val array = Regex("""<string-array name="home_tabs">(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
            ?: error("home_tabs not found in strings.xml")
        return Regex("""<item>(.*?)</item>""").findAll(array).map { it.groupValues[1].trim() }.toList()
    }

    @Test
    fun `the top bar reads search, home, movies, series, my list, profile, downloads, settings`() {
        assertEquals(
            listOf("جستجو", "خانه", "فیلم", "سریال", "لیست من", "پروفایل", "دانلودها", "تنظیمات"),
            tabLabels(),
        )
    }

    @Test
    fun `every tab has a destination, in the same order, with downloads between profile and settings`() {
        assertEquals(
            listOf(
                HomeDestination.SEARCH,
                null, // خانه: this screen
                HomeDestination.MOVIES,
                HomeDestination.SERIES,
                HomeDestination.MY_LIST,
                HomeDestination.PROFILE,
                null, // دانلودها: the onOpenDownloads callback
                HomeDestination.SETTINGS,
            ),
            TabDestinations,
        )
        assertEquals(tabLabels().size, TabDestinations.size)
    }

    @Test
    fun `the selected tab is home, and every destination is reachable from exactly one tab`() {
        assertNull(TabDestinations[HOME_TAB_INDEX])
        assertEquals("خانه", tabLabels()[HOME_TAB_INDEX])

        val reachable = TabDestinations.filterNotNull()
        assertEquals(HomeDestination.entries.toSet(), reachable.toSet())
        assertEquals(reachable.size, reachable.toSet().size)
    }

    @Test
    fun `the profile destination sits under the profile label`() {
        val index = TabDestinations.indexOf(HomeDestination.PROFILE)
        assertEquals("پروفایل", tabLabels()[index])
    }

    @Test
    fun `the downloads tab sits under the downloads label and is the only extra null`() {
        assertEquals("دانلودها", tabLabels()[DOWNLOADS_TAB_INDEX])
        assertNull(TabDestinations[DOWNLOADS_TAB_INDEX])
        assertEquals(listOf(HOME_TAB_INDEX, DOWNLOADS_TAB_INDEX), TabDestinations.indices.filter { TabDestinations[it] == null })
    }
}
