package app.bolbol.tv.feature.home

import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.HomeFeed
import app.bolbol.tv.core.model.WatchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class HomeViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    /** Keeps the WhileSubscribed upstream alive for the whole test. */
    private fun TestScope.collect(vm: HomeViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
    }

    private fun HomeViewModel.rows() = (uiState.value as HomeUiState.Content).rows

    @Test fun cachedRowsPaintFirstThenFreshRowsReplaceThemSilently() = runTest {
        val source = MutableSharedFlow<HomeFeed>()
        val vm = HomeViewModel(FakeCatalog { source }, FakeWatch())
        collect(vm)
        assertEquals(HomeUiState.Loading, vm.uiState.value)

        source.emit(feed(section("a", 1, 2), fromCache = true))
        assertEquals(listOf("section:a"), vm.rows().map { it.id })
        assertEquals(2, vm.rows().single().size)

        source.emit(feed(section("a", 1, 2, 3), section("b", 4), fromCache = false))
        assertEquals(listOf("section:a", "section:b"), vm.rows().map { it.id })
        assertEquals(3, vm.rows().first().size)
    }

    @Test fun continueWatchingComesFirstAndFollowsTheLocalList() = runTest {
        val watch = FakeWatch()
        val vm = HomeViewModel(FakeCatalog { flow { emit(feed(section("a", 1))) } }, watch)
        collect(vm)
        assertEquals(listOf("section:a"), vm.rows().map { it.id }) // empty list: no row

        watch.items.value = listOf(resumable(9), resumable(8, season = 1, episode = 2))

        assertEquals(listOf(HomeRow.CONTINUE_ROW_ID, "section:a"), vm.rows().map { it.id })
        assertEquals(2, vm.rows().first().size)
    }

    @Test fun failureWithoutCacheShowsThePersianErrorKind() = runTest {
        val cases = listOf(
            CatalogException.Offline() to HomeError.OFFLINE,
            CatalogException.AllMirrorsDown() to HomeError.SERVER_DOWN,
            CatalogException.BadResponse() to HomeError.GENERIC,
            CatalogException.NotFound() to HomeError.GENERIC,
            IllegalStateException("boom") to HomeError.GENERIC,
        )
        for ((failure, expected) in cases) {
            val vm = HomeViewModel(FakeCatalog { flow { throw failure } }, FakeWatch())
            collect(vm)
            assertEquals(HomeUiState.Error(expected), vm.uiState.value)
        }
    }

    @Test fun retryLoadsAgainAfterAFailure() = runTest {
        var online = false
        val catalog = FakeCatalog {
            flow {
                if (!online) throw CatalogException.Offline()
                emit(feed(section("a", 1)))
            }
        }
        val vm = HomeViewModel(catalog, FakeWatch())
        collect(vm)
        assertEquals(HomeUiState.Error(HomeError.OFFLINE), vm.uiState.value)

        online = true
        vm.retry()

        assertEquals(listOf("section:a"), vm.rows().map { it.id })
        assertEquals(2, catalog.homeCalls)
    }

    @Test fun aFailedRefreshKeepsTheRowsAlreadyOnScreen() = runTest {
        val vm = HomeViewModel(
            FakeCatalog {
                flow {
                    emit(feed(section("a", 1), fromCache = true))
                    throw CatalogException.Offline()
                }
            },
            FakeWatch(),
        )
        collect(vm)
        assertEquals(listOf("section:a"), vm.rows().map { it.id })
    }

    @Test fun retryWhileRowsAreShownDoesNotFlashTheLoadingState() = runTest {
        val source = MutableSharedFlow<HomeFeed>()
        val vm = HomeViewModel(FakeCatalog { source }, FakeWatch())
        collect(vm)
        source.emit(feed(section("a", 1)))

        vm.retry()

        assertTrue(vm.uiState.value is HomeUiState.Content)
    }

    @Test fun focusIsRememberedForBackNavigation() = runTest {
        val vm = HomeViewModel(FakeCatalog { flow { emit(feed(section("a", 1))) } }, FakeWatch())
        assertNull(vm.lastFocus)

        vm.onCardFocused("section:a", 1)

        assertEquals(FocusTarget("section:a", 1), vm.lastFocus)
    }

    @Test fun theViewAllCardIsRememberedAsAFocusTarget() = runTest {
        val vm = HomeViewModel(FakeCatalog { flow { emit(feed(section("a", 1))) } }, FakeWatch())
        vm.onCardFocused("section:a", 1)

        vm.onViewAllFocused("section:a")

        assertEquals(FocusTarget("section:a", VIEW_ALL_FOCUS_ID), vm.lastFocus)
        assertTrue(vm.lastFocus!!.isViewAll)

        // Moving back onto a poster replaces it.
        vm.onCardFocused("section:a", 1)
        assertEquals(FocusTarget("section:a", 1), vm.lastFocus)
    }

    // ---- Continue Watching menu actions ----

    @Test fun markWatchedMarksTheLatestEpisodeOfTheEntry() = runTest {
        val watch = FakeWatch(listOf(resumable(8, season = 1, episode = 2)))
        val vm = HomeViewModel(FakeCatalog { flow { emit(feed(section("a", 1))) } }, watch)

        vm.markWatched(watch.items.value.single())

        assertEquals(listOf(WatchKey(8, 1, 2) to true), watch.markedWatched)
        assertTrue(watch.hidden.isEmpty())
    }

    @Test fun removeHidesTheWholeTitleFromContinueWatching() = runTest {
        val watch = FakeWatch(listOf(resumable(8, season = 1, episode = 2), resumable(9)))
        val vm = HomeViewModel(FakeCatalog { flow { emit(feed(section("a", 1))) } }, watch)

        vm.removeFromContinueWatching(watch.items.value.first())

        assertEquals(listOf(8L), watch.hidden)
        assertTrue(watch.markedWatched.isEmpty())
    }

    @Test fun aFailedLocalWriteIsSwallowed() = runTest {
        val watch = FakeWatch(listOf(resumable(8))).apply { failWrites = true }
        val vm = HomeViewModel(FakeCatalog { flow { emit(feed(section("a", 1))) } }, watch)

        vm.markWatched(watch.items.value.single())
        vm.removeFromContinueWatching(watch.items.value.single())

        assertTrue(watch.markedWatched.isEmpty() && watch.hidden.isEmpty())
    }

    private fun vmWith(catalog: FakeCatalog, watch: FakeWatch) =
        HomeViewModel(catalog, watch, computeDispatcher = UnconfinedTestDispatcher())

    @Test fun localRowsGoBetweenContinueWatchingAndApiRowsAndDoNotBlockTheFirstFrame() = runTest {
        val catalog = FakeCatalog { flow { emit(feed(section("a", 1))) } }
        catalog.localRows = { listOf(localRow("rec", 5, 6, viewAll = app.bolbol.tv.core.model.CatalogFilter()), localRow("empty")) }
        val vm = vmWith(catalog, FakeWatch(listOf(resumable(9), resumable(8))))
        collect(vm)
        // Debounce has not elapsed: the feed and Continue Watching are already on screen.
        assertEquals(listOf(HomeRow.CONTINUE_ROW_ID, "section:a"), vm.rows().map { it.id })
        assertTrue(catalog.localCalls.isEmpty())

        advanceTimeBy(500)
        assertEquals(listOf(HomeRow.CONTINUE_ROW_ID, "local-rec", "section:a"), vm.rows().map { it.id })
        assertEquals(listOf(listOf(9L, 8L)), catalog.localCalls)
        assertTrue((vm.rows()[1] as HomeRow.Titles).hasViewAll)
    }

    @Test fun continueWatchingChangeRecomputesAndAFailureKeepsTheOldRows() = runTest {
        val catalog = FakeCatalog { flow { emit(feed(section("a", 1))) } }
        var n = 0
        catalog.localRows = { n++; if (n == 1) listOf(localRow("rec", 5)) else throw IllegalStateException("x") }
        val watch = FakeWatch()
        val vm = vmWith(catalog, watch)
        collect(vm)
        advanceTimeBy(500)
        assertEquals(listOf("local-rec", "section:a"), vm.rows().map { it.id })

        watch.items.value = listOf(resumable(7))
        advanceTimeBy(500)
        assertEquals(listOf(listOf<Long>(), listOf(7L)), catalog.localCalls)
        assertEquals(listOf(HomeRow.CONTINUE_ROW_ID, "local-rec", "section:a"), vm.rows().map { it.id })
    }

    @Test fun focusOnALocalRowCardIsRestored() {
        val rows = (buildHomeUiState(FeedState.Loaded(listOf(section("a", 1))), emptyList(), listOf(localRow("rec", 5, 6))) as HomeUiState.Content).rows
        val focus = rows.resolveInitialFocus(FocusTarget("local-rec", 6))!!
        assertEquals(0, focus.rowIndex)
        assertEquals(1, focus.itemIndex)
    }
}
