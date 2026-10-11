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
}
