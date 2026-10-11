package app.bolbol.tv.feature.tvhome

import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.WatchKey

internal enum class ProgramKind { MOVIE, TV_EPISODE }

internal enum class PosterShape { WIDE_16_9, POSTER_2_3 }

/**
 * Everything we write into one Watch Next program, as plain values (no Android types), so the mapping and the
 * diffing are unit-testable on a plain JVM. [toProgram] (ProgramMapper.kt) turns it into a `WatchNextProgram`.
 *
 * Fixed on every program: `watchNextType = WATCH_NEXT_TYPE_CONTINUE`, `internalProviderId = key.asString()`.
 */
internal data class WatchNextSpec(
    val key: WatchKey,
    val kind: ProgramKind,
    /** Series or movie title (Persian preferred). */
    val title: String,
    /** null for movies. */
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    /** «فصل ۱ · قسمت ۲»; null for movies. */
    val episodeTitle: String?,
    /** null when the title has no usable http(s) image. */
    val posterUrl: String?,
    val posterShape: PosterShape,
    val positionMs: Int,
    val durationMs: Int,
    val lastEngagementMs: Long,
    /** `bolbol://play/...`; wrapped into an ACTION_VIEW intent URI for [appPackage]. */
    val deepLink: String,
    val appPackage: String,
) {
    /**
     * Stable 64-bit digest of every field. Stored in the program's `internalProviderFlag1` so a later publish can
     * tell "unchanged" from "needs update" without reading all columns back.
     */
    fun fingerprint(): Long {
        var h = FNV_OFFSET
        fun mix(part: Any?) {
            for (c in part.toString()) {
                h = (h xor c.code.toLong()) * FNV_PRIME
            }
            h = (h xor 0x1F) * FNV_PRIME // field separator
        }
        mix(key.asString()); mix(kind); mix(title); mix(seasonNumber); mix(episodeNumber); mix(episodeTitle)
        mix(posterUrl); mix(posterShape); mix(positionMs); mix(durationMs); mix(lastEngagementMs)
        mix(deepLink); mix(appPackage)
        return h
    }
}

private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
private const val FNV_PRIME = 1099511628211L

/** Most programs we keep on the launcher (matches `WatchRepository.continueWatching`'s default limit). */
internal const val MAX_PROGRAMS = 20

/** Maps one Continue Watching entry; null when it must not be on the launcher (finished, unknown duration, no title). */
internal fun ContinueWatchingItem.toWatchNextSpec(appPackage: String): WatchNextSpec? {
    val p = progress
    val key = p.key
    if (p.completed || p.durationMs <= 0 || p.durationMs > Int.MAX_VALUE || key.postId <= 0) return null
    val summary = title
    val displayTitle = summary.faTitle.trim().ifEmpty { summary.title.trim() }
    if (displayTitle.isEmpty()) return null

    val backdrop = summary.backdropUrl.httpOrNull()
    val poster = summary.posterUrl.httpOrNull()
    val isEpisode = key.isEpisode
    return WatchNextSpec(
        key = key,
        kind = if (isEpisode) ProgramKind.TV_EPISODE else ProgramKind.MOVIE,
        title = displayTitle,
        seasonNumber = if (isEpisode) key.season else null,
        episodeNumber = if (isEpisode) key.episode else null,
        episodeTitle = if (isEpisode) episodeLabel(key.season, key.episode) else null,
        // 16:9 backdrop first; fall back to the 2:3 poster rather than an empty card.
        posterUrl = backdrop ?: poster,
        posterShape = if (backdrop != null) PosterShape.WIDE_16_9 else PosterShape.POSTER_2_3,
        positionMs = p.positionMs.coerceIn(0, p.durationMs).toInt(),
        durationMs = p.durationMs.toInt(),
        lastEngagementMs = p.updatedAtMs,
        deepLink = DeepLinks.playString(key),
        appPackage = appPackage,
    )
}

/**
 * Specs to publish: one per title (the most recently watched episode wins), newest first, at most [max].
 */
internal fun selectWatchNextSpecs(
    items: List<ContinueWatchingItem>,
    appPackage: String,
    max: Int = MAX_PROGRAMS,
): List<WatchNextSpec> =
    items.mapNotNull { it.toWatchNextSpec(appPackage) }
        .groupBy { it.key.postId }
        .values
        .map { perTitle -> perTitle.maxBy { it.lastEngagementMs } }
        .sortedWith(compareByDescending<WatchNextSpec> { it.lastEngagementMs }.thenBy { it.key.postId })
        .take(max)

/** Order-sensitive digest of a whole publish; used to skip a publish when nothing changed. */
internal fun fingerprintOf(specs: List<WatchNextSpec>): Long {
    var h = specs.size.toLong()
    for (s in specs) h = (h xor s.fingerprint()) * FNV_PRIME + 31
    return h
}

/** «فصل ۱ · قسمت ۲» */
internal fun episodeLabel(season: Int, episode: Int): String =
    "فصل ${season.toPersianDigits()} · قسمت ${episode.toPersianDigits()}"

private fun Int.toPersianDigits(): String = buildString {
    for (c in this@toPersianDigits.toString()) append(if (c in '0'..'9') '۰' + (c - '0') else c)
}

private fun String?.httpOrNull(): String? {
    val s = this?.trim() ?: return null
    return if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) s else null
}
