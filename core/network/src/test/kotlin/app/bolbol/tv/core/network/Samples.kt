package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.HomeSection
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.network.dto.DetailEnvelopeDto
import app.bolbol.tv.core.network.dto.HomeEnvelopeDto
import app.bolbol.tv.core.network.dto.PagedEnvelopeDto
import app.bolbol.tv.core.network.dto.PlayerLinksEnvelopeDto
import app.bolbol.tv.core.network.mapper.PlayableLinks
import app.bolbol.tv.core.network.mapper.mapLinks
import app.bolbol.tv.core.network.mapper.toDetails
import app.bolbol.tv.core.network.mapper.toHomeSections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Loads the real API captures copied into src/test/resources. */
internal object Samples {
    val ALL_FILES = listOf(
        "post_all.json",
        "post_movies.json",
        "post_series.json",
        "post_suggestions.json",
        "post_search_friends.json",
        "movie_353371.json",
        "serie_624236.json",
        "post_player-links.json",
        "post_search_s_friends.json",
        "serie_623954_detail_empty_links.json",
        "player-links_serie_623954.json",
        "player-links_movie_353371.json",
        "serie_623954_season_1.json",
    )

    fun text(name: String): String =
        requireNotNull(Samples::class.java.classLoader!!.getResourceAsStream(name)) { "missing test resource $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    fun home(): List<HomeSection> =
        BolboljanJson.decodeFromString(HomeEnvelopeDto.serializer(), text("post_all.json")).toHomeSections()

    fun paged(name: String): PagedEnvelopeDto =
        BolboljanJson.decodeFromString(PagedEnvelopeDto.serializer(), text(name))

    fun details(name: String): TitleDetails {
        val envelope = BolboljanJson.decodeFromString(DetailEnvelopeDto.serializer(), text(name))
        return requireNotNull(envelope.data) { "$name has no data" }.toDetails()
    }

    fun movie(): TitleDetails = details("movie_353371.json")
    fun series(): TitleDetails = details("serie_624236.json")

    /** Kill Jackie (623954): 8 subtitled episodes, 4 of them also dubbed. */
    fun dubbedSeries(): TitleDetails = details("serie_623954_detail_empty_links.json")

    /** Links of `post/player-links?id=` mapped without knowing the kind. */
    fun playerLinks(name: String): PlayableLinks {
        val data = BolboljanJson.decodeFromString(PlayerLinksEnvelopeDto.serializer(), text(name)).data
        return mapLinks(data.playerLinks, data.downloadLinks)
    }

    /** A detail sample with `download_links` and `player_links` replaced by `[]`, as the server sometimes sends. */
    fun detailWithoutLinks(name: String): String {
        val root = Json.parseToJsonElement(text(name)).jsonObject
        val data = root.getValue("data").jsonObject
        val emptied = JsonObject(data + mapOf("download_links" to JsonArray(emptyList()), "player_links" to JsonArray(emptyList())))
        return JsonObject(root + ("data" to emptied)).toString()
    }
}
