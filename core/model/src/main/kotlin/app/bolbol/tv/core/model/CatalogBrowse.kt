package app.bolbol.tv.core.model

// v1.6 categories and recommendations (docs/plan.md §v1.6). Answered on the TV from the site's own daily index
// (core:data LocalSearchIndex), so every filter combines, works instantly and includes titles added today.

/** Sort orders for [CatalogFilter]. */
enum class CatalogSort {
    /** Most recently added to the site first (the default: "always the latest"). */
    NEWEST_ADDED,
    /** Highest IMDb score first (titles without a score last). */
    TOP_IMDB,
    /** Newest release year first. */
    NEWEST_YEAR,
}

/**
 * A combination of filters for the «فیلم» / «سریال» pages and the local Home rows. Null / false means "any".
 * [genre] and [country] are the site's own Persian names (from [CatalogFacets]); [decade] is e.g. 2020 for 2020-2029,
 * or [OLDER] for everything before 1980.
 */
data class CatalogFilter(
    val type: TitleType? = null,
    val genre: String? = null,
    val country: String? = null,
    val dubbedOnly: Boolean = false,
    val decade: Int? = null,
    val minImdb: Float? = null,
    /** Only titles the site added in the last N days. */
    val addedWithinDays: Int? = null,
    val sort: CatalogSort = CatalogSort.NEWEST_ADDED,
) {
    companion object {
        const val OLDER = 1900
    }
}

/** The choices for the filter pickers of one kind, most common first. */
data class CatalogFacets(
    val genres: List<String>,
    val countries: List<String>,
    /** Decades present, newest first, e.g. 2020, 2010, …, then [CatalogFilter.OLDER]. */
    val decades: List<Int>,
    val dubbedCount: Int,
    val total: Int,
)

/** A Home row computed on the TV; [viewAll] opens the same filter on the browse page. */
data class LocalHomeRow(
    val id: String,
    val title: String,
    val items: List<TitleSummary>,
    val viewAll: CatalogFilter?,
)
