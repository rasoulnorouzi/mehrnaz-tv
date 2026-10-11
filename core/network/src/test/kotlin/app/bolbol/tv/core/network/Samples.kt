package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.HomeSection
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.network.dto.DetailEnvelopeDto
import app.bolbol.tv.core.network.dto.HomeEnvelopeDto
import app.bolbol.tv.core.network.dto.PagedEnvelopeDto
import app.bolbol.tv.core.network.mapper.toDetails
import app.bolbol.tv.core.network.mapper.toHomeSections

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
}
