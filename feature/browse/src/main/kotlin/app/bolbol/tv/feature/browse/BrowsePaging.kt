package app.bolbol.tv.feature.browse

import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.TitleSummary

/** Grid columns (docs/design-system.md: 5 posters of 140 dp across the 864 dp safe width). */
internal const val GRID_COLUMNS = 5

/** Prefetch the next page when the focused poster is within this many rows of the end. */
internal const val PREFETCH_ROWS = 2

/** Give up chasing pages that add nothing new (all duplicates / empty) after this many in a row. */
internal const val MAX_EMPTY_PAGES = 3

/**
 * [existing] followed by the items of [incoming] whose id is not in it yet (order kept). The API
 * can repeat a title across pages when the catalogue changes between requests, and the grid
 * needs unique keys. Returns the same list instance when nothing new arrived.
 */
internal fun mergeUnique(existing: List<TitleSummary>, incoming: List<TitleSummary>): List<TitleSummary> {
    if (incoming.isEmpty()) return existing
    val seen = HashSet<Long>(existing.size + incoming.size)
    existing.forEach { seen.add(it.id) }
    val merged = ArrayList<TitleSummary>(existing.size + incoming.size)
    merged.addAll(existing)
    for (item in incoming) if (seen.add(item.id)) merged.add(item)
    return if (merged.size == existing.size) existing else merged
}

/** Everything loaded so far for one query. [page] = last page merged in (0 = nothing yet). */
internal data class PagingState(
    val items: List<TitleSummary> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 0,
) {
    val nextPage: Int get() = page + 1

    /** False once the last page was merged in (the server's `total_page`). */
    val hasMore: Boolean get() = page == 0 || page < totalPages

    /**
     * Merges [result] (the answer to [requestedPage]). Progress is tracked by the page we asked
     * for, not the one the server echoes, so a misbehaving server can never make us loop.
     */
    fun append(requestedPage: Int, result: Page<TitleSummary>): PagingState =
        PagingState(mergeUnique(items, result.items), requestedPage, result.totalPages)
}

/**
 * Whether focusing item [focusedIndex] (of [itemCount], laid out in [columns] columns) should
 * fetch the next page: the focus is within the last [rowsAhead] rows, more pages exist and no
 * request is running or waiting for a manual retry ([busy]).
 */
internal fun shouldLoadMore(
    focusedIndex: Int,
    itemCount: Int,
    columns: Int,
    hasMore: Boolean,
    busy: Boolean,
    rowsAhead: Int = PREFETCH_ROWS,
): Boolean {
    if (!hasMore || busy || columns <= 0 || itemCount <= 0 || focusedIndex !in 0 until itemCount) return false
    val lastRow = (itemCount - 1) / columns
    val focusedRow = focusedIndex / columns
    return focusedRow >= lastRow - (rowsAhead - 1)
}
