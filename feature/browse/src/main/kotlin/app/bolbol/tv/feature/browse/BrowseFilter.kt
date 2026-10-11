package app.bolbol.tv.feature.browse

import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.ListQuery

/** Country taxonomy ids of the MOVIES list (docs/api.md §5.1: the home rows "ایرانی" / "ترکی"). */
internal const val COUNTRY_IRAN = 1199
internal const val COUNTRY_TURKEY = 1198

/**
 * Quick-filter chips above the grid of the Movies / Series tabs. Each chip is applied to the
 * screen's **base** query on its own (never stacked): the API's behaviour for combined filters
 * is unverified (docs/api.md §10).
 */
internal enum class BrowseFilter(val label: String) {
    All("همه"),
    Top250("۲۵۰ برتر IMDb"),
    Iranian("ایرانی"),
    Turkish("ترکی"),
}

/**
 * Chips offered for [base]: only for an unfiltered MOVIES / SERIES query (a "view all" of a row
 * already is a filter, so it gets none). Country ids differ between the movie and series
 * taxonomies (docs/api.md quirk 13), and only the movie ids are known, so the country chips are
 * movies-only.
 */
internal fun quickFiltersFor(base: ListQuery): List<BrowseFilter> {
    val unfiltered = base.country == null && base.genre == null && !base.imdbTop250
    if (!unfiltered) return emptyList()
    return when (base.category) {
        Category.MOVIES -> listOf(BrowseFilter.All, BrowseFilter.Top250, BrowseFilter.Iranian, BrowseFilter.Turkish)
        Category.SERIES -> listOf(BrowseFilter.All, BrowseFilter.Top250)
        else -> emptyList()
    }
}

/** The list query for [filter] on top of the screen's [base] query. */
internal fun ListQuery.withFilter(filter: BrowseFilter): ListQuery = when (filter) {
    BrowseFilter.All -> this
    BrowseFilter.Top250 -> copy(imdbTop250 = true)
    BrowseFilter.Iranian -> copy(country = COUNTRY_IRAN)
    BrowseFilter.Turkish -> copy(country = COUNTRY_TURKEY)
}
