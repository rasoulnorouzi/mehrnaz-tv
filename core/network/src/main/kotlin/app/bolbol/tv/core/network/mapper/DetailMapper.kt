package app.bolbol.tv.core.network.mapper

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.Episode
import app.bolbol.tv.core.model.Season
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.network.dto.DetailDto
import app.bolbol.tv.core.network.dto.DownloadEntryDto
import app.bolbol.tv.core.network.dto.PlayerEntryDto

/** Newest quality first; for equal quality subtitled before dubbed (the order of the source list). */
private val STREAM_ORDER = compareByDescending<StreamOption> { it.quality }.thenBy { it.audio.ordinal }

/**
 * Detail payload -> [TitleDetails] (docs/api.md section 5.4 and 8.4).
 * The kind comes from `type`; when that is missing it is inferred from the shape of `player_links`.
 */
internal fun DetailDto.toDetails(images: ImageUrls = ImageUrls.Default): TitleDetails {
    val kind = parseType(type)
        ?: if (playerLinks.any { it.items.isNotEmpty() }) TitleType.SERIES else TitleType.MOVIE

    val streams = if (kind == TitleType.MOVIE) mapMovieStreams(playerLinks, downloadLinks) else emptyList()
    val seasons = if (kind == TitleType.SERIES) mapSeasons(playerLinks, downloadLinks) else emptyList()

    val allStreams = streams + seasons.flatMap { season -> season.episodes.flatMap { it.streams } }
    val summary = TitleSummary(
        id = id,
        type = kind,
        title = cleanTitle(title),
        faTitle = faTitle.decodeHtmlEntities().trim(),
        posterUrl = images.normalize(image),
        backdropUrl = images.normalize(backgroundImage),
        year = parseYear(release),
        imdbRating = parseImdb(imdbRate),
        // The flags can lag behind the links; trust whichever says "yes".
        hasDub = hasDubbed || allStreams.any { it.audio == AudioType.DUBBED },
        hasSubtitle = hasSubtitle || allStreams.any { it.audio == AudioType.SUBTITLED },
    )

    return TitleDetails(
        summary = summary,
        type = kind,
        plot = faPlot.decodeHtmlEntities().trim(),
        runtimeMinutes = parseRuntimeMinutes(runtime),
        ageRating = age.nullIfBlank(),
        metacritic = parseMetacritic(metacriticRate),
        genres = genres.mapNotNull { it.name.decodeHtmlEntities().nullIfBlank() },
        countries = countries.mapNotNull { it.name.decodeHtmlEntities().nullIfBlank() },
        actors = actors.mapNotNull { it.toPerson(images) },
        directors = directors.mapNotNull { it.toPerson(images) },
        trailerUrl = trailer.nullIfBlank(),
        updateText = updateText.decodeHtmlEntities().nullIfBlank(),
        streams = streams,
        seasons = seasons,
        related = relatedPosts.mapNotNull { it.toSummary(images) }.distinctBy { it.id },
    )
}

// ---------------------------------------------------------------------------------------------
// Movies
// ---------------------------------------------------------------------------------------------

/**
 * One [StreamOption] per `player_links` entry (url = `play_link`, subtitle = `fasub_link`),
 * enriched from the `download_links` entry with the same key (label `"BluRay 1080p"`, size
 * `"2.42 GB"`). If the key finds nothing, the entry whose `dl_link` equals the `play_link` is used.
 */
internal fun mapMovieStreams(
    players: List<PlayerEntryDto>,
    downloads: List<DownloadEntryDto>,
): List<StreamOption> {
    val byKey = downloads.filter { it.key.isNotEmpty() }.associateBy { it.key }
    return players.mapNotNull { player ->
        val url = player.playLink.nullIfBlank() ?: return@mapNotNull null
        val download = byKey[player.key] ?: downloads.firstOrNull { it.dlLink.isNotBlank() && it.dlLink == player.playLink }
        val quality = parseQuality(player.qualityLink) ?: parseQuality(download?.qualityLink.orEmpty()) ?: 0
        StreamOption(
            quality = quality,
            audio = parseAudio(player.typeLink.ifBlank { download?.linkType.orEmpty() }),
            url = url,
            subtitleUrl = player.fasubLink.nullIfBlank() ?: download?.dlSub.nullIfBlank(),
            label = download?.qualityLink.nullIfBlank() ?: qualityLabel(quality),
            sizeText = formatSize(download?.dlCapacity.orEmpty()),
        )
    }.sortedWith(STREAM_ORDER)
}

private fun qualityLabel(quality: Int): String = if (quality > 0) "${quality}p" else "SD"

// ---------------------------------------------------------------------------------------------
// Series
// ---------------------------------------------------------------------------------------------

private class EpisodeAccumulator(val season: Int, val number: Int) {
    var title: String = ""
    val streams = ArrayList<StreamOption>()
}

/**
 * Builds seasons from the per-(season, quality) blocks of `player_links`.
 *
 * Each player block is matched with the `download_links` block of the same season number and
 * quality (preferring the block at the same index, never reusing a block); the download block
 * supplies the audio type (`type`), the label (`quality`), the size and the episode titles.
 * Episode number = the number in the download item title when the block's titles are complete
 * and distinct, otherwise the 1-based position. Blocks of all qualities are merged per
 * (season, episode) so each [Episode] carries every [StreamOption].
 */
internal fun mapSeasons(
    players: List<PlayerEntryDto>,
    downloads: List<DownloadEntryDto>,
): List<Season> {
    val used = BooleanArray(downloads.size)
    val episodes = LinkedHashMap<Pair<Int, Int>, EpisodeAccumulator>()

    players.forEachIndexed { index, player ->
        if (player.items.isEmpty()) return@forEachIndexed

        val seasonNumber = parseOrdinal(player.seasonName)
            ?: downloads.getOrNull(index)?.name?.let(::parseOrdinal)
            ?: 1
        val playerQuality = parseQuality(player.qualityLink)

        fun candidate(j: Int): Boolean =
            !used[j] &&
                (parseOrdinal(downloads[j].name) ?: 1) == seasonNumber &&
                parseQuality(downloads[j].quality) == playerQuality

        val matched = when {
            index < downloads.size && candidate(index) -> index
            else -> downloads.indices.firstOrNull { candidate(it) }
        }
        matched?.let { used[it] = true }
        val download = matched?.let { downloads[it] }

        val quality = playerQuality ?: parseQuality(download?.quality.orEmpty()) ?: 0
        val label = download?.quality.nullIfBlank() ?: qualityLabel(quality)
        val sizeText = formatSize(download?.capacity.orEmpty())

        val downloadType = download?.type.nullIfBlank()
        val audio = when {
            downloadType != null -> parseAudio(downloadType)
            player.typeLink.isNotBlank() -> parseAudio(player.typeLink)
            // Docs 8.4: default to subtitled if a subtitle URL exists; otherwise assume a dub.
            player.items.any { it.fasubLink.isNotBlank() } -> AudioType.SUBTITLED
            else -> AudioType.DUBBED
        }

        val downloadItems = download?.items.orEmpty()
        val titleNumbers: List<Int?> = downloadItems.map { parseOrdinal(it.title) }
        val useTitleNumbers = downloadItems.size == player.items.size &&
            titleNumbers.all { it != null } &&
            titleNumbers.toSet().size == titleNumbers.size

        player.items.forEachIndexed { position, item ->
            val streamUrl = item.playLink.nullIfBlank() ?: return@forEachIndexed
            val downloadItem = downloadItems.getOrNull(position)
            val number = if (useTitleNumbers) titleNumbers[position]!! else position + 1

            val accumulator = episodes.getOrPut(seasonNumber to number) { EpisodeAccumulator(seasonNumber, number) }
            if (accumulator.title.isEmpty()) {
                accumulator.title = downloadItem?.title.nullIfBlank() ?: "Episode $number"
            }
            accumulator.streams += StreamOption(
                quality = quality,
                audio = audio,
                url = streamUrl,
                subtitleUrl = item.fasubLink.nullIfBlank() ?: downloadItem?.subtitle.nullIfBlank(),
                label = label,
                sizeText = sizeText,
            )
        }
    }

    return episodes.values
        .groupBy { it.season }
        .toSortedMap()
        .map { (seasonNumber, accumulators) ->
            Season(
                number = seasonNumber,
                episodes = accumulators.sortedBy { it.number }.map { acc ->
                    Episode(
                        season = acc.season,
                        number = acc.number,
                        title = acc.title,
                        streams = acc.streams.distinctBy { Triple(it.quality, it.audio, it.url) }.sortedWith(STREAM_ORDER),
                    )
                },
            )
        }
}
