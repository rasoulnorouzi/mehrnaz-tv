package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.network.dto.DetailDto
import app.bolbol.tv.core.network.dto.DetailEnvelopeDto
import app.bolbol.tv.core.network.dto.PlayerLinksEnvelopeDto
import app.bolbol.tv.core.network.mapper.PlayableLinks
import app.bolbol.tv.core.network.mapper.mapLinks
import app.bolbol.tv.core.network.mapper.toDetails
import app.bolbol.tv.core.network.mapper.withPlayableLinks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dubbed series blocks, player-links, and the merge into a detail (docs/api.md section 11). */
class PlayerLinksMappingTest {

    private val killJackie = Samples.playerLinks("player-links_serie_623954.json")

    // --- series 623954: 8 subtitled episodes, 4 also dubbed ----------------------------------------------------

    @Test
    fun playerLinksOfTheDubbedSeriesYieldOneSeasonWithEightEpisodes() {
        assertTrue(killJackie.streams.isEmpty())
        val season = killJackie.seasons.single()
        assertEquals(1, season.number)
        assertEquals((1..8).toList(), season.episodes.map { it.number })
        assertEquals((1..8).map { "Episode $it" }, season.episodes.map { it.title })
    }

    @Test
    fun episodesOneToFourCarrySubAndDubInThreeQualities() {
        val episodes = killJackie.seasons.single().episodes
        for (episode in episodes.take(4)) {
            assertEquals("episode ${episode.number}", 6, episode.streams.size)
            val subs = episode.streams.filter { it.audio == AudioType.SUBTITLED }
            val dubs = episode.streams.filter { it.audio == AudioType.DUBBED }
            assertEquals(listOf(1080, 720, 480), subs.map { it.quality })
            assertEquals(listOf(1080, 720, 480), dubs.map { it.quality })
            assertTrue(subs.all { it.subtitleUrl!!.endsWith(".vtt") })
            assertTrue(dubs.all { it.subtitleUrl == null })
            assertTrue(subs.all { !it.url.contains("Dubbed") })
            assertTrue(dubs.all { it.url.contains("Dubbed") })
            assertEquals(listOf("WEB-DL 1080p", "WEB-DL 720p", "WEB-DL 480p"), dubs.map { it.label })
        }
    }

    @Test
    fun episodesFiveToEightExistOnlyAsSubtitled() {
        val episodes = killJackie.seasons.single().episodes
        for (episode in episodes.drop(4)) {
            assertEquals("episode ${episode.number}", 3, episode.streams.size)
            assertTrue(episode.streams.all { it.audio == AudioType.SUBTITLED && it.subtitleUrl != null })
            assertEquals(listOf(1080, 720, 480), episode.streams.map { it.quality })
        }
    }

    @Test
    fun everyStreamUrlBelongsToItsEpisode() {
        for (episode in killJackie.seasons.single().episodes) {
            val tag = "S01E0${episode.number}"
            assertTrue(episode.streams.all { it.url.contains(tag) })
        }
    }

    @Test
    fun episodeCountsMatchTheSeasonEndpointCapture() {
        // serie_623954_season_1.json lists the same files per "quality" and "quality-dub" key.
        val counts = Json.parseToJsonElement(Samples.text("serie_623954_season_1.json"))
            .jsonObject.getValue("data").jsonObject.getValue("download_links").jsonObject
            .mapValues { it.value.jsonArray.size }
        assertEquals(mapOf("WEB-DL 1080p" to 8, "WEB-DL 720p" to 8, "WEB-DL 480p" to 8, "WEB-DL 1080p-dub" to 4, "WEB-DL 720p-dub" to 4, "WEB-DL 480p-dub" to 4), counts)

        val streams = killJackie.seasons.single().episodes.flatMap { it.streams }
        for ((key, count) in counts) {
            val dub = key.endsWith("-dub")
            val label = key.removeSuffix("-dub")
            val mapped = streams.count { it.label == label && (it.audio == AudioType.DUBBED) == dub }
            assertEquals(key, count, mapped)
        }
    }

    @Test
    fun theDetailCaptureAndTheSeasonCaptureAgreeWithPlayerLinks() {
        // The detail capture (named "empty links" but carrying them) maps to the same seasons as player-links.
        assertEquals(killJackie.seasons, Samples.dubbedSeries().seasons)
    }

    // --- merging into a detail ----------------------------------------------------------------------------------

    private fun detailWithEmptyLinks(name: String = "serie_623954_detail_empty_links.json"): DetailDto =
        BolboljanJson.decodeFromString(DetailEnvelopeDto.serializer(), Samples.detailWithoutLinks(name)).data!!

    @Test
    fun aDetailWithEmptyLinksYieldsPlayableSeasonsOncePlayerLinksAreMerged() {
        val bare = detailWithEmptyLinks().toDetails()
        assertEquals(TitleType.SERIES, bare.type)
        assertTrue(bare.seasons.isEmpty())

        val merged = bare.withPlayableLinks(killJackie)
        assertEquals(8, merged.seasons.single().episodes.size)
        assertEquals(6, merged.seasons.single().episodes[0].streams.size)
        assertEquals(3, merged.seasons.single().episodes[7].streams.size)
        assertTrue(merged.streams.isEmpty())
        // Everything but the links is untouched.
        assertEquals(bare.copy(summary = merged.summary, seasons = merged.seasons), merged)
    }

    @Test
    fun mergingWidensTheDubAndSubtitleFlags() {
        val bare = detailWithEmptyLinks().toDetails().let { it.copy(summary = it.summary.copy(hasDub = false, hasSubtitle = false)) }
        val merged = bare.withPlayableLinks(killJackie)
        assertTrue(merged.summary.hasDub)
        assertTrue(merged.summary.hasSubtitle)
    }

    @Test
    fun nullEmptyOrWrongKindLinksKeepTheDetailAsIs() {
        val details = Samples.dubbedSeries()
        assertSame(details, details.withPlayableLinks(null))
        assertSame(details, details.withPlayableLinks(PlayableLinks(emptyList(), emptyList())))
        // A series detail never takes movie streams, a movie detail never takes seasons.
        val movieLinks = Samples.playerLinks("player-links_movie_353371.json")
        assertSame(details, details.withPlayableLinks(movieLinks))
        val movie = Samples.movie()
        assertSame(movie, movie.withPlayableLinks(killJackie))
    }

    @Test
    fun playerLinksReplaceTheEmbeddedStreamsWhenPresent() {
        val series = Samples.series() // LEGO ONE PIECE: 1 season of 2 episodes embedded
        val replaced = series.withPlayableLinks(killJackie)
        assertEquals(8, replaced.seasons.single().episodes.size)
    }

    // --- movie 353371 via player-links ------------------------------------------------------------------------------

    @Test
    fun theMovieOnPlayerLinksHasThreeSubAndThreeDubQualities() {
        val links = Samples.playerLinks("player-links_movie_353371.json")
        assertTrue(links.seasons.isEmpty())
        assertEquals(6, links.streams.size)
        val subs = links.streams.filter { it.audio == AudioType.SUBTITLED }
        val dubs = links.streams.filter { it.audio == AudioType.DUBBED }
        assertEquals(listOf(1080, 720, 480), subs.map { it.quality })
        assertEquals(listOf(1080, 720, 480), dubs.map { it.quality })
        assertTrue(subs.all { it.subtitleUrl!!.endsWith(".vtt") })
        assertTrue(dubs.all { it.subtitleUrl == null })
        assertEquals(listOf("BluRay 1080p", "BluRay 720p", "BluRay 480p"), subs.map { it.label })
        assertEquals(listOf("2.42 GB", "780 MB", "390 MB"), subs.map { it.sizeText })
        assertEquals(listOf("1.42 GB", "770 MB", "380 MB"), dubs.map { it.sizeText })
    }

    @Test
    fun movieStreamsViaPlayerLinksEqualTheEmbeddedOnes() {
        assertEquals(Samples.movie().streams, Samples.playerLinks("player-links_movie_353371.json").streams)
    }

    @Test
    fun aMovieDetailTakesItsStreamsFromPlayerLinks() {
        val bare = detailWithEmptyLinks("movie_353371.json").toDetails()
        assertEquals(TitleType.MOVIE, bare.type)
        assertTrue(bare.streams.isEmpty())
        val merged = bare.withPlayableLinks(Samples.playerLinks("player-links_movie_353371.json"))
        assertEquals(6, merged.streams.size)
    }

    // --- block matching and audio detection -------------------------------------------------------------------------

    private fun links(json: String): PlayableLinks {
        val data = BolboljanJson.decodeFromString(PlayerLinksEnvelopeDto.serializer(), json).data
        return mapLinks(data.playerLinks, data.downloadLinks)
    }

    @Test
    fun downloadAndPlayerBlocksAreMatchedByKeyNotByPosition() {
        // Player side lacks the dub block "3"; positional matching would pair the sub block with the dub download block.
        val result = links(
            """
            {"success":true,"data":{
              "download_links":{
                "3":{"name":"Season 1","quality":"WEB-DL 720p DUB-LABEL","type":"dub","items":[{"title":"Episode 1","link":"d1"}]},
                "4":{"name":"Season 1","quality":"WEB-DL 720p SUB-LABEL","type":"sub","items":[{"title":"Episode 1","link":"s1","subtitle":"s1.vtt"}]}},
              "player_links":{
                "4":{"season_name":"Season 1","quality_link":"720","type_link":"","items":[{"play_link":"https://h/s1.mp4","fasub_link":"https://h/s1.vtt"}]}}}}
            """,
        )
        val stream = result.seasons.single().episodes.single().streams.single()
        assertEquals(AudioType.SUBTITLED, stream.audio)
        assertEquals("WEB-DL 720p SUB-LABEL", stream.label)
    }

    @Test
    fun aKeyThatContradictsSeasonOrQualityFallsBackToTheMatchingBlock() {
        val result = links(
            """
            {"success":true,"data":{
              "download_links":{
                "0":{"name":"Season 2","quality":"WEB-DL 480p","type":"sub","items":[{"title":"Episode 1","link":"x"}]},
                "9":{"name":"Season 1","quality":"WEB-DL 1080p","type":"dub","items":[{"title":"Episode 1","link":"y"}]}},
              "player_links":{
                "0":{"season_name":"Season 1","quality_link":"1080","type_link":"dub","items":[{"play_link":"https://h/y.mp4"}]}}}}
            """,
        )
        val stream = result.seasons.single().episodes.single().streams.single()
        assertEquals("WEB-DL 1080p", stream.label)
        assertEquals(AudioType.DUBBED, stream.audio)
    }

    @Test
    fun audioComesFromPlayerTypeLinkOrDownloadType() {
        val result = links(
            """
            {"success":true,"data":{
              "download_links":{
                "0":{"name":"Season 1","quality":"WEB-DL 1080p","type":"dub","items":[{"title":"Episode 1"}]},
                "1":{"name":"Season 1","quality":"WEB-DL 720p","type":"sub","items":[{"title":"Episode 1"}]},
                "2":{"name":"Season 1","quality":"WEB-DL 480p","type":"","items":[{"title":"Episode 1"}]}},
              "player_links":{
                "0":{"season_name":"Season 1","quality_link":"1080","type_link":"","items":[{"play_link":"https://h/a.mp4"}]},
                "1":{"season_name":"Season 1","quality_link":"720","type_link":"","items":[{"play_link":"https://h/b.mp4"}]},
                "2":{"season_name":"Season 1","quality_link":"480","type_link":"dub","items":[{"play_link":"https://h/c.mp4"}]}}}}
            """,
        )
        val streams = result.seasons.single().episodes.single().streams.associateBy { it.quality }
        assertEquals(AudioType.DUBBED, streams.getValue(1080).audio) // download type "dub"
        assertEquals(AudioType.SUBTITLED, streams.getValue(720).audio) // download type "sub", player type ""
        assertEquals(AudioType.DUBBED, streams.getValue(480).audio) // player type_link "dub"
    }

    @Test
    fun aDubbedBlockStartingAtALaterEpisodeKeepsItsOwnNumbers() {
        val result = links(
            """
            {"success":true,"data":{
              "download_links":{
                "0":{"name":"Season 1","quality":"WEB-DL 720p","type":"sub","items":[{"title":"Episode 1"},{"title":"Episode 2"},{"title":"Episode 3"}]},
                "1":{"name":"Season 1","quality":"WEB-DL 720p","type":"dub","items":[{"title":"Episode 3"}]}},
              "player_links":{
                "0":{"season_name":"Season 1","quality_link":"720","type_link":"","items":[{"play_link":"https://h/1.mp4"},{"play_link":"https://h/2.mp4"},{"play_link":"https://h/3.mp4"}]},
                "1":{"season_name":"Season 1","quality_link":"720","type_link":"dub","items":[{"play_link":"https://h/3d.mp4"}]}}}}
            """,
        )
        val episodes = result.seasons.single().episodes
        assertEquals(listOf(1, 2, 3), episodes.map { it.number })
        assertEquals(1, episodes[0].streams.size)
        assertEquals(2, episodes[2].streams.size)
        assertEquals(setOf(AudioType.SUBTITLED, AudioType.DUBBED), episodes[2].streams.map { it.audio }.toSet())
    }

    @Test
    fun playerLinksEnvelopeToleratesEmptyAndMalformedData() {
        for (body in listOf(
            """{"success":true,"data":[]}""",
            """{"success":true,"data":""}""",
            """{"success":true}""",
            """{"success":true,"data":{"download_links":[],"player_links":[]}}""",
            """{"success":false,"message":"x"}""",
        )) {
            val data = BolboljanJson.decodeFromString(PlayerLinksEnvelopeDto.serializer(), body).data
            assertTrue(body, mapLinks(data.playerLinks, data.downloadLinks).isEmpty)
        }
        assertFalse(killJackie.isEmpty)
    }
}
