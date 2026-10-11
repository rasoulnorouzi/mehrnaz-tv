package app.bolbol.tv.feature.details

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.TitleType
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private val streams = listOf(
        stream(1080, AudioType.SUBTITLED),
        stream(720, AudioType.SUBTITLED),
        stream(720, AudioType.DUBBED),
    )

    /** Keeps the WhileSubscribed upstream running for the duration of the test. */
    private fun TestScope.collect(vm: DetailsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
    }

    private fun DetailsViewModel.content() = uiState.value as DetailsUiState.Content

    @Test fun loadsDetailsAndPreselectsFromTheTitlesOwnPrefs() = runTest {
        val vm = DetailsViewModel(
            1,
            FakeCatalog(movie(1, streams)),
            FakeWatch(),
            FakePrefs(title = PlaybackPrefs(AudioType.SUBTITLED, 1080), global = PlaybackPrefs(AudioType.DUBBED, 720)),
        )
        collect(vm)
        val c = vm.content()
        assertEquals(AudioType.SUBTITLED, c.choices.selectedAudio)
        assertEquals(1080, c.choices.selectedQuality)
        assertEquals(PlayAction(WatchKey(1), 0), c.primary)
    }

    @Test fun fallsBackToTheGlobalPrefsWhenTheTitleHasNone() = runTest {
        val vm = DetailsViewModel(
            1, FakeCatalog(movie(1, streams)), FakeWatch(), FakePrefs(title = null, global = PlaybackPrefs(AudioType.DUBBED, 720)),
        )
        collect(vm)
        assertEquals(AudioType.DUBBED, vm.content().choices.selectedAudio)
    }

    @Test fun savedProgressTurnsPlayIntoResumeAndFollowsLiveUpdates() = runTest {
        val watch = FakeWatch(listOf(progress(1, 0, 0, positionMs = 2_832_000)))
        val vm = DetailsViewModel(1, FakeCatalog(movie(1, streams)), watch, FakePrefs())
        collect(vm)
        assertEquals(PlayAction(WatchKey(1), 2_832_000), vm.content().primary)

        // The player finishes the movie: the button goes back to "play".
        watch.progress.value = listOf(progress(1, 0, 0, positionMs = 2_990_000, completed = true))
        assertEquals(PlayAction(WatchKey(1), 0), vm.content().primary)
    }

    @Test fun audioChipSavesTheChoiceForThisTitle() = runTest {
        val prefs = FakePrefs()
        val vm = DetailsViewModel(1, FakeCatalog(movie(1, streams)), FakeWatch(), prefs)
        collect(vm)

        vm.onAudioSelected(AudioType.DUBBED)

        assertEquals(listOf(1L to PlaybackPrefs(audio = AudioType.DUBBED)), prefs.saved)
        assertEquals(AudioType.DUBBED, vm.content().choices.selectedAudio)
        assertEquals(listOf(720), vm.content().choices.qualityOptions)
    }

    @Test fun qualityChipSavesQualityAndTheAudioShownAtThatMoment() = runTest {
        val prefs = FakePrefs()
        val vm = DetailsViewModel(1, FakeCatalog(movie(1, streams)), FakeWatch(), prefs)
        collect(vm)
        assertEquals(AudioType.SUBTITLED, vm.content().choices.selectedAudio) // first 720p stream

        vm.onQualitySelected(1080)

        assertEquals(PlaybackPrefs(audio = AudioType.SUBTITLED, quality = 1080), prefs.saved.single().second)
        assertEquals(1080, vm.content().choices.selectedQuality)
    }

    @Test fun seasonTabOverridesTheAutomaticSeason() = runTest {
        val show = series(1, season(1, 2), season(2, 2))
        val vm = DetailsViewModel(1, FakeCatalog(show), FakeWatch(), FakePrefs())
        collect(vm)
        assertEquals(1, vm.content().selectedSeason)

        vm.onSeasonSelected(2)

        assertEquals(2, vm.content().selectedSeason)
        assertEquals(listOf(WatchKey(1, 2, 1), WatchKey(1, 2, 2)), vm.content().episodes.map { it.key })
    }

    @Test fun failuresMapToPersianMessageKinds() = runTest {
        val cases = listOf(
            CatalogException.Offline() to DetailsError.OFFLINE,
            CatalogException.AllMirrorsDown() to DetailsError.SERVER_DOWN,
            CatalogException.NotFound() to DetailsError.NOT_FOUND,
            CatalogException.BadResponse() to DetailsError.GENERIC,
            IllegalStateException("boom") to DetailsError.GENERIC,
        )
        for ((failure, expected) in cases) {
            val vm = DetailsViewModel(1, FakeCatalog(failure = failure), FakeWatch(), FakePrefs())
            collect(vm)
            assertEquals(DetailsUiState.Error(expected), vm.uiState.value)
        }
    }

    @Test fun retryReloadsAfterAFailure() = runTest {
        val catalog = FakeCatalog(failure = CatalogException.Offline())
        val vm = DetailsViewModel(1, catalog, FakeWatch(), FakePrefs())
        collect(vm)
        assertTrue(vm.uiState.value is DetailsUiState.Error)

        catalog.failure = null
        catalog.details = movie(1, streams)
        vm.retry()

        assertTrue(vm.uiState.value is DetailsUiState.Content)
        assertEquals(2, catalog.detailsCalls)
    }

    @Test fun unreadablePrefsDoNotBlockTheDetails() = runTest {
        val prefs = object : app.bolbol.tv.core.model.PrefsRepository {
            override fun globalPrefs() = kotlinx.coroutines.flow.flow<PlaybackPrefs> { throw IllegalStateException("db") }
            override suspend fun titlePrefs(postId: Long): PlaybackPrefs? = throw IllegalStateException("db")
            override suspend fun saveGlobalPrefs(prefs: PlaybackPrefs) = Unit
            override suspend fun saveTitlePrefs(postId: Long, prefs: PlaybackPrefs) = Unit
        }
        val vm = DetailsViewModel(1, FakeCatalog(movie(1, streams)), FakeWatch(), prefs)
        collect(vm)
        assertTrue(vm.uiState.value is DetailsUiState.Content)
    }

    // ---- My List ----

    @Test fun withoutMyListActionsTheButtonStateIsUnknownAndTogglingDoesNothing() = runTest {
        val vm = DetailsViewModel(1, FakeCatalog(movie(1, streams)), FakeWatch(), FakePrefs())
        collect(vm)

        assertNull(vm.content().inMyList)
        vm.onToggleMyList() // must not throw
    }

    @Test fun inMyListFollowsTheListLive() = runTest {
        val myList = FakeMyList(setOf(1L))
        val vm = DetailsViewModel(1, FakeCatalog(movie(1, streams)), FakeWatch(), FakePrefs(), myList)
        collect(vm)
        assertEquals(true, vm.content().inMyList)

        myList.listed.value = emptySet()

        assertEquals(false, vm.content().inMyList)
    }

    @Test fun toggleAddsThenRemovesTheTitle() = runTest {
        val myList = FakeMyList()
        val vm = DetailsViewModel(7, FakeCatalog(movie(7, streams)), FakeWatch(), FakePrefs(), myList)
        collect(vm)
        assertEquals(false, vm.content().inMyList)

        vm.onToggleMyList()

        assertEquals(true, vm.content().inMyList)
        assertEquals(7L, myList.writes.single().first.id)
        assertEquals(true, myList.writes.single().second)

        vm.onToggleMyList()

        assertEquals(false, vm.content().inMyList)
        assertEquals(listOf(true, false), myList.writes.map { it.second })
    }

    @Test fun theStoredSnapshotKnowsTheTitleType() = runTest {
        val myList = FakeMyList()
        val vm = DetailsViewModel(7, FakeCatalog(series(7, season(1, 2))), FakeWatch(), FakePrefs(), myList)
        collect(vm)

        vm.onToggleMyList()

        assertEquals(TitleType.SERIES, myList.writes.single().first.type)
    }

    @Test fun aFailedWriteLeavesTheButtonAsItWas() = runTest {
        val myList = FakeMyList().apply { failWrites = true }
        val vm = DetailsViewModel(1, FakeCatalog(movie(1, streams)), FakeWatch(), FakePrefs(), myList)
        collect(vm)

        vm.onToggleMyList()

        assertEquals(false, vm.content().inMyList)
    }

    @Test fun aBrokenListHidesTheButtonButNotTheTitle() = runTest {
        val broken = object : MyListActions {
            override fun isInMyList(postId: Long) = kotlinx.coroutines.flow.flow<Boolean> { throw IllegalStateException("db") }
            override suspend fun setInMyList(title: app.bolbol.tv.core.model.TitleSummary, inList: Boolean) = Unit
        }
        val vm = DetailsViewModel(1, FakeCatalog(movie(1, streams)), FakeWatch(), FakePrefs(), broken)
        collect(vm)

        assertNull(vm.content().inMyList)
    }

    @Test fun aListThatNeverAnswersDoesNotHoldTheScreenBack() = runTest {
        val silent = object : MyListActions {
            override fun isInMyList(postId: Long) = kotlinx.coroutines.flow.flow<Boolean> { kotlinx.coroutines.awaitCancellation() }
            override suspend fun setInMyList(title: app.bolbol.tv.core.model.TitleSummary, inList: Boolean) = Unit
        }
        val vm = DetailsViewModel(1, FakeCatalog(movie(1, streams)), FakeWatch(), FakePrefs(), silent)
        collect(vm)

        assertTrue(vm.uiState.value is DetailsUiState.Content)
        assertNull(vm.content().inMyList)
    }

    // ---- watched toggle on an episode ----

    @Test fun markingAnEpisodeWatchedAndUnwatchedGoesToTheWatchRepository() = runTest {
        val watch = FakeWatch()
        val vm = DetailsViewModel(1, FakeCatalog(series(1, season(1, 3))), watch, FakePrefs())
        collect(vm)

        vm.onSetWatched(WatchKey(1, 1, 2), true)
        vm.onSetWatched(WatchKey(1, 1, 2), false)

        assertEquals(listOf(WatchKey(1, 1, 2) to true, WatchKey(1, 1, 2) to false), watch.markedWatched)
    }

    @Test fun aFailedWatchedWriteIsSwallowed() = runTest {
        val watch = FakeWatch().apply { failWrites = true }
        val vm = DetailsViewModel(1, FakeCatalog(series(1, season(1, 3))), watch, FakePrefs())
        collect(vm)

        vm.onSetWatched(WatchKey(1, 1, 2), true)

        assertTrue(watch.markedWatched.isEmpty())
        assertTrue(vm.uiState.value is DetailsUiState.Content)
    }

    @Test fun anEpisodeMarkedWatchedShowsAsCompletedAndMovesTheContinuePoint() = runTest {
        val watch = FakeWatch()
        val vm = DetailsViewModel(1, FakeCatalog(series(1, season(1, 3))), watch, FakePrefs())
        collect(vm)
        assertEquals(PlayAction(WatchKey(1, 1, 1), 0), vm.content().primary)

        // What the real repository does for markWatched(key, true): a completed progress row.
        watch.progress.value = listOf(progress(1, 1, 1, positionMs = 2_990_000, completed = true))

        assertEquals(true, vm.content().episodes.first().completed)
        assertEquals(PlayAction(WatchKey(1, 1, 2), 0), vm.content().primary)
    }
}
