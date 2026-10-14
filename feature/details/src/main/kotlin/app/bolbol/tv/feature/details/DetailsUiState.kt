package app.bolbol.tv.feature.details

import app.bolbol.tv.core.designsystem.formatProgressLabelFa

import androidx.compose.runtime.Immutable
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.CatalogException
import app.bolbol.tv.core.model.DownloadItem
import app.bolbol.tv.core.model.Episode
import app.bolbol.tv.core.model.Person
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.model.episodesInOrder
import app.bolbol.tv.core.model.pick

/** Why the details could not be loaded; the screen maps it to a Persian message. */
enum class DetailsError { OFFLINE, SERVER_DOWN, NOT_FOUND, GENERIC }

/**
 * What a play button does: start [key] at [startPositionMs] (0 = from the beginning).
 * The player does not ask again, so this is the final position.
 */
@Immutable
data class PlayAction(val key: WatchKey, val startPositionMs: Long) {
    /** True when this continues something already started (the label says "ادامه ..."). */
    val resume: Boolean get() = startPositionMs > 0
}

/** Audio / quality pickers. Pickers with fewer than two options are not worth showing. */
@Immutable
data class StreamChoices(
    /** Available audio flavours, dubbed first. */
    val audioOptions: List<AudioType>,
    val selectedAudio: AudioType?,
    /** Available vertical resolutions for [selectedAudio], highest first. */
    val qualityOptions: List<Int>,
    val selectedQuality: Int?,
) {
    companion object {
        val None = StreamChoices(emptyList(), null, emptyList(), null)
    }
}

/** One row of the episode list. */
@Immutable
data class EpisodeItem(
    val key: WatchKey,
    /** The API's episode title, `"Episode 3"` when the server has none. */
    val title: String,
    /** Saved progress 0..1 for an unfinished episode, else 0. */
    val fraction: Float,
    /** Time left of an unfinished episode, else 0. */
    val remainingMs: Long,
    val completed: Boolean,
    /** Where selecting this row starts: the saved position when unfinished, else 0. */
    val startPositionMs: Long,
    /** False for announced episodes without any stream. */
    val playable: Boolean,
    /** «ادامه از ۱۲:۳۴» (duration unknown, e.g. synced from the website) or «۲۴ دقیقه مانده»; null if not started. */
    val progressLabel: String? = null,
    /** Offline-saving state of this episode; null = offline saving not wired in (no download icon). */
    val download: DownloadButtonState? = null,
) {
    val number: Int get() = key.episode
}

@Immutable
sealed interface DetailsUiState {
    data object Loading : DetailsUiState

    data class Error(val error: DetailsError) : DetailsUiState

    data class Content(
        val details: TitleDetails,
        val cast: List<Person>,
        val related: List<TitleSummary>,
        val choices: StreamChoices,
        /** The main button; null when nothing is playable yet. */
        val primary: PlayAction?,
        /** Season numbers that have episodes (empty for movies). */
        val seasons: List<Int>,
        val selectedSeason: Int,
        /** Episodes of [selectedSeason]. */
        val episodes: List<EpisodeItem>,
        /** Whether the title is in "لیست من"; null = unknown or no My List wired in (the button is hidden). */
        val inMyList: Boolean? = null,
        /**
         * The episode that gets the screen's first focus (opened from Continue Watching's "رفتن به صفحهٔ سریال");
         * non-null only when it exists, is playable and lies in [selectedSeason]. null = the usual default focus.
         */
        val focusEpisode: WatchKey? = null,
        /** Offline-saving state of a movie; null = not wired in, or a series (episodes carry their own). */
        val movieDownload: DownloadButtonState? = null,
    ) : DetailsUiState {
        val isSeries: Boolean get() = details.type == TitleType.SERIES
    }
}

internal sealed interface LoadState {
    data object Loading : LoadState
    data class Loaded(val details: TitleDetails) : LoadState
    data class Failed(val error: DetailsError) : LoadState
}

internal fun Throwable.toDetailsError(): DetailsError = when (this) {
    is CatalogException.Offline -> DetailsError.OFFLINE
    is CatalogException.AllMirrorsDown -> DetailsError.SERVER_DOWN
    is CatalogException.NotFound -> DetailsError.NOT_FOUND
    else -> DetailsError.GENERIC
}

// ---------------------------------------------------------------------------------------------
// Pure logic (unit-tested): what to play, what to preselect.
// ---------------------------------------------------------------------------------------------

/** Progress shorter than this is treated as "not started": resuming 3 s in is pointless. */
internal const val MIN_RESUME_MS = 5_000L

/** The saved position worth resuming from, or 0 when there is none (no progress, finished, or just begun). */
internal fun PlaybackProgress?.resumePositionMs(): Long =
    if (this != null && !completed && positionMs >= MIN_RESUME_MS) positionMs else 0L

/** Movie: "ادامه از ۴۷:۱۲" when unfinished progress exists, else "پخش" from 0. */
internal fun moviePlayAction(titleId: Long, progress: List<PlaybackProgress>): PlayAction {
    val key = WatchKey(titleId)
    return PlayAction(key, progress.firstOrNull { it.key == key }.resumePositionMs())
}

/**
 * Series: where "continue / start" points.
 * 1. The most recently watched episode, if it is unfinished: continue it at its saved position.
 * 2. Otherwise the episode after the furthest completed one (so finishing episode 3 offers episode 4,
 *    even if an older episode 1 was left half-watched). If that episode has an unfinished position
 *    saved, it resumes there.
 * 3. No progress at all: the first episode. Everything watched: start over from the first.
 * Episodes without streams are never offered. Returns null when no episode is playable.
 */
internal fun seriesPlayAction(details: TitleDetails, progress: List<PlaybackProgress>): PlayAction? {
    val id = details.summary.id
    val episodes = details.episodesInOrder().filter { it.streams.isNotEmpty() }
    if (episodes.isEmpty()) return null

    val indexByEpisode = HashMap<Pair<Int, Int>, Int>(episodes.size * 2)
    episodes.forEachIndexed { index, ep -> indexByEpisode[ep.season to ep.number] = index }
    fun PlaybackProgress.index(): Int? = indexByEpisode[key.season to key.episode]

    val known = progress.filter { it.key.postId == id && it.key.isEpisode && it.index() != null }

    val latest = known.maxWithOrNull(compareBy<PlaybackProgress> { it.updatedAtMs }.thenBy { it.index() })
    if (latest != null && latest.resumePositionMs() > 0) {
        return PlayAction(latest.key, latest.positionMs)
    }

    val furthestCompleted = known.filter { it.completed }.maxOfOrNull { it.index()!! }
    val target = episodes.getOrNull(if (furthestCompleted == null) 0 else furthestCompleted + 1) ?: episodes.first()
    val key = WatchKey(id, target.season, target.number)
    return PlayAction(key, known.firstOrNull { it.key == key }.resumePositionMs())
}

/**
 * The audio / quality chips, preselected the way the player will choose: `pick()` over [streams]
 * with [prefs]. Quality options belong to the selected audio (a dub may lack 1080).
 */
internal fun streamChoices(streams: List<StreamOption>, prefs: PlaybackPrefs?): StreamChoices {
    val picked = streams.pick(prefs) ?: return StreamChoices.None
    val present = streams.map { it.audio }.toSet()
    val audioOptions = listOf(AudioType.DUBBED, AudioType.SUBTITLED).filter { it in present }
    val qualities = streams
        .filter { it.audio == picked.audio && it.quality > 0 }
        .map { it.quality }
        .distinct()
        .sortedDescending()
    return StreamChoices(
        audioOptions = audioOptions,
        selectedAudio = picked.audio,
        qualityOptions = qualities,
        selectedQuality = picked.quality.takeIf { it > 0 },
    )
}

/**
 * Cards are never shorter than this share of the progress bar: 1 minute of a 45 minute episode is
 * 2 %, a couple of pixels, which reads as "not started".
 */
internal const val MIN_VISIBLE_PROGRESS = 0.03f

/** The fraction to draw: 0 stays 0 (not started), anything above it is at least [MIN_VISIBLE_PROGRESS]. */
internal fun visibleProgress(fraction: Float): Float =
    if (fraction.isNaN() || fraction <= 0f) 0f else fraction.coerceIn(MIN_VISIBLE_PROGRESS, 1f)

private val GenericEpisodeTitle = Regex("""^\s*(episode|ep\.?|قسمت)\s*[\d۰-۹]*\s*$""", RegexOption.IGNORE_CASE)

/** The title worth showing next to "قسمت ۳": null for the server's generic "Episode 3" placeholder. */
internal fun episodeSubtitle(title: String): String? {
    val trimmed = title.trim()
    if (trimmed.isEmpty() || GenericEpisodeTitle.matches(trimmed)) return null
    return trimmed
}

private fun Episode.toItem(
    titleId: Long,
    progress: Map<Pair<Int, Int>, PlaybackProgress>,
    downloads: Map<WatchKey, DownloadItem>?,
): EpisodeItem {
    val saved = progress[season to number]
    val resume = saved.resumePositionMs()
    return EpisodeItem(
        key = WatchKey(titleId, season, number),
        title = title,
        fraction = if (resume > 0) saved!!.fraction else 0f,
        remainingMs = if (resume > 0) saved!!.remainingMs else 0L,
        completed = saved?.completed == true,
        startPositionMs = resume,
        playable = streams.isNotEmpty(),
        progressLabel = if (resume > 0) formatProgressLabelFa(saved!!.positionMs, saved.durationMs, saved.completed) else null,
        download = downloads?.let { it[WatchKey(titleId, season, number)].toButtonState() },
    )
}

/**
 * Everything the screen shows, derived from the details, the saved progress and the remembered
 * choices. [userSeason] is the season tab the user picked (or the season asked for by the caller); null follows
 * the "continue" episode. [focusTarget] is an episode to focus first; it is ignored (default behaviour) when
 * that season or episode no longer exists.
 */
internal fun buildDetailsContent(
    details: TitleDetails,
    progress: List<PlaybackProgress>,
    prefs: PlaybackPrefs?,
    userSeason: Int?,
    inMyList: Boolean? = null,
    focusTarget: WatchKey? = null,
    /** This title's downloads; null = offline saving not wired in (no download buttons). */
    downloads: List<DownloadItem>? = null,
): DetailsUiState.Content {
    val id = details.summary.id
    val downloadsByKey = downloads?.associateBy { it.key }
    val cast = details.actors.distinctBy { it.id }
    val related = details.related.distinctBy { it.id }

    if (details.type == TitleType.MOVIE) {
        return DetailsUiState.Content(
            details = details,
            cast = cast,
            related = related,
            choices = streamChoices(details.streams, prefs),
            primary = if (details.streams.isEmpty()) null else moviePlayAction(id, progress),
            seasons = emptyList(),
            selectedSeason = 0,
            episodes = emptyList(),
            inMyList = inMyList,
            movieDownload = downloadsByKey?.let { if (details.streams.isEmpty()) null else it[WatchKey(id)].toButtonState() },
        )
    }

    val seasons = details.seasons.filter { it.episodes.isNotEmpty() }.sortedBy { it.number }
    val seasonNumbers = seasons.map { it.number }
    val primary = seriesPlayAction(details, progress)
    val selected = userSeason?.takeIf { it in seasonNumbers }
        ?: primary?.key?.season?.takeIf { it in seasonNumbers }
        ?: seasonNumbers.firstOrNull()
        ?: 0
    val byEpisode = progress
        .filter { it.key.postId == id && it.key.isEpisode }
        .associateBy { it.key.season to it.key.episode }
    val episodes = seasons.firstOrNull { it.number == selected }
        ?.episodes.orEmpty()
        .sortedBy { it.number }
        .map { it.toItem(id, byEpisode, downloadsByKey) }
    val focusEpisode = focusTarget
        ?.takeIf { it.postId == id && it.season == selected }
        ?.takeIf { target -> episodes.any { it.key == target && it.playable } }

    return DetailsUiState.Content(
        details = details,
        cast = cast,
        related = related,
        choices = streamChoices(seasons.flatMap { s -> s.episodes.flatMap { it.streams } }, prefs),
        primary = primary,
        seasons = seasonNumbers,
        selectedSeason = selected,
        episodes = episodes,
        inMyList = inMyList,
        focusEpisode = focusEpisode,
    )
}

/**
 * The episode a caller asked to land on ([season] / [episode] from the route; 0 = none), as a key, or null
 * when no complete target was given.
 */
internal fun episodeFocusTarget(titleId: Long, season: Int, episode: Int): WatchKey? =
    if (season > 0 && episode > 0) WatchKey(titleId, season, episode) else null

/** Audio / quality prefs after the user taps the audio chip: the quality intent is kept. */
internal fun PlaybackPrefs.withAudio(audio: AudioType): PlaybackPrefs = copy(audio = audio)

/**
 * Prefs after the user taps a quality chip. The audio shown at that moment is saved too, so the
 * player plays what the chips displayed even when no audio had been chosen before.
 */
internal fun PlaybackPrefs.withQuality(quality: Int, shownAudio: AudioType?): PlaybackPrefs =
    copy(quality = quality, audio = audio ?: shownAudio)
