package app.bolbol.tv.feature.details

import app.bolbol.tv.core.model.Season
import app.bolbol.tv.core.model.WatchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Opening a series on a given episode (Continue Watching's "رفتن به صفحهٔ سریال"): season preselection and fallbacks. */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeFocusTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private val show = series(1, season(1, 3), season(2, 4), season(3, 2))

    private fun build(userSeason: Int?, target: WatchKey?, progress: List<app.bolbol.tv.core.model.PlaybackProgress> = emptyList()) =
        buildDetailsContent(show, progress, null, userSeason, null, target)

    // ---- the target key from route arguments ----

    @Test fun routeArgumentsNeedBothSeasonAndEpisode() {
        assertEquals(WatchKey(1, 2, 3), episodeFocusTarget(1, 2, 3))
        assertNull(episodeFocusTarget(1, 0, 0))
        assertNull(episodeFocusTarget(1, 2, 0))
        assertNull(episodeFocusTarget(1, 0, 3))
    }

    // ---- pure content logic ----

    @Test fun targetSeasonIsSelectedAndTheEpisodeIsFocused() {
        val c = build(userSeason = 2, target = WatchKey(1, 2, 3))
        assertEquals(2, c.selectedSeason)
        assertEquals(WatchKey(1, 2, 3), c.focusEpisode)
        assertEquals(4, c.episodes.size)
    }

    @Test fun targetBeatsTheContinueSeason() {
        // Progress says "continue in season 1", the route asked for season 3 episode 2.
        val c = build(
            userSeason = 3,
            target = WatchKey(1, 3, 2),
            progress = listOf(progress(1, 1, 2, positionMs = 600_000)),
        )
        assertEquals(3, c.selectedSeason)
        assertEquals(WatchKey(1, 3, 2), c.focusEpisode)
        assertEquals(WatchKey(1, 1, 2), c.primary?.key)
    }

    @Test fun noTargetKeepsTheDefaultBehaviour() {
        val c = build(userSeason = null, target = null)
        assertEquals(1, c.selectedSeason)
        assertNull(c.focusEpisode)
    }

    @Test fun missingSeasonFallsBackToTheDefaultSeasonAndFocus() {
        val c = build(userSeason = 9, target = WatchKey(1, 9, 1))
        assertEquals(1, c.selectedSeason)
        assertNull(c.focusEpisode)
    }

    @Test fun missingEpisodeKeepsTheSeasonButNotTheFocus() {
        val c = build(userSeason = 2, target = WatchKey(1, 2, 99))
        assertEquals(2, c.selectedSeason)
        assertNull(c.focusEpisode)
    }

    @Test fun anEpisodeWithoutStreamsIsNotFocused() {
        val withGap = series(1, Season(1, listOf(episode(1, 1), episode(1, 2, streams = emptyList()))))
        val c = buildDetailsContent(withGap, emptyList(), null, 1, null, WatchKey(1, 1, 2))
        assertNull(c.focusEpisode)
    }

    @Test fun anotherSeasonTabHasNoFocusTarget() {
        // The user moves to season 1 after arriving at season 2 episode 3: the target no longer applies.
        val c = build(userSeason = 1, target = WatchKey(1, 2, 3))
        assertEquals(1, c.selectedSeason)
        assertNull(c.focusEpisode)
    }

    @Test fun aTargetOfAnotherTitleIsIgnored() {
        val c = build(userSeason = 2, target = WatchKey(77, 2, 3))
        assertNull(c.focusEpisode)
    }

    @Test fun aMovieIgnoresTheTarget() {
        val c = buildDetailsContent(movie(1), emptyList(), null, null, null, WatchKey(1, 1, 1))
        assertNull(c.focusEpisode)
        assertEquals(0, c.selectedSeason)
    }

    // ---- through the ViewModel ----

    private fun TestScope.collect(vm: DetailsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
    }

    private fun DetailsViewModel.content() = uiState.value as DetailsUiState.Content

    @Test fun viewModelOpensOnTheRouteSeasonAndEpisode() = runTest {
        val vm = DetailsViewModel(1, FakeCatalog(show), FakeWatch(), FakePrefs(), focusSeason = 3, focusEpisode = 2)
        collect(vm)
        assertEquals(3, vm.content().selectedSeason)
        assertEquals(WatchKey(1, 3, 2), vm.content().focusEpisode)
    }

    @Test fun viewModelFallsBackWhenTheRouteEpisodeIsGone() = runTest {
        val vm = DetailsViewModel(1, FakeCatalog(show), FakeWatch(), FakePrefs(), focusSeason = 5, focusEpisode = 1)
        collect(vm)
        assertEquals(1, vm.content().selectedSeason)
        assertNull(vm.content().focusEpisode)
    }

    @Test fun pickingAnotherSeasonDropsTheFocusTarget() = runTest {
        val vm = DetailsViewModel(1, FakeCatalog(show), FakeWatch(), FakePrefs(), focusSeason = 2, focusEpisode = 3)
        collect(vm)
        vm.onSeasonSelected(1)
        assertEquals(1, vm.content().selectedSeason)
        assertNull(vm.content().focusEpisode)
    }

    @Test fun withoutRouteTargetTheViewModelBehavesAsBefore() = runTest {
        val vm = DetailsViewModel(1, FakeCatalog(show), FakeWatch(), FakePrefs())
        collect(vm)
        assertEquals(1, vm.content().selectedSeason)
        assertNull(vm.content().focusEpisode)
    }
}
