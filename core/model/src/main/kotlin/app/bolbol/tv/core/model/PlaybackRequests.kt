package app.bolbol.tv.core.model

/** Episodes of a series in watch order (season, then episode). */
fun TitleDetails.episodesInOrder(): List<Episode> =
    seasons.sortedBy { it.number }.flatMap { season -> season.episodes.sortedBy { it.number } }

fun TitleDetails.episode(season: Int, episode: Int): Episode? =
    seasons.firstOrNull { it.number == season }?.episodes?.firstOrNull { it.number == episode }

/** The episode after [key] across season boundaries, or null for the last episode / movies. */
fun TitleDetails.nextEpisodeKey(key: WatchKey): WatchKey? {
    if (!key.isEpisode) return null
    val all = episodesInOrder()
    val index = all.indexOfFirst { it.season == key.season && it.number == key.episode }
    if (index < 0 || index == all.lastIndex) return null
    val next = all[index + 1]
    return WatchKey(id(), next.season, next.number)
}

/**
 * Builds what the player needs for [key] (a movie: season = episode = 0).
 * Returns null when the key doesn't exist in these details or has no streams.
 */
fun TitleDetails.playbackRequest(key: WatchKey, startPositionMs: Long? = null): PlaybackRequest? {
    val streams: List<StreamOption>
    val line: String?
    if (key.isEpisode) {
        val ep = episode(key.season, key.episode) ?: return null
        streams = ep.streams
        line = "فصل ${key.season.fa()} · قسمت ${key.episode.fa()}"
    } else {
        streams = this.streams
        line = null
    }
    if (streams.isEmpty()) return null
    return PlaybackRequest(
        key = key,
        title = summary.copy(type = type),
        subtitleLine = line,
        streams = streams,
        startPositionMs = startPositionMs,
        next = nextEpisodeKey(key),
    )
}

private fun TitleDetails.id(): Long = summary.id

private fun Int.fa(): String = toString().map { c -> if (c in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[c - '0'] else c }.joinToString("")
