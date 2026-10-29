package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.HomeSection
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.model.TitleSummary

/**
 * Network side of the catalogue. Everything returned is a domain type (DTOs never leave this module);
 * every failure is a [app.bolbol.tv.core.model.CatalogException]. Mirror failover is already handled below this interface.
 */
interface CatalogRemoteDataSource {

    /** Raw `post/all` body, so the caller can persist it verbatim and re-parse it later with [parseHome]. */
    suspend fun fetchHomeJson(): String

    /**
     * Parses a `post/all` body (fresh or cached) into rows: empty rows hidden, ids unique.
     * Throws `CatalogException.BadResponse` when the text is not a valid home payload.
     */
    fun parseHome(json: String): List<HomeSection>

    suspend fun details(id: Long): TitleDetails

    /** A server error "not found" (e.g. a page past the end) yields an empty page, not an exception. */
    suspend fun list(query: ListQuery, page: Int): Page<TitleSummary>

    /** Blank text returns an empty page without a request. */
    suspend fun search(text: String, page: Int = 1): Page<TitleSummary>

    /**
     * Streams the website's own search index (all ~38k titles, rebuilt by the site every day) into [sink], one title
     * at a time, without holding the whole file in memory. [ifModifiedSince] is the `Last-Modified` of the copy we
     * already have; the answer is then [SearchIndexFetch.NotModified] and [sink] is not called. Blocking I/O: call it
     * off the main thread. Default: not supported (fakes).
     */
    suspend fun fetchSearchIndex(ifModifiedSince: String?, sink: (IndexedTitle) -> Unit): SearchIndexFetch =
        SearchIndexFetch.NotModified
}

/** One title of the site's index: the card data plus what the v1.6 filters need. */
data class IndexedTitle(
    val summary: TitleSummary,
    /** Persian genre names, tidied (see docs/api.md "Search index"). */
    val genres: List<String>,
    /** Persian country names. */
    val countries: List<String>,
    /** When the site added it (epoch seconds), 0 when unknown. */
    val addedAtEpochSec: Long,
)

/** Result of [CatalogRemoteDataSource.fetchSearchIndex]. */
sealed interface SearchIndexFetch {
    data object NotModified : SearchIndexFetch
    /** Every entry went to the sink; [lastModified] is the server's header for the next conditional request. */
    data class Updated(val lastModified: String?, val count: Int) : SearchIndexFetch
}
