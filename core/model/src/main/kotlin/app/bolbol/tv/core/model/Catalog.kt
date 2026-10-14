package app.bolbol.tv.core.model

/**
 * Domain types for the catalogue. These are the contract between the data layer
 * (core:network/core:database) and the UI (feature:*). DTO quirks never leak past
 * the mappers in core:network (see docs/api.md).
 */

enum class TitleType { MOVIE, SERIES }

/** Audio flavour of a stream: original audio + Persian subtitle, or Persian dub. */
enum class AudioType { SUBTITLED, DUBBED }

/** Lightweight item for rows, grids, search results and "related". */
data class TitleSummary(
    val id: Long,
    /** null when unknown (home items carry no type); resolve lazily from details. */
    val type: TitleType?,
    /** Display title with HTML entities decoded and the Persian type prefix removed, e.g. "The Naked Gun 2025". */
    val title: String,
    /** Persian title, may be blank. */
    val faTitle: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: Int?,
    /** IMDb rating, null when absent ("0.0" in the API). */
    val imdbRating: Float?,
    val hasDub: Boolean,
    val hasSubtitle: Boolean,
)

data class Person(val id: Long, val name: String, val photoUrl: String?)

/** One playable file for a movie or an episode. */
data class StreamOption(
    /** Vertical resolution: 1080, 720, 480 ... */
    val quality: Int,
    val audio: AudioType,
    val url: String,
    /** Persian WebVTT subtitle, null for dubbed streams. */
    val subtitleUrl: String?,
    /** Human label for the picker, e.g. "BluRay 1080p". */
    val label: String,
    /** Human size, e.g. "2.42 GB", may be null. */
    val sizeText: String?,
)

data class Episode(
    val season: Int,
    val number: Int,
    val title: String,
    /** All qualities/audio types available for this episode. */
    val streams: List<StreamOption>,
)

data class Season(
    val number: Int,
    val episodes: List<Episode>,
)

data class TitleDetails(
    val summary: TitleSummary,
    val type: TitleType,
    val plot: String,
    val runtimeMinutes: Int?,
    val ageRating: String?,
    val metacritic: Int?,
    val genres: List<String>,
    val countries: List<String>,
    val actors: List<Person>,
    val directors: List<Person>,
    val trailerUrl: String?,
    /** Banner like "Season 1 episode 2 added", may be null. */
    val updateText: String?,
    /** Movie streams; empty for series. */
    val streams: List<StreamOption>,
    /** Series seasons sorted by number; empty for movies. */
    val seasons: List<Season>,
    val related: List<TitleSummary>,
)

/** Filters for list endpoints, taken from a home row's "view all" or a category page. */
data class ListQuery(
    val category: Category,
    val country: Int? = null,
    val genre: Int? = null,
    val imdbTop250: Boolean = false,
)

enum class Category { MOVIES, SERIES, CARTOONS, DOCUMENTARIES, SUGGESTIONS }

/** A genre usable as [ListQuery.genre]. Ids differ between movies and series (docs/api.md quirk 13). */
data class Genre(val id: Int, val name: String)

data class Page<T>(val items: List<T>, val page: Int, val totalPages: Int) {
    val hasNext: Boolean get() = page < totalPages
}

/** One home row as returned by the server (row order preserved). */
data class HomeSection(
    /** Stable id within one feed: index-based because the API's keys repeat. */
    val id: String,
    val title: String,
    val items: List<TitleSummary>,
    val viewAll: ListQuery?,
)

/** Home feed; [fromCache] is true while showing cached rows before the network refresh lands. */
data class HomeFeed(val sections: List<HomeSection>, val fromCache: Boolean)
