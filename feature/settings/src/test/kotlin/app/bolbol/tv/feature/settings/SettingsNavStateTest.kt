package app.bolbol.tv.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsNavStateTest {
    @Test
    fun startsOnSubtitlesInTheList() {
        val nav = SettingsNavState()
        assertEquals(SettingsSection.SUBTITLES, nav.section)
        assertEquals(SettingsZone.LIST, nav.zone)
    }

    @Test
    fun sectionsAreInDocumentedOrderWithPersianLabels() {
        assertEquals(
            listOf("زیرنویس", "پخش", "اتصال", "توسعه‌دهنده", "درباره"),
            SettingsSection.entries.map { it.label },
        )
    }

    @Test
    fun focusingAListItemSelectsItAndStaysInTheList() {
        val nav = SettingsNavState().listFocused(SettingsSection.CONNECTION)
        assertEquals(SettingsNavState(SettingsSection.CONNECTION, SettingsZone.LIST), nav)
    }

    @Test
    fun endDirectionEntersTheContent() {
        val nav = SettingsNavState(SettingsSection.PLAYBACK).enterContent()
        assertEquals(SettingsNavState(SettingsSection.PLAYBACK, SettingsZone.CONTENT), nav)
    }

    @Test
    fun aSectionWithoutControlsCannotBeEntered() {
        val nav = SettingsNavState(SettingsSection.ABOUT)
        assertEquals(nav, nav.enterContent())
        assertEquals(nav, nav.contentFocused())
    }

    @Test
    fun backFromContentReturnsToTheListKeepingTheSection() {
        val inContent = SettingsNavState(SettingsSection.DEVELOPER, SettingsZone.CONTENT)
        assertEquals(SettingsNavState(SettingsSection.DEVELOPER, SettingsZone.LIST), inContent.back())
    }

    @Test
    fun backFromTheListIsLeftToTheHost() {
        assertNull(SettingsNavState().back())
    }

    @Test
    fun leavingTheContentByFocusMoveReturnsToTheList() {
        val inContent = SettingsNavState(zone = SettingsZone.CONTENT)
        assertEquals(SettingsZone.LIST, inContent.contentBlurred().zone)
        // Already in the list: nothing changes.
        assertEquals(SettingsNavState(), SettingsNavState().contentBlurred())
    }

    @Test
    fun movingFromContentBackToAnotherListItemSelectsThatItem() {
        val nav = SettingsNavState(SettingsSection.SUBTITLES, SettingsZone.CONTENT)
            .listFocused(SettingsSection.PLAYBACK)
        assertEquals(SettingsNavState(SettingsSection.PLAYBACK, SettingsZone.LIST), nav)
    }

    @Test
    fun everySectionButAboutHasControls() {
        assertEquals(
            listOf(true, true, true, true, false),
            SettingsSection.entries.map { it.hasControls },
        )
    }
}
