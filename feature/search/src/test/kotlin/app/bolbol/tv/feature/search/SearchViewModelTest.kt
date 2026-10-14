package app.bolbol.tv.feature.search

import androidx.lifecycle.viewModelScope
import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.CatalogRepository
import app.bolbol.tv.core.model.HomeFeed
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()
    private val created = mutableListOf<SearchViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    // --- fakes ---

    private fun title(id: Long) = TitleSummary(
        id = id,
        type = TitleType.MOVIE,
        title = "Title $id",
        faTitle = "عنوان $id",
        posterUrl = null,
        backdropUrl = null,
        year = 2020,
        imdbRating = 7.5f,
        hasDub = true,
        hasSubtitle = false,
    )

    private fun pageOf(ids: IntRange, page: Int = 1, totalPages: Int = 1) =
        Page(ids.map { title(it.toLong()) }, page, totalPages)

    private class FakeCatalog(var handler: suspend (String, Int) -> Page<TitleSummary>) : CatalogRepository {
        val calls = mutableListOf<Pair<String, Int>>()
        var titles: List<String> = emptyList()
        override suspend fun suggestions(prefix: String, limit: Int): List<String> =
            titles.filter { it.startsWith(prefix, ignoreCase = true) }.take(limit)
        override fun homeFeed(): Flow<HomeFeed> = emptyFlow()
        override suspend fun details(id: Long): TitleDetails = error("unused")
        override suspend fun list(query: ListQuery, page: Int): Page<TitleSummary> = error("unused")
        override suspend fun search(text: String, page: Int): Page<TitleSummary> {
            calls += text to page
            return handler(text, page)
        }
    }

    private class FakeStore(
        var language: KeyboardLanguage = KeyboardLanguage.Persian,
        var recents: List<String> = emptyList(),
    ) : SearchStore {
        var savedLanguage: KeyboardLanguage? = null
        val savedRecents = mutableListOf<List<String>>()
        override fun loadLanguage() = language
        override fun saveLanguage(language: KeyboardLanguage) {
            savedLanguage = language
        }

        override fun loadRecents() = recents
        override fun saveRecents(recents: List<String>) {
            savedRecents += recents
        }
    }

    private fun TestScope.vm(catalog: FakeCatalog, store: FakeStore = FakeStore()): SearchViewModel {
        val vm = SearchViewModel(catalog, store, dispatcher, SEARCH_DEBOUNCE_MS)
        created += vm
        runCurrent()
        return vm
    }

    private fun SearchViewModel.type(text: String) = text.forEach { onKey(KeyAction.Insert(it.toString())) }

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private val SearchViewModel.current get() = state.value

    // --- predictions ---

    @Test
    fun predictionsListRecentsFirstThenKnownTitlesWithoutDuplicates() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, _ -> pageOf(1..2) }
        catalog.titles = listOf("Dune", "Dune Part Two", "Dunkirk", "Dune 1984")
        val vm = vm(catalog, FakeStore(recents = listOf("Dune Part Two", "Friends")))
        vm.type("Du"); pass(150)
        assertEquals(listOf("Dune Part Two", "Dune", "Dunkirk", "Dune 1984"), vm.current.predictions)
        vm.onKey(KeyAction.Clear); pass(150)
        assertTrue(vm.current.predictions.isEmpty())
    }

    @Test
    fun choosingAPredictionFillsTheQuerySearchesAtOnceAndRemembersIt() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, _ -> pageOf(1..2) }
        catalog.titles = listOf("Dune Part Two")
        val store = FakeStore()
        val vm = vm(catalog, store)
        vm.type("Du"); pass(150)
        vm.onPredictionClicked(vm.current.predictions.single())
        pass(1) // immediate: no debounce
        assertEquals("Dune Part Two", vm.current.query)
        assertEquals("Dune Part Two" to 1, catalog.calls.last())
        assertEquals(listOf("Dune Part Two"), vm.current.recents)
    }

    @Test
    fun theLastFocusedKeyIsRemembered() = runTest(dispatcher) {
        val vm = vm(FakeCatalog { _, _ -> pageOf(1..1) })
        assertEquals(SearchViewModel.DEFAULT_KEY_ID, vm.lastKeyId)
        vm.onKeyFocused("3.7")
        assertEquals("3.7", vm.lastKeyId)
    }

    // --- typing and debounce ---

    @Test
    fun rapidTypingProducesOneRequestAfterTheDebounce() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, _ -> pageOf(1..12, totalPages = 2) }
        val vm = vm(catalog)
        vm.type("d"); pass(100)
        vm.type("u"); pass(100)
        vm.type("n"); pass(100)
        vm.type("e"); pass(399)
        assertTrue("no request before the debounce elapsed", catalog.calls.isEmpty())
        pass(2)
        assertEquals(listOf("dune" to 1), catalog.calls)
        assertEquals(SearchPhase.Results, vm.current.phase)
        assertEquals(12, vm.current.items.size)
        assertTrue(vm.current.hasNext)
    }

    @Test
    fun oneCharacterDoesNotSearchAndShowsTheIdleState() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, _ -> pageOf(1..3) }
        val vm = vm(catalog)
        vm.type("d")
        pass(2_000)
        assertTrue(catalog.calls.isEmpty())
        assertEquals(SearchPhase.Idle, vm.current.phase)
        assertEquals("d", vm.current.query)
    }

    @Test
    fun deletingBelowTwoCharactersClearsTheResultsImmediately() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, _ -> pageOf(1..3) }
        val vm = vm(catalog)
        vm.type("du"); pass(500)
        assertEquals(SearchPhase.Results, vm.current.phase)
        vm.onKey(KeyAction.Backspace)
        pass(1) // no debounce when going back to idle
        assertEquals(SearchPhase.Idle, vm.current.phase)
        assertTrue(vm.current.items.isEmpty())
    }

    @Test
    fun aNewKeystrokeCancelsTheStaleRequest() = runTest(dispatcher) {
        val finished = mutableListOf<String>()
        val catalog = FakeCatalog { text, _ ->
            delay(1_000)
            finished += text
            pageOf(if (text == "du") 1..2 else 10..11)
        }
        val vm = vm(catalog)
        vm.type("du"); pass(401) // "du" request is now in flight
        assertEquals(listOf("du" to 1), catalog.calls)
        vm.type("n") // "dun" supersedes it
        pass(401)
        pass(1_500)
        assertEquals(listOf("dun"), finished) // "du" never completed
        assertEquals(listOf(10L, 11L), vm.current.items.map { it.id })
    }

    @Test
    fun theQueryIsNormalisedBeforeItIsSent() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, _ -> pageOf(1..2) }
        val vm = vm(catalog)
        val arabicYeh = 0x064A.toChar()
        val arabicKaf = 0x0643.toChar()
        vm.onExternalInput("  عل$arabicYeh   $arabicKaf" + "تاب  ")
        pass(500)
        assertEquals(listOf("علی کتاب" to 1), catalog.calls)
    }

    @Test
    fun trailingSpaceDoesNotTriggerASecondSearch() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, _ -> pageOf(1..2) }
        val vm = vm(catalog)
        vm.type("dune"); pass(500)
        vm.onKey(KeyAction.Space); pass(1_000)
        assertEquals(1, catalog.calls.size)
        assertEquals("dune ", vm.current.query)
    }

    // --- result states ---

    @Test
    fun emptyPageShowsTheNoResultsState() = runTest(dispatcher) {
        val vm = vm(FakeCatalog { _, _ -> Page(emptyList(), 1, 1) })
        vm.type("zz"); pass(500)
        assertEquals(SearchPhase.Empty, vm.current.phase)
        assertFalse(vm.current.hasNext)
    }

    @Test
    fun notFoundIsTreatedAsNoResultsNotAsAnError() = runTest(dispatcher) {
        val vm = vm(FakeCatalog { _, _ -> throw CatalogException.NotFound() })
        vm.type("zz"); pass(500)
        assertEquals(SearchPhase.Empty, vm.current.phase)
        assertNull(vm.current.error)
    }

    @Test
    fun failureShowsAMessageAndRetryRunsTheSearchAgain() = runTest(dispatcher) {
        var failing = true
        val catalog = FakeCatalog { _, _ ->
            if (failing) throw CatalogException.Offline() else pageOf(1..4)
        }
        val vm = vm(catalog)
        vm.type("dune"); pass(500)
        assertEquals(SearchPhase.Error, vm.current.phase)
        assertEquals(SearchError.Offline, vm.current.error)
        failing = false
        vm.retry()
        pass(1) // retry skips the debounce
        assertEquals(SearchPhase.Results, vm.current.phase)
        assertNull(vm.current.error)
        assertEquals(2, catalog.calls.size)
        assertEquals(catalog.calls[0], catalog.calls[1])
    }

    @Test
    fun unexpectedExceptionsBecomeTheGenericError() = runTest(dispatcher) {
        val vm = vm(FakeCatalog { _, _ -> throw IllegalStateException("boom") })
        vm.type("dune"); pass(500)
        assertEquals(SearchPhase.Error, vm.current.phase)
        assertEquals(SearchError.Unknown, vm.current.error)
    }

    @Test
    fun previousResultsStayVisibleWhileTheNextSearchLoads() = runTest(dispatcher) {
        val catalog = FakeCatalog { text, _ ->
            if (text == "dune") pageOf(1..3) else {
                delay(5_000)
                pageOf(7..8)
            }
        }
        val vm = vm(catalog)
        vm.type("dune"); pass(500)
        vm.type("s"); pass(500)
        assertEquals(SearchPhase.Loading, vm.current.phase)
        assertEquals(3, vm.current.items.size)
        pass(6_000)
        assertEquals(listOf(7L, 8L), vm.current.items.map { it.id })
    }

    @Test
    fun everyNewResultSetBumpsTheGeneration() = runTest(dispatcher) {
        val vm = vm(FakeCatalog { _, _ -> pageOf(1..3) })
        vm.type("du"); pass(500)
        val first = vm.current.generation
        vm.type("n"); pass(500)
        assertTrue(vm.current.generation > first)
    }

    // --- pagination ---

    @Test
    fun focusOnTheLastRowLoadsTheNextPageAndDropsDuplicates() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, page ->
            if (page == 1) pageOf(1..12, page = 1, totalPages = 2) else pageOf(12..20, page = 2, totalPages = 2)
        }
        val vm = vm(catalog)
        vm.type("dune"); pass(500)
        vm.onResultFocused(index = 5, columns = 3) // not the last row
        pass(10)
        assertEquals(1, catalog.calls.size)
        vm.onResultFocused(index = 9, columns = 3) // last row
        pass(10)
        assertEquals(listOf("dune" to 1, "dune" to 2), catalog.calls)
        assertEquals((1L..20L).toList(), vm.current.items.map { it.id }) // 12 appeared on both pages
        assertFalse(vm.current.hasNext)
        assertFalse(vm.current.loadingMore)
    }

    @Test
    fun noRequestIsMadeAfterTheLastPage() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, _ -> pageOf(1..12, page = 1, totalPages = 1) }
        val vm = vm(catalog)
        vm.type("dune"); pass(500)
        vm.onResultFocused(11, 3)
        pass(10)
        assertEquals(1, catalog.calls.size)
    }

    @Test
    fun focusingTheLastRowTwiceWhileLoadingRequestsOnce() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, page ->
            if (page == 2) delay(1_000)
            pageOf(1..12, page = page, totalPages = 5)
        }
        val vm = vm(catalog)
        vm.type("dune"); pass(500)
        vm.onResultFocused(10, 3)
        pass(10)
        assertTrue(vm.current.loadingMore)
        vm.onResultFocused(11, 3)
        vm.onResultFocused(10, 3)
        pass(2_000)
        assertEquals(2, catalog.calls.size)
        assertEquals(2, vm.current.page)
    }

    @Test
    fun aPageFetchFailureKeepsTheResultsAndAllowsARetryOnTheNextFocus() = runTest(dispatcher) {
        var fail = true
        val catalog = FakeCatalog { _, page ->
            if (page == 2 && fail) throw CatalogException.Offline()
            pageOf(if (page == 1) 1..12 else 13..14, page = page, totalPages = 2)
        }
        val vm = vm(catalog)
        vm.type("dune"); pass(500)
        vm.onResultFocused(9, 3)
        pass(10)
        assertEquals(SearchPhase.Results, vm.current.phase)
        assertEquals(12, vm.current.items.size)
        assertFalse(vm.current.loadingMore)
        fail = false
        vm.onResultFocused(10, 3)
        pass(10)
        assertEquals(14, vm.current.items.size)
    }

    @Test
    fun aLatePageOfAnOlderQueryIsDiscarded() = runTest(dispatcher) {
        val catalog = FakeCatalog { text, page ->
            when {
                text == "dune" && page == 2 -> {
                    delay(2_000)
                    pageOf(100..105, page = 2, totalPages = 2)
                }
                text == "dune" -> pageOf(1..12, page = 1, totalPages = 2)
                else -> pageOf(50..51)
            }
        }
        val vm = vm(catalog)
        vm.type("dune"); pass(500)
        vm.onResultFocused(9, 3) // page 2 of "dune" starts (slow)
        pass(100)
        vm.type("s"); pass(500) // user refines: "dunes" replaces the results
        pass(3_000)
        assertEquals(listOf(50L, 51L), vm.current.items.map { it.id })
    }

    // --- recents, voice, phone ---

    @Test
    fun openingAResultRemembersTheSearchAndPersistsIt() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = vm(FakeCatalog { _, _ -> pageOf(1..3) }, store)
        vm.type("dune"); pass(500)
        vm.onResultOpened(title(2))
        pass(10)
        assertEquals(listOf("dune"), vm.current.recents)
        assertEquals(listOf("dune"), store.savedRecents.last())
        // Searching and opening the same thing again does not duplicate it.
        vm.onResultOpened(title(3))
        pass(10)
        assertEquals(listOf("dune"), vm.current.recents)
    }

    @Test
    fun recentsKeepTheEightMostRecentSearches() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = vm(FakeCatalog { _, _ -> pageOf(1..3) }, store)
        for (i in 1..10) {
            vm.onKey(KeyAction.Clear)
            vm.type("movie$i"); pass(500)
            vm.onResultOpened(title(1))
        }
        pass(10)
        assertEquals(MAX_RECENTS, vm.current.recents.size)
        assertEquals("movie10", vm.current.recents.first())
        assertEquals("movie3", vm.current.recents.last())
    }

    @Test
    fun savedLanguageAndRecentsAreLoadedAtStart() = runTest(dispatcher) {
        val store = FakeStore(KeyboardLanguage.English, listOf("friends", "dune"))
        val vm = vm(FakeCatalog { _, _ -> pageOf(1..3) }, store)
        assertEquals(KeyboardLanguage.English, vm.current.language)
        assertEquals(listOf("friends", "dune"), vm.current.recents)
    }

    @Test
    fun switchKeyTogglesTheLayoutAndRemembersIt() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = vm(FakeCatalog { _, _ -> pageOf(1..3) }, store)
        vm.onKey(KeyAction.SwitchLanguage)
        pass(10)
        assertEquals(KeyboardLanguage.English, vm.current.language)
        assertEquals(KeyboardLanguage.English, store.savedLanguage)
        vm.onKey(KeyAction.SwitchLanguage)
        pass(10)
        assertEquals(KeyboardLanguage.Persian, store.savedLanguage)
    }

    @Test
    fun recentChipSearchesImmediately() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, _ -> pageOf(1..3) }
        val vm = vm(catalog, FakeStore(recents = listOf("friends")))
        vm.onRecentClicked("friends")
        pass(1)
        assertEquals(listOf("friends" to 1), catalog.calls)
        assertEquals("friends", vm.current.query)
        assertEquals(KeyboardLanguage.English, vm.current.language)
    }

    @Test
    fun voiceResultSearchesImmediatelyAndIsRemembered() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, _ -> pageOf(1..3) }
        val store = FakeStore()
        val vm = vm(catalog, store)
        vm.onVoiceResult("  دوستان ")
        pass(1)
        assertEquals(listOf("دوستان" to 1), catalog.calls)
        assertEquals(listOf("دوستان"), vm.current.recents)
        assertEquals(listOf("دوستان"), store.savedRecents.last())
    }

    @Test
    fun voiceResultWithoutHitsIsNotRemembered() = runTest(dispatcher) {
        val vm = vm(FakeCatalog { _, _ -> Page(emptyList(), 1, 1) })
        vm.onVoiceResult("xyzzy")
        pass(1)
        assertEquals(SearchPhase.Empty, vm.current.phase)
        assertTrue(vm.current.recents.isEmpty())
    }

    @Test
    fun blankVoiceResultIsIgnored() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, _ -> pageOf(1..3) }
        val vm = vm(catalog)
        vm.onVoiceResult("   ")
        pass(1_000)
        assertTrue(catalog.calls.isEmpty())
    }

    @Test
    fun externalInputReplacesTheQueryAndSwitchesTheLayoutDisplayOnly() = runTest(dispatcher) {
        val catalog = FakeCatalog { _, _ -> pageOf(1..3) }
        val store = FakeStore()
        val vm = vm(catalog, store)
        vm.type("old")
        vm.onExternalInput("Friends")
        assertEquals("Friends", vm.current.query)
        assertEquals(KeyboardLanguage.English, vm.current.language)
        pass(500)
        assertEquals(listOf("Friends" to 1), catalog.calls.takeLast(1))
        // The phone does not change the person's remembered layout.
        assertNull(store.savedLanguage)

        vm.onExternalInput("دوستان")
        assertEquals(KeyboardLanguage.Persian, vm.current.language)
    }

    @Test
    fun externalInputKeepsTheLayoutWhenItHasNoLetters() = runTest(dispatcher) {
        val vm = vm(FakeCatalog { _, _ -> pageOf(1..3) }, FakeStore(KeyboardLanguage.English))
        vm.onExternalInput("2012")
        assertEquals(KeyboardLanguage.English, vm.current.language)
    }

    // --- focus restore and state flags ---

    @Test
    fun openedTitleIsHandedBackOnce() = runTest(dispatcher) {
        val vm = vm(FakeCatalog { _, _ -> pageOf(1..3) })
        vm.type("dune"); pass(500)
        vm.onResultOpened(title(3))
        assertEquals(3L, vm.consumeOpenedId())
        assertNull(vm.consumeOpenedId())
    }

    @Test
    fun resultsPanelFocusabilityFollowsThePhase() = runTest(dispatcher) {
        val vm = vm(FakeCatalog { _, _ -> pageOf(1..3) })
        assertFalse(vm.current.hasFocusableResults) // idle, no recents
        assertTrue(vm.current.copy(recents = listOf("dune")).hasFocusableResults)
        vm.type("dune"); pass(500)
        assertTrue(vm.current.hasFocusableResults)
        assertFalse(vm.current.copy(phase = SearchPhase.Empty).hasFocusableResults)
        assertFalse(vm.current.copy(phase = SearchPhase.Loading, items = emptyList()).hasFocusableResults)
    }
}
