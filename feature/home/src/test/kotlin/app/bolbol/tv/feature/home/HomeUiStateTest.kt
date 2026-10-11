package app.bolbol.tv.feature.home

import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.ListQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeUiStateTest {

    private fun content(feed: FeedState.Loaded, cw: List<ContinueWatchingItem> = emptyList()) =
        buildHomeUiState(feed, cw) as HomeUiState.Content

    @Test fun emptyRowsAreDroppedAndDuplicatesRemoved() {
        val state = content(
            FeedState.Loaded(
                listOf(
                    section("a", 1, 2, 1), // duplicate title inside a row
                    section("empty"),
                    section("a", 3), // duplicate row id
                    section("b", 1), // the same title in another row is fine
                ),
            ),
        )
        assertEquals(listOf("section:a", "section:b"), state.rows.map { it.id })
        assertEquals(listOf(1L, 2L), (state.rows[0] as HomeRow.Titles).items.map { it.id })
    }

    @Test fun continueWatchingIsFirstAndOneEntryPerTitle() {
        val state = content(
            FeedState.Loaded(listOf(section("a", 1))),
            listOf(resumable(5, 1, 2), resumable(5, 1, 1), resumable(6)),
        )
        val cw = state.rows.first() as HomeRow.ContinueWatching
        assertEquals(listOf(5L, 6L), cw.items.map { it.title.id })
    }

    @Test fun anEmptyFeedBecomesARetryableError() {
        assertEquals(
            HomeUiState.Error(HomeError.GENERIC),
            buildHomeUiState(FeedState.Loaded(listOf(section("empty"))), emptyList()),
        )
    }

    @Test fun loadingAndFailedFeedsMapDirectly() {
        assertEquals(HomeUiState.Loading, buildHomeUiState(FeedState.Loading, listOf(resumable(1))))
        assertEquals(
            HomeUiState.Error(HomeError.OFFLINE),
            buildHomeUiState(FeedState.Failed(HomeError.OFFLINE), listOf(resumable(1))),
        )
    }

    // ---- focus restoration ----

    private val rows = content(
        FeedState.Loaded(listOf(section("a", 1, 2, 3), section("b", 4, 5))),
        listOf(resumable(9)),
    ).rows // continue_watching, section:a, section:b

    @Test fun withoutMemoryFocusStartsOnTheFirstCardOfTheFirstRow() {
        val focus = rows.resolveInitialFocus(null)!!
        assertEquals(0, focus.rowIndex)
        assertEquals(0, focus.itemIndex)
        assertEquals(HomeRow.CONTINUE_ROW_ID, focus.rowId)
        assertEquals(9L, focus.title.id)
    }

    @Test fun rememberedCardIsRestored() {
        val focus = rows.resolveInitialFocus(FocusTarget("section:a", 3))!!
        assertEquals(1, focus.rowIndex)
        assertEquals(2, focus.itemIndex)
        assertEquals(3L, focus.title.id)
    }

    @Test fun missingCardFallsBackToTheStartOfItsRow() {
        val focus = rows.resolveInitialFocus(FocusTarget("section:b", 99))!!
        assertEquals(2, focus.rowIndex)
        assertEquals(0, focus.itemIndex)
        assertEquals(4L, focus.title.id)
    }

    @Test fun missingRowFallsBackToTheFirstRow() {
        val focus = rows.resolveInitialFocus(FocusTarget("section:gone", 1))!!
        assertEquals(0, focus.rowIndex)
    }

    @Test fun noCardsMeansNoFocus() {
        assertNull(emptyList<HomeRow>().resolveInitialFocus(FocusTarget("x", 1)))
    }

    @Test fun errorKindsComeFromTheExceptionType() {
        assertEquals(HomeError.OFFLINE, CatalogException.Offline().toHomeError())
        assertEquals(HomeError.SERVER_DOWN, CatalogException.AllMirrorsDown().toHomeError())
        assertEquals(HomeError.GENERIC, RuntimeException().toHomeError())
    }

    // ---- view all ----

    @Test fun aRowKeepsItsViewAllQuery() {
        val query = ListQuery(Category.MOVIES, imdbTop250 = true)
        val state = content(
            FeedState.Loaded(listOf(section("a", 1, viewAll = query), section("b", 2))),
        )
        assertEquals(query, (state.rows[0] as HomeRow.Titles).viewAll)
        assertNull((state.rows[1] as HomeRow.Titles).viewAll)
    }

    // ---- progress bar ----

    @Test fun startedTitlesAlwaysShowAVisibleSliver() {
        assertEquals(0f, visibleProgress(0f), 0f)
        assertEquals(0f, visibleProgress(-0.2f), 0f)
        assertEquals(0f, visibleProgress(Float.NaN), 0f)
        assertEquals(MIN_VISIBLE_PROGRESS, visibleProgress(60_000f / 10_500_000f), 0f) // 1 min of 2 h 55 m
        assertEquals(0.5f, visibleProgress(0.5f), 0f)
        assertEquals(1f, visibleProgress(1.4f), 0f)
    }

    // ---- focus after a card disappears ----

    private val twoRows = content(
        FeedState.Loaded(listOf(section("a", 1, 2, 3), section("b", 4))),
        listOf(resumable(7), resumable(8), resumable(9)),
    ).rows // continue_watching (7, 8, 9), section:a (1, 2, 3), section:b (4)

    @Test fun focusMovesToTheCardThatSlidesIntoThePlace() {
        assertEquals(FocusTarget(HomeRow.CONTINUE_ROW_ID, 9), twoRows.neighbourFocus(FocusTarget(HomeRow.CONTINUE_ROW_ID, 8)))
        assertEquals(FocusTarget(HomeRow.CONTINUE_ROW_ID, 8), twoRows.neighbourFocus(FocusTarget(HomeRow.CONTINUE_ROW_ID, 7)))
    }

    @Test fun theLastCardFallsBackToThePreviousOne() {
        assertEquals(FocusTarget(HomeRow.CONTINUE_ROW_ID, 8), twoRows.neighbourFocus(FocusTarget(HomeRow.CONTINUE_ROW_ID, 9)))
    }

    @Test fun anEmptiedRowHandsFocusToTheRowBelow() {
        val rows = content(FeedState.Loaded(listOf(section("a", 1, 2))), listOf(resumable(7))).rows
        assertEquals(FocusTarget("section:a", 1), rows.neighbourFocus(FocusTarget(HomeRow.CONTINUE_ROW_ID, 7)))
    }

    @Test fun theBottomRowFallsBackToTheRowAbove() {
        val rows = content(FeedState.Loaded(listOf(section("a", 1, 2), section("b", 4)))).rows
        assertEquals(FocusTarget("section:a", 1), rows.neighbourFocus(FocusTarget("section:b", 4)))
    }

    @Test fun noOtherCardMeansNoTarget() {
        val rows = listOf<HomeRow>(HomeRow.Titles("section:a", "A", listOf(title(1))))
        assertNull(rows.neighbourFocus(FocusTarget("section:a", 1)))
    }
}
