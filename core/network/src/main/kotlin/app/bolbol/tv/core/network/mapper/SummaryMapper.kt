package app.bolbol.tv.core.network.mapper

import app.bolbol.tv.core.network.dto.SearchIndexEntryDto

import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.Person
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.network.dto.PagedEnvelopeDto
import app.bolbol.tv.core.network.dto.PersonDto
import app.bolbol.tv.core.network.dto.RelatedDto
import app.bolbol.tv.core.network.dto.SummaryDto

/** `"movie"` -> MOVIE, `"serie"`/`"series"` -> SERIES, anything else (including blank) -> null. */
internal fun parseType(raw: String): TitleType? = when (raw.trim().lowercase()) {
    "movie" -> TitleType.MOVIE
    "serie", "series" -> TitleType.SERIES
    else -> null
}

/** The kind of title a list category contains, when it is unambiguous. */
internal fun Category.impliedType(): TitleType? = when (this) {
    Category.MOVIES -> TitleType.MOVIE
    Category.SERIES -> TitleType.SERIES
    Category.CARTOONS, Category.DOCUMENTARIES, Category.SUGGESTIONS -> null
}

/**
 * List/search/home item -> [TitleSummary]. The item's own `type` wins; [fallbackType] (derived from
 * the row or category) is used when the payload has none (home items). Null for rows without an id.
 */
internal fun SummaryDto.toSummary(fallbackType: TitleType?, images: ImageUrls = ImageUrls.Default): TitleSummary? {
    if (id <= 0L) return null
    return TitleSummary(
        id = id,
        type = parseType(type) ?: fallbackType,
        title = cleanTitle(title),
        faTitle = faTitle.decodeHtmlEntities().trim(),
        posterUrl = images.normalize(thumbnail),
        backdropUrl = images.normalize(background),
        year = parseYear(release),
        imdbRating = parseImdb(imdbRate),
        hasDub = dubbed,
        hasSubtitle = subtitle,
    )
}

/** `related_posts[]` -> [TitleSummary]; the payload has no Persian title, rating or dub/sub flags. */
internal fun RelatedDto.toSummary(images: ImageUrls = ImageUrls.Default): TitleSummary? {
    if (id <= 0L) return null
    return TitleSummary(
        id = id,
        type = parseType(type),
        title = cleanTitle(title),
        faTitle = "",
        posterUrl = images.normalize(image),
        backdropUrl = images.normalize(backgroundImage),
        year = parseYear(release),
        imdbRating = null,
        hasDub = false,
        hasSubtitle = false,
    )
}

/** Placeholder person the server uses when the director is unknown (docs/api.md quirk 14). */
private const val UNKNOWN_PERSON = "unknown"

internal fun PersonDto.toPerson(images: ImageUrls = ImageUrls.Default): Person? {
    val cleaned = name.decodeHtmlEntities().trim()
    if (id <= 0L || cleaned.isEmpty() || cleaned.equals(UNKNOWN_PERSON, ignoreCase = true)) return null
    return Person(id = id, name = cleaned, photoUrl = images.normalize(profileActor))
}

/**
 * Paged envelope -> [Page]. [requestedPage] fills in a missing `page`; `totalPages` is never below
 * the current page. Duplicate ids inside one page are dropped (LazyGrid keys must be unique).
 */
internal fun PagedEnvelopeDto.toPage(
    requestedPage: Int,
    category: Category?,
    images: ImageUrls = ImageUrls.Default,
): Page<TitleSummary> {
    val fallbackType = category?.impliedType()
    val currentPage = if (page > 0) page else requestedPage
    return Page(
        items = data.mapNotNull { it.toSummary(fallbackType, images) }.distinctBy { it.id },
        page = currentPage,
        totalPages = maxOf(totalPage, currentPage),
    )
}

internal fun emptyPage(page: Int): Page<TitleSummary> = Page(emptyList(), page, page)

/** Site search-index entry -> [TitleSummary] (no backdrop in the index). Null for entries without an id. */
internal fun SearchIndexEntryDto.toSummary(images: ImageUrls = ImageUrls.Default): TitleSummary? {
    if (postId <= 0L) return null
    val kind = when (type.trim().lowercase()) {
        "series", "serie" -> TitleType.SERIES
        "movies", "movie" -> TitleType.MOVIE
        else -> parseType(type)
    }
    return TitleSummary(
        id = postId,
        type = kind,
        title = cleanTitle(title),
        faTitle = faTitle.decodeHtmlEntities().trim(),
        posterUrl = images.normalize(thumb),
        backdropUrl = null,
        year = parseYear(year),
        imdbRating = parseImdb(imdbRate),
        hasDub = hasDubbed,
        hasSubtitle = hasSubtitle,
    )
}
