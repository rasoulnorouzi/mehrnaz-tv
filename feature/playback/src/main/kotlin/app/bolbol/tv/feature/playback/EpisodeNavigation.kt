package app.bolbol.tv.feature.playback

import androidx.compose.runtime.Immutable
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.PlaybackRequest
import app.bolbol.tv.core.model.Season
import app.bolbol.tv.core.model.WatchKey

// Pure logic (unit-tested) for v1.1-1 «قسمت قبل» / «قسمت بعد» and the v1.1-4 «قسمت‌ها» panel.

/**
 * Which episode buttons the controls show. [visible] is false for movies (no buttons at all);
 * for a series both buttons are always drawn (stable layout) and disabled at the first / last episode.
 */
@Immutable
internal data class EpisodeButtons(
    val visible: Boolean,
    val previous: WatchKey?,
    val next: WatchKey?,
) {
    val hasPrevious: Boolean get() = previous != null
    val hasNext: Boolean get() = next != null
}

internal fun PlaybackRequest.episodeButtons(): EpisodeButtons =
    if (!key.isEpisode) EpisodeButtons(visible = false, previous = null, next = null)
    else EpisodeButtons(visible = true, previous = previous, next = next)

/** True when the «قسمت‌ها» button and panel exist: a series with an episode list. */
internal fun PlaybackRequest.hasEpisodePanel(): Boolean = key.isEpisode && seasons.any { it.episodes.isNotEmpty() }

/**
 * A manual switch (button, panel, MEDIA_NEXT/PREVIOUS) is allowed to [target]: another episode of the
 * same title. Switching to the episode already playing is not a switch.
 */
internal fun PlaybackRequest.canSwitchTo(target: WatchKey): Boolean =
    key.isEpisode && target.isEpisode && target.postId == key.postId && target != key

/** Progress shorter than this reads as "not started" (same rule as the details screen). */
internal const val MIN_IN_PROGRESS_MS = 5_000L

/** A started bar is never thinner than this share, so one minute in still shows. */
internal const val MIN_VISIBLE_FRACTION = 0.03f

/** One row of the episodes panel. */
@Immutable
internal data class EpisodeRowUi(
    val key: WatchKey,
    val number: Int,
    /** The title worth showing next to «قسمت ۳»; null for the server's generic "Episode 3". */
    val title: String?,
    /** Bar fill (0 = no bar): only for an unfinished episode that was started. */
    val fraction: Float,
    val watched: Boolean,
    val dubbed: Boolean,
    val current: Boolean,
    val playable: Boolean,
)

/** The panel's season tabs; the season of [current] is preselected (falls back to the first). */
internal fun initialPanelSeason(seasons: List<Season>, current: WatchKey): Int? =
    seasons.firstOrNull { it.number == current.season }?.number ?: seasons.firstOrNull()?.number

/** Rows for [season] of title [postId], with saved [progress] (any title's entries are ignored). */
internal fun episodeRows(
    postId: Long,
    season: Season,
    progress: List<PlaybackProgress>,
    current: WatchKey,
): List<EpisodeRowUi> {
    val bySeasonEpisode = HashMap<Int, PlaybackProgress>()
    for (p in progress) {
        if (p.key.postId == postId && p.key.season == season.number) bySeasonEpisode[p.key.episode] = p
    }
    return season.episodes.map { episode ->
        val key = WatchKey(postId, season.number, episode.number)
        val saved = bySeasonEpisode[episode.number]
        EpisodeRowUi(
            key = key,
            number = episode.number,
            title = episodeTitle(episode.title),
            fraction = progressFraction(saved),
            watched = saved?.completed == true,
            dubbed = episode.streams.any { it.audio == AudioType.DUBBED },
            current = key == current,
            playable = episode.streams.isNotEmpty(),
        )
    }
}

/** 0 when not started or finished; otherwise the watched share, at least [MIN_VISIBLE_FRACTION]. */
internal fun progressFraction(saved: PlaybackProgress?): Float {
    if (saved == null || saved.completed || saved.positionMs < MIN_IN_PROGRESS_MS) return 0f
    val f = saved.fraction
    return if (f.isNaN() || f <= 0f) 0f else f.coerceIn(MIN_VISIBLE_FRACTION, 1f)
}

private val GenericEpisodeTitle = Regex("""^\s*(episode|ep\.?|قسمت)\s*[\d۰-۹]*\s*$""", RegexOption.IGNORE_CASE)

/** The title worth showing next to «قسمت ۳»: null for blanks and the generic "Episode 3" placeholder. */
internal fun episodeTitle(title: String): String? {
    val trimmed = title.trim()
    if (trimmed.isEmpty() || GenericEpisodeTitle.matches(trimmed)) return null
    return trimmed
}
