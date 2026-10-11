package app.bolbol.tv.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Wire DTOs. They mirror docs/api.md closely and never leave :core:network.
 * Rules: every property has a default; scalars the server types inconsistently are read as
 * strings (LenientString) and converted in the mappers; flags go through FlagSerializer;
 * every list goes through a MapOrList serializer because PHP turns sparse arrays into objects.
 */

// ---------------------------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------------------------

/** `{"success":false,"message":"..."}`; the message is Persian and never parsed for logic. */
@Serializable
internal data class ApiErrorDto(
    @Serializable(with = FlagSerializer::class) val success: Boolean = false,
    @Serializable(with = LenientStringSerializer::class) val message: String = "",
)

/** Genre, country or language. */
@Serializable
internal data class TermDto(
    @Serializable(with = LenientLongSerializer::class) val id: Long = 0,
    @Serializable(with = LenientStringSerializer::class) val name: String = "",
)

/** Actor or director. `profile_actor` is the photo for both. */
@Serializable
internal data class PersonDto(
    @Serializable(with = LenientLongSerializer::class) val id: Long = 0,
    @Serializable(with = LenientStringSerializer::class) val name: String = "",
    @SerialName("profile_actor")
    @Serializable(with = LenientStringSerializer::class) val profileActor: String = "",
)

/**
 * One item of a list endpoint, of search, and of a home row. Home items lack `type` and `release`.
 * Only the fields the domain needs are declared; the rest (plot, genres, cast ...) is skipped.
 */
@Serializable
internal data class SummaryDto(
    @Serializable(with = LenientLongSerializer::class) val id: Long = 0,
    @Serializable(with = LenientStringSerializer::class) val title: String = "",
    @SerialName("fa_title")
    @Serializable(with = LenientStringSerializer::class) val faTitle: String = "",
    @Serializable(with = LenientStringSerializer::class) val type: String = "",
    @Serializable(with = FlagSerializer::class) val dubbed: Boolean = false,
    @Serializable(with = FlagSerializer::class) val subtitle: Boolean = false,
    @Serializable(with = LenientStringSerializer::class) val release: String = "",
    @SerialName("imdb_rate")
    @Serializable(with = LenientStringSerializer::class) val imdbRate: String = "",
    @Serializable(with = LenientStringSerializer::class) val thumbnail: String = "",
    @Serializable(with = LenientStringSerializer::class) val background: String = "",
)

// ---------------------------------------------------------------------------------------------
// Home: GET post/all
// ---------------------------------------------------------------------------------------------

@Serializable
internal data class HomeEnvelopeDto(
    @Serializable(with = FlagSerializer::class) val success: Boolean = false,
    val data: HomeDataDto? = null,
)

@Serializable
internal data class HomeDataDto(
    @Serializable(with = HomeSectionListSerializer::class) val sections: List<HomeSectionDto> = emptyList(),
)

@Serializable
internal data class HomeSectionDto(
    @Serializable(with = LenientStringSerializer::class) val key: String = "",
    @Serializable(with = LenientStringSerializer::class) val title: String = "",
    /** `{url, paramters}`; kept raw because `paramters` is `{}` or `[]` and values are ints. */
    @SerialName("view_all") val viewAll: JsonElement? = null,
    @Serializable(with = SummaryListSerializer::class) val items: List<SummaryDto> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// Paged lists: post/movies|series|cartoons|documentaries|suggestions and post/search
// ---------------------------------------------------------------------------------------------

@Serializable
internal data class PagedEnvelopeDto(
    @Serializable(with = FlagSerializer::class) val success: Boolean = false,
    @SerialName("per_page")
    @Serializable(with = LenientIntSerializer::class) val perPage: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val page: Int = 0,
    @SerialName("total_page")
    @Serializable(with = LenientIntSerializer::class) val totalPage: Int = 0,
    @Serializable(with = SummaryListSerializer::class) val data: List<SummaryDto> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// Detail: GET post/{id}
// ---------------------------------------------------------------------------------------------

@Serializable
internal data class DetailEnvelopeDto(
    @Serializable(with = FlagSerializer::class) val success: Boolean = false,
    val data: DetailDto? = null,
)

@Serializable
internal data class DetailDto(
    @Serializable(with = LenientLongSerializer::class) val id: Long = 0,
    /** `"movie"` or `"serie"`. */
    @Serializable(with = LenientStringSerializer::class) val type: String = "",
    @Serializable(with = LenientStringSerializer::class) val title: String = "",
    @SerialName("fa_title")
    @Serializable(with = LenientStringSerializer::class) val faTitle: String = "",
    @Serializable(with = LenientStringSerializer::class) val image: String = "",
    @SerialName("background_image")
    @Serializable(with = LenientStringSerializer::class) val backgroundImage: String = "",
    @SerialName("has_dubbed")
    @Serializable(with = FlagSerializer::class) val hasDubbed: Boolean = false,
    @SerialName("has_subtitle")
    @Serializable(with = FlagSerializer::class) val hasSubtitle: Boolean = false,
    @Serializable(with = LenientStringSerializer::class) val release: String = "",
    @SerialName("imdb_rate")
    @Serializable(with = LenientStringSerializer::class) val imdbRate: String = "",
    /** `"64%"`. */
    @SerialName("metacritic_rate")
    @Serializable(with = LenientStringSerializer::class) val metacriticRate: String = "",
    @Serializable(with = LenientStringSerializer::class) val age: String = "",
    /** `"85 دقیقه"`. */
    @Serializable(with = LenientStringSerializer::class) val runtime: String = "",
    @SerialName("fa_plot")
    @Serializable(with = LenientStringSerializer::class) val faPlot: String = "",
    @Serializable(with = LenientStringSerializer::class) val trailer: String = "",
    @SerialName("update_text")
    @Serializable(with = LenientStringSerializer::class) val updateText: String = "",
    @Serializable(with = TermListSerializer::class) val countries: List<TermDto> = emptyList(),
    @Serializable(with = TermListSerializer::class) val genres: List<TermDto> = emptyList(),
    @Serializable(with = PersonListSerializer::class) val actors: List<PersonDto> = emptyList(),
    @Serializable(with = PersonListSerializer::class) val directors: List<PersonDto> = emptyList(),
    /** Movie: object keyed by numeric strings. Series: array of blocks. Both tolerated for either kind. */
    @SerialName("download_links")
    @Serializable(with = DownloadEntryListSerializer::class) val downloadLinks: List<DownloadEntryDto> = emptyList(),
    @SerialName("player_links")
    @Serializable(with = PlayerEntryListSerializer::class) val playerLinks: List<PlayerEntryDto> = emptyList(),
    @SerialName("related_posts")
    @Serializable(with = RelatedListSerializer::class) val relatedPosts: List<RelatedDto> = emptyList(),
)

/** `related_posts[]`: `{id, type, title, image, background_image, release}`. */
@Serializable
internal data class RelatedDto(
    @Serializable(with = LenientLongSerializer::class) val id: Long = 0,
    @Serializable(with = LenientStringSerializer::class) val type: String = "",
    @Serializable(with = LenientStringSerializer::class) val title: String = "",
    @Serializable(with = LenientStringSerializer::class) val image: String = "",
    @SerialName("background_image")
    @Serializable(with = LenientStringSerializer::class) val backgroundImage: String = "",
    @Serializable(with = LenientStringSerializer::class) val release: String = "",
)

/**
 * One `download_links` element. Union of the movie entry (dl_link ...) and the series block
 * (name/quality/type/items); the unused half stays at its defaults.
 */
@Serializable
internal data class DownloadEntryDto(
    /** Original key of the entry (movie: `"0"`, `"3"` ...), injected by MapOrListSerializer. */
    @SerialName(KEY_FIELD) val key: String = "",
    // movie entry
    @SerialName("dl_link")
    @Serializable(with = LenientStringSerializer::class) val dlLink: String = "",
    /** `"BluRay 1080p"`. */
    @SerialName("quality_link")
    @Serializable(with = LenientStringSerializer::class) val qualityLink: String = "",
    /** `"2.42 GigaByte "`. */
    @SerialName("dl_capacity")
    @Serializable(with = LenientStringSerializer::class) val dlCapacity: String = "",
    @SerialName("dl_sub")
    @Serializable(with = LenientStringSerializer::class) val dlSub: String = "",
    /** `"sub"` or `"dub"`. */
    @SerialName("link_type")
    @Serializable(with = LenientStringSerializer::class) val linkType: String = "",
    // series block
    /** `"Season 1"`. */
    @Serializable(with = LenientStringSerializer::class) val name: String = "",
    /** `"WEB-DL 1080p"`. */
    @Serializable(with = LenientStringSerializer::class) val quality: String = "",
    @Serializable(with = LenientStringSerializer::class) val capacity: String = "",
    /** `"sub"` / `"dub"`. */
    @Serializable(with = LenientStringSerializer::class) val type: String = "",
    @Serializable(with = DownloadItemListSerializer::class) val items: List<DownloadItemDto> = emptyList(),
)

@Serializable
internal data class DownloadItemDto(
    /** `"Episode 1"`. */
    @Serializable(with = LenientStringSerializer::class) val title: String = "",
    @Serializable(with = LenientStringSerializer::class) val link: String = "",
    @Serializable(with = LenientStringSerializer::class) val subtitle: String = "",
)

/** One `player_links` element: union of the movie entry (play_link ...) and the series block. */
@Serializable
internal data class PlayerEntryDto(
    @SerialName(KEY_FIELD) val key: String = "",
    // movie entry
    @SerialName("play_link")
    @Serializable(with = LenientStringSerializer::class) val playLink: String = "",
    @SerialName("fasub_link")
    @Serializable(with = LenientStringSerializer::class) val fasubLink: String = "",
    @SerialName("ensub_link")
    @Serializable(with = LenientStringSerializer::class) val ensubLink: String = "",
    /** `"1080"`. */
    @SerialName("quality_link")
    @Serializable(with = LenientStringSerializer::class) val qualityLink: String = "",
    /** `"sub"` / `"dub"`; empty for series blocks. */
    @SerialName("type_link")
    @Serializable(with = LenientStringSerializer::class) val typeLink: String = "",
    // series block
    @SerialName("season_name")
    @Serializable(with = LenientStringSerializer::class) val seasonName: String = "",
    @Serializable(with = PlayerItemListSerializer::class) val items: List<PlayerItemDto> = emptyList(),
)

@Serializable
internal data class PlayerItemDto(
    @SerialName("play_link")
    @Serializable(with = LenientStringSerializer::class) val playLink: String = "",
    @SerialName("fasub_link")
    @Serializable(with = LenientStringSerializer::class) val fasubLink: String = "",
    @SerialName("ensub_link")
    @Serializable(with = LenientStringSerializer::class) val ensubLink: String = "",
)

// ---------------------------------------------------------------------------------------------
// Tolerant list serializers (object-keyed / array / [] / null -> List)
// ---------------------------------------------------------------------------------------------

internal object SummaryListSerializer : MapOrListSerializer<SummaryDto>(SummaryDto.serializer())
internal object HomeSectionListSerializer : MapOrListSerializer<HomeSectionDto>(HomeSectionDto.serializer())
internal object TermListSerializer : MapOrListSerializer<TermDto>(TermDto.serializer())
internal object PersonListSerializer : MapOrListSerializer<PersonDto>(PersonDto.serializer())
internal object RelatedListSerializer : MapOrListSerializer<RelatedDto>(RelatedDto.serializer())
internal object DownloadItemListSerializer : MapOrListSerializer<DownloadItemDto>(DownloadItemDto.serializer())
internal object PlayerItemListSerializer : MapOrListSerializer<PlayerItemDto>(PlayerItemDto.serializer())
internal object DownloadEntryListSerializer :
    MapOrListSerializer<DownloadEntryDto>(DownloadEntryDto.serializer(), withKeys = true)
internal object PlayerEntryListSerializer :
    MapOrListSerializer<PlayerEntryDto>(PlayerEntryDto.serializer(), withKeys = true)
