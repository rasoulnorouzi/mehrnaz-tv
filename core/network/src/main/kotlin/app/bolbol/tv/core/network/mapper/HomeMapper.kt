package app.bolbol.tv.core.network.mapper

import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.HomeSection
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.network.dto.HomeEnvelopeDto
import app.bolbol.tv.core.network.dto.HomeSectionDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A section's `view_all` block, reduced to what the app needs. */
internal data class ViewAll(
    val category: Category?,
    val country: Int?,
    val genre: Int?,
    val imdbTop250: Boolean,
)

/** `.../wp-json/mapi/v1/post/movies` -> MOVIES; unknown or missing -> null. */
internal fun categoryFromPath(url: String): Category? {
    val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
    return when (path.substringAfterLast('/').lowercase()) {
        "movies" -> Category.MOVIES
        "series" -> Category.SERIES
        "cartoons" -> Category.CARTOONS
        "documentaries" -> Category.DOCUMENTARIES
        "suggestions" -> Category.SUGGESTIONS
        else -> null
    }
}

private fun JsonElement?.asPrimitive(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

internal fun parseViewAll(element: JsonElement?): ViewAll? {
    val obj = element as? JsonObject ?: return null
    val url = obj["url"].asPrimitive()?.content ?: return null
    // The key is misspelled by the API ("paramters"); it is `{}` when empty and might be `[]`.
    val params = obj["paramters"] as? JsonObject ?: obj["parameters"] as? JsonObject
    fun int(name: String): Int? = params?.get(name).asPrimitive()?.content?.trim()?.toIntOrNull()
    return ViewAll(
        category = categoryFromPath(url),
        country = int("country")?.takeIf { it > 0 },
        genre = int("genres")?.takeIf { it > 0 },
        imdbTop250 = (int("imdbtop250") ?: 0) != 0,
    )
}

/** Fallback when `view_all` is missing: the section `key` (`movies`, `series`, ...). */
private fun categoryFromKey(key: String): Category? = categoryFromPath(key)

private data class ParsedSection(
    val index: Int,
    val dto: HomeSectionDto,
    val category: Category?,
    val viewAll: ViewAll?,
)

/**
 * `post/all` envelope -> home rows (docs/api.md section 5.1):
 *  - `id` is the row's index in the server list (keys repeat); stable as long as the server order is.
 *  - Empty rows are dropped. Duplicate ids inside a row are dropped.
 *  - `type` of each item comes from the row's `view_all` path (`series` -> SERIES, `movies` -> MOVIE, else null).
 *  - `viewAll` is built from the `view_all` path and `paramters`. A SERIES row whose genre id is also used by
 *    a movie row (`key == "movies"`) in the same feed is the server's copy/paste bug (WordPress term ids are
 *    globally unique, so a series row can never legitimately use a movie genre); that genre is dropped.
 */
internal fun HomeEnvelopeDto.toHomeSections(): List<HomeSection> {
    val sections = data?.sections.orEmpty()
    val parsed = sections.mapIndexed { index, dto ->
        val viewAll = parseViewAll(dto.viewAll)
        ParsedSection(index, dto, viewAll?.category ?: categoryFromKey(dto.key), viewAll)
    }
    // Only genuine movie rows (`key == "movies"`): the cartoons row also points at /post/movies but with a series id.
    val movieGenres: Set<Int> = parsed
        .filter { it.category == Category.MOVIES && it.dto.key.equals("movies", ignoreCase = true) }
        .mapNotNull { it.viewAll?.genre }
        .toSet()

    return parsed.mapNotNull { section ->
        val rowType: TitleType? = when (section.category) {
            Category.SERIES -> TitleType.SERIES
            Category.MOVIES -> TitleType.MOVIE
            else -> null
        }
        val items = section.dto.items
            .mapNotNull { it.toSummary(rowType) }
            .distinctBy { it.id }
        if (items.isEmpty()) return@mapNotNull null

        val viewAll = section.viewAll
        val query = if (viewAll?.category != null) {
            val genre = viewAll.genre?.takeUnless { section.category == Category.SERIES && it in movieGenres }
            ListQuery(
                category = viewAll.category,
                country = viewAll.country,
                genre = genre,
                imdbTop250 = viewAll.imdbTop250,
            )
        } else {
            null
        }
        HomeSection(
            id = section.index.toString(),
            title = section.dto.title.decodeHtmlEntities().trim(),
            items = items,
            viewAll = query,
        )
    }
}
