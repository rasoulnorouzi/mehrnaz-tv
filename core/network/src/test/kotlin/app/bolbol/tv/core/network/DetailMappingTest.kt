package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.network.dto.DetailDto
import app.bolbol.tv.core.network.mapper.toDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailMappingTest {

    // --- movie (movie_353371.json) ------------------------------------------------------------

    @Test
    fun movieMetadata() {
        val d = Samples.movie()
        assertEquals(TitleType.MOVIE, d.type)
        assertEquals(353371L, d.summary.id)
        assertEquals("The Naked Gun 2025", d.summary.title)
        assertEquals("اسلحه برهنه", d.summary.faTitle)
        assertEquals(2025, d.summary.year)
        assertEquals(6.3f, d.summary.imdbRating!!, 0.0001f)
        assertEquals(64, d.metacritic)
        assertEquals(85, d.runtimeMinutes)
        assertEquals("12", d.ageRating)
        assertTrue(d.summary.hasDub)
        assertTrue(d.summary.hasSubtitle)
        assertEquals(
            "https://majnoonbazar.ir/wp-content/uploads/2026/02/0-76008300-1772116872.webp",
            d.summary.posterUrl,
        )
        assertTrue(d.summary.backdropUrl!!.endsWith("0-84730100-1772116872.webp"))
        assertTrue(d.plot.isNotBlank())
        assertTrue(d.trailerUrl!!.endsWith(".mp4"))
        assertEquals("دوبله (ساندفیلم) اضافه شد!", d.updateText)
        assertEquals(listOf("اکشن", "جنایی", "کمدی"), d.genres)
        assertEquals(listOf("آمریکا", "کانادا"), d.countries)
        assertEquals("Akiva Schaffer", d.directors.single().name)
        assertEquals(null, d.directors.single().photoUrl)
        assertTrue(d.actors.isNotEmpty())
        assertEquals("Bruce Buffer", d.actors.first().name)
        assertTrue(d.actors.first().photoUrl!!.startsWith("https://"))
        assertTrue(d.seasons.isEmpty())
    }

    @Test
    fun movieHasSixStreams() {
        val streams = Samples.movie().streams
        assertEquals(6, streams.size)

        val subs = streams.filter { it.audio == AudioType.SUBTITLED }
        val dubs = streams.filter { it.audio == AudioType.DUBBED }
        assertEquals(3, subs.size)
        assertEquals(3, dubs.size)

        assertTrue(subs.all { it.subtitleUrl != null && it.subtitleUrl!!.endsWith(".vtt") })
        assertTrue(dubs.all { it.subtitleUrl == null })
        assertTrue(streams.all { it.url.endsWith(".mp4") })

        assertEquals(listOf(1080, 720, 480), subs.map { it.quality })
        assertEquals(listOf(1080, 720, 480), dubs.map { it.quality })
    }

    @Test
    fun movieStreamLabelsAndSizesComeFromTheMatchingDownloadEntry() {
        val streams = Samples.movie().streams
        fun find(audio: AudioType, quality: Int) = streams.single { it.audio == audio && it.quality == quality }

        assertEquals("BluRay 1080p", find(AudioType.SUBTITLED, 1080).label)
        assertEquals("2.42 GB", find(AudioType.SUBTITLED, 1080).sizeText)
        assertEquals("BluRay 720p", find(AudioType.SUBTITLED, 720).label)
        assertEquals("780 MB", find(AudioType.SUBTITLED, 720).sizeText)
        assertEquals("BluRay 480p", find(AudioType.SUBTITLED, 480).label)
        assertEquals("390 MB", find(AudioType.SUBTITLED, 480).sizeText)

        assertEquals("WEB-DL 1080p", find(AudioType.DUBBED, 1080).label)
        assertEquals("1.42 GB", find(AudioType.DUBBED, 1080).sizeText)
        assertEquals("WEB-DL 720p", find(AudioType.DUBBED, 720).label)
        assertEquals("770 MB", find(AudioType.DUBBED, 720).sizeText)
        assertEquals("WEB-DL 480p", find(AudioType.DUBBED, 480).label)
        assertEquals("380 MB", find(AudioType.DUBBED, 480).sizeText)
    }

    @Test
    fun movieStreamsPlayTheSameUrlAsThePlayerLink() {
        // The 720p entries live on another CDN host than 1080/480 (docs/api.md 5.4.1).
        val streams = Samples.movie().streams
        assertTrue(streams.single { it.audio == AudioType.SUBTITLED && it.quality == 720 }.url.contains("dl2.ecenteri.ir"))
        assertTrue(streams.single { it.audio == AudioType.SUBTITLED && it.quality == 1080 }.url.contains("dl20.acenteri.ir"))
    }

    @Test
    fun movieRelatedPostsBecomeSummaries() {
        val related = Samples.movie().related
        assertEquals(10, related.size)
        assertTrue(related.all { it.type == TitleType.MOVIE })
        assertTrue(related.all { it.posterUrl != null && it.backdropUrl != null })
        assertEquals("Masters of the Universe 2026", related.first().title)
        assertEquals(2026, related.first().year)
    }

    // --- series (serie_624236.json) -----------------------------------------------------------

    @Test
    fun seriesMetadata() {
        val d = Samples.series()
        assertEquals(TitleType.SERIES, d.type)
        assertEquals("LEGO ONE PIECE", d.summary.title)
        assertEquals(2026, d.summary.year)
        assertEquals(79, d.metacritic)
        assertEquals("TV-Y7", d.ageRating)
        assertEquals(65, d.runtimeMinutes)
        assertNull(d.trailerUrl)
        assertTrue(d.streams.isEmpty())
        assertFalse(d.summary.hasDub)
        assertTrue(d.summary.hasSubtitle)
        // The only director is the "unknown" placeholder (docs/api.md quirk 14).
        assertTrue(d.directors.isEmpty())
        assertTrue(d.related.all { it.type == TitleType.SERIES })
    }

    @Test
    fun seriesHasOneSeasonWithTwoEpisodesOfThreeQualities() {
        val seasons = Samples.series().seasons
        assertEquals(1, seasons.size)
        val season = seasons.single()
        assertEquals(1, season.number)
        assertEquals(2, season.episodes.size)
        assertEquals(listOf(1, 2), season.episodes.map { it.number })
        assertEquals(listOf("Episode 1", "Episode 2"), season.episodes.map { it.title })
        for (episode in season.episodes) {
            assertEquals(1, episode.season)
            assertEquals(listOf(1080, 720, 480), episode.streams.map { it.quality })
            assertTrue(episode.streams.all { it.audio == AudioType.SUBTITLED })
            assertTrue(episode.streams.all { it.subtitleUrl!!.endsWith(".vtt") })
            assertTrue(episode.streams.all { it.url.endsWith(".mp4") })
            assertEquals(
                listOf("WEB-DL 1080p", "WEB-DL 720p", "WEB-DL 480p"),
                episode.streams.map { it.label },
            )
        }
    }

    @Test
    fun seriesStreamUrlsMatchTheirQualityFolderAndEpisode() {
        val season = Samples.series().seasons.single()
        val ep1 = season.episodes[0].streams.single { it.quality == 720 }
        val ep2 = season.episodes[1].streams.single { it.quality == 720 }
        assertTrue(ep1.url.contains("/720p/") && ep1.url.contains("S01E01"))
        assertTrue(ep2.url.contains("/720p/") && ep2.url.contains("S01E02"))
    }

    // --- synthetic shapes ---------------------------------------------------------------------

    private fun detail(json: String): DetailDto = BolboljanJson.decodeFromString(DetailDto.serializer(), json)

    @Test
    fun movieLinksGivenAsArraysJoinByIndex() {
        // PHP emits an array when keys happen to be 0..n-1 in order.
        val d = detail(
            """
            {"id":1,"type":"movie","title":"فیلم X 2020",
             "download_links":[
               {"dl_link":"https://h/a.mp4","quality_link":"BluRay 1080p","dl_capacity":"2 GigaByte ","dl_sub":"https://h/a.vtt","link_type":"sub"},
               {"dl_link":"https://h/b.mp4","quality_link":"WEB-DL 720p","dl_capacity":"500 MegaByte ","dl_sub":"","link_type":"dub"}],
             "player_links":[
               {"play_link":"https://h/a.mp4","fasub_link":"https://h/a.vtt","ensub_link":"","quality_link":"1080","type_link":"sub"},
               {"play_link":"https://h/b.mp4","fasub_link":"","ensub_link":"","quality_link":"720","type_link":"dub"}]}
            """,
        ).toDetails()
        assertEquals(2, d.streams.size)
        assertEquals("BluRay 1080p", d.streams[0].label)
        assertEquals("2 GB", d.streams[0].sizeText)
        assertEquals("WEB-DL 720p", d.streams[1].label)
        assertEquals(AudioType.DUBBED, d.streams[1].audio)
        assertEquals("500 MB", d.streams[1].sizeText)
    }

    @Test
    fun movieWithoutDownloadEntryFallsBackToQualityLabel() {
        val d = detail(
            """
            {"id":1,"type":"movie","title":"X","download_links":[],
             "player_links":{"7":{"play_link":"https://h/a.mp4","fasub_link":"","quality_link":"480","type_link":"dub"}}}
            """,
        ).toDetails()
        val s = d.streams.single()
        assertEquals("480p", s.label)
        assertNull(s.sizeText)
        assertEquals(AudioType.DUBBED, s.audio)
        assertTrue(d.summary.hasDub)
        assertFalse(d.summary.hasSubtitle)
    }

    @Test
    fun emptyMovieLinksMeanNoStreams() {
        val d = detail("""{"id":1,"type":"movie","title":"X","download_links":[],"player_links":[]}""").toDetails()
        assertTrue(d.streams.isEmpty())
        assertTrue(d.seasons.isEmpty())
    }

    @Test
    fun streamsWithBlankUrlAreDropped() {
        val d = detail(
            """{"id":1,"type":"movie","title":"X","player_links":{"0":{"play_link":"","quality_link":"720","type_link":"sub"}}}""",
        ).toDetails()
        assertTrue(d.streams.isEmpty())
    }

    @Test
    fun seriesSubAndDubBlocksOfTheSameQualityStaySeparate() {
        val d = detail(
            """
            {"id":2,"type":"serie","title":"سریال Y",
             "download_links":[
               {"name":"Season 1","quality":"WEB-DL 720p","type":"sub","count":"2","items":[
                  {"title":"Episode 1","link":"s1","subtitle":"s1.vtt"},{"title":"Episode 2","link":"s2","subtitle":"s2.vtt"}]},
               {"name":"Season 1","quality":"WEB-DL 720p","type":"dub","count":"2","items":[
                  {"title":"Episode 1","link":"d1","subtitle":""},{"title":"Episode 2","link":"d2","subtitle":""}]}],
             "player_links":[
               {"season_name":"Season 1","quality_link":"720","type_link":"","items":[
                  {"play_link":"https://h/s1.mp4","fasub_link":"https://h/s1.vtt"},{"play_link":"https://h/s2.mp4","fasub_link":"https://h/s2.vtt"}]},
               {"season_name":"Season 1","quality_link":"720","type_link":"","items":[
                  {"play_link":"https://h/d1.mp4","fasub_link":""},{"play_link":"https://h/d2.mp4","fasub_link":""}]}]}
            """,
        ).toDetails()
        val episodes = d.seasons.single().episodes
        assertEquals(2, episodes.size)
        for (episode in episodes) {
            assertEquals(2, episode.streams.size)
            val sub = episode.streams.single { it.audio == AudioType.SUBTITLED }
            val dub = episode.streams.single { it.audio == AudioType.DUBBED }
            assertTrue(sub.subtitleUrl!!.endsWith(".vtt"))
            assertNull(dub.subtitleUrl)
        }
        assertTrue(episodes[0].streams.single { it.audio == AudioType.DUBBED }.url.endsWith("d1.mp4"))
        assertTrue(episodes[1].streams.single { it.audio == AudioType.SUBTITLED }.url.endsWith("s2.mp4"))
        assertTrue(d.summary.hasDub)
    }

    @Test
    fun qualitiesWithDifferentEpisodeCountsMergeByEpisodeNumber() {
        val d = detail(
            """
            {"id":3,"type":"serie","title":"Z",
             "download_links":[
               {"name":"Season 2","quality":"WEB-DL 1080p","type":"sub","items":[
                  {"title":"Episode 1","link":"a1"},{"title":"Episode 2","link":"a2"},{"title":"Episode 3","link":"a3"}]},
               {"name":"Season 2","quality":"WEB-DL 480p","type":"sub","items":[
                  {"title":"Episode 1","link":"b1"},{"title":"Episode 2","link":"b2"}]}],
             "player_links":[
               {"season_name":"Season 2","quality_link":"1080","items":[
                  {"play_link":"https://h/a1.mp4"},{"play_link":"https://h/a2.mp4"},{"play_link":"https://h/a3.mp4"}]},
               {"season_name":"Season 2","quality_link":"480","items":[
                  {"play_link":"https://h/b1.mp4"},{"play_link":"https://h/b2.mp4"}]}]}
            """,
        ).toDetails()
        val season = d.seasons.single()
        assertEquals(2, season.number)
        assertEquals(3, season.episodes.size)
        assertEquals(2, season.episodes[0].streams.size)
        assertEquals(2, season.episodes[1].streams.size)
        assertEquals(1, season.episodes[2].streams.size)
        assertEquals(1080, season.episodes[2].streams.single().quality)
    }

    @Test
    fun seasonsAreSortedAndEpisodesFallBackToPositionWithoutDownloadTitles() {
        val d = detail(
            """
            {"id":4,"type":"serie","title":"W",
             "player_links":[
               {"season_name":"Season 2","quality_link":"720","type_link":"dub","items":[{"play_link":"https://h/x1.mp4"},{"play_link":"https://h/x2.mp4"}]},
               {"season_name":"Season 1","quality_link":"720","type_link":"","items":[{"play_link":"https://h/y1.mp4","fasub_link":"https://h/y1.vtt"}]}]}
            """,
        ).toDetails()
        assertEquals(listOf(1, 2), d.seasons.map { it.number })
        val s2 = d.seasons[1]
        assertEquals(listOf(1, 2), s2.episodes.map { it.number })
        assertEquals("Episode 2", s2.episodes[1].title)
        assertEquals(AudioType.DUBBED, s2.episodes[0].streams.single().audio)
        // No type anywhere but a subtitle URL exists -> subtitled.
        assertEquals(AudioType.SUBTITLED, d.seasons[0].episodes.single().streams.single().audio)
    }

    @Test
    fun seriesDownloadLinksGivenAsObjectAreTolerated() {
        val d = detail(
            """
            {"id":5,"type":"serie","title":"V",
             "download_links":{"1":{"name":"Season 1","quality":"WEB-DL 720p","type":"sub","items":{"0":{"title":"Episode 1","link":"l"}}}},
             "player_links":{"1":{"season_name":"Season 1","quality_link":"720","items":{"0":{"play_link":"https://h/p.mp4"}}}}}
            """,
        ).toDetails()
        val stream = d.seasons.single().episodes.single().streams.single()
        assertEquals("WEB-DL 720p", stream.label)
        assertEquals(AudioType.SUBTITLED, stream.audio)
    }

    @Test
    fun typeIsInferredFromPlayerLinksWhenMissing() {
        val series = detail(
            """{"id":6,"title":"U","player_links":[{"season_name":"Season 1","quality_link":"720","items":[{"play_link":"https://h/p.mp4"}]}]}""",
        ).toDetails()
        assertEquals(TitleType.SERIES, series.type)
        val movie = detail(
            """{"id":7,"title":"U","player_links":{"0":{"play_link":"https://h/p.mp4","quality_link":"720","type_link":"sub"}}}""",
        ).toDetails()
        assertEquals(TitleType.MOVIE, movie.type)
    }

    @Test
    fun scalarsMayBeStringsNumbersOrNull() {
        val d = detail(
            """{"id":"8","type":"movie","title":null,"release":2024,"imdb_rate":6.5,"metacritic_rate":"0%","runtime":"","has_dubbed":true,"has_subtitle":"on","age":null,"genres":null,"actors":{"3":{"id":1,"name":"A"}}}""",
        ).toDetails()
        assertEquals(8L, d.summary.id)
        assertEquals("", d.summary.title)
        assertEquals(2024, d.summary.year)
        assertEquals(6.5f, d.summary.imdbRating!!, 0.0001f)
        assertNull(d.metacritic)
        assertNull(d.runtimeMinutes)
        assertNull(d.ageRating)
        assertTrue(d.summary.hasDub)
        assertTrue(d.summary.hasSubtitle)
        assertTrue(d.genres.isEmpty())
        assertEquals("A", d.actors.single().name)
    }
}
