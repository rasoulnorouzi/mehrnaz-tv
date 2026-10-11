package app.bolbol.tv.feature.details

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.CatalogRepository
import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.Episode
import app.bolbol.tv.core.model.HomeFeed
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.PrefsRepository
import app.bolbol.tv.core.model.Season
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.model.WatchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

internal fun stream(quality: Int = 720, audio: AudioType = AudioType.SUBTITLED) =
    StreamOption(quality, audio, "https://s/$quality-$audio", null, "${quality}p", null)

internal fun summary(id: Long = 1, type: TitleType? = null) = TitleSummary(
    id = id, type = type, title = "Title $id", faTitle = "عنوان $id", posterUrl = null, backdropUrl = null,
    year = 2024, imdbRating = 7.5f, hasDub = false, hasSubtitle = true,
)

internal fun movie(id: Long = 1, streams: List<StreamOption> = listOf(stream())) = TitleDetails(
    summary = summary(id, TitleType.MOVIE), type = TitleType.MOVIE, plot = "plot", runtimeMinutes = 100,
    ageRating = "12", metacritic = null, genres = listOf("اکشن"), countries = emptyList(), actors = emptyList(),
    directors = emptyList(), trailerUrl = null, updateText = null, streams = streams, seasons = emptyList(),
    related = emptyList(),
)

/** Episode with one 720p stream unless [streams] says otherwise. */
internal fun episode(season: Int, number: Int, streams: List<StreamOption> = listOf(stream())) =
    Episode(season, number, "Episode $number", streams)

internal fun series(id: Long = 1, vararg seasons: Season) = TitleDetails(
    summary = summary(id, TitleType.SERIES), type = TitleType.SERIES, plot = "plot", runtimeMinutes = null,
    ageRating = null, metacritic = null, genres = emptyList(), countries = emptyList(), actors = emptyList(),
    directors = emptyList(), trailerUrl = null, updateText = null, streams = emptyList(),
    seasons = seasons.toList(), related = emptyList(),
)

internal fun season(number: Int, episodes: Int) = Season(number, (1..episodes).map { episode(number, it) })

internal fun progress(
    postId: Long,
    season: Int,
    episode: Int,
    positionMs: Long,
    durationMs: Long = 3_000_000,
    updatedAtMs: Long = 1,
    completed: Boolean = false,
) = PlaybackProgress(WatchKey(postId, season, episode), positionMs, durationMs, updatedAtMs, completed)

internal class FakeCatalog(
    var details: TitleDetails? = null,
    var failure: Throwable? = null,
) : CatalogRepository {
    var detailsCalls = 0
    override fun homeFeed(): Flow<HomeFeed> = emptyFlow()
    override suspend fun details(id: Long): TitleDetails {
        detailsCalls++
        failure?.let { throw it }
        return details ?: throw IllegalStateException("no details configured")
    }

    override suspend fun list(query: ListQuery, page: Int): Page<TitleSummary> = Page(emptyList(), 1, 1)
    override suspend fun search(text: String, page: Int): Page<TitleSummary> = Page(emptyList(), 1, 1)
}

internal class FakeWatch(initial: List<PlaybackProgress> = emptyList()) : WatchRepository {
    val progress = MutableStateFlow(initial)
    override fun continueWatching(limit: Int): Flow<List<ContinueWatchingItem>> = emptyFlow()
    override suspend fun progress(key: WatchKey): PlaybackProgress? = progress.value.firstOrNull { it.key == key }
    override fun progressForTitle(postId: Long): Flow<List<PlaybackProgress>> = progress
    override suspend fun saveProgress(title: TitleSummary, progress: PlaybackProgress) = Unit
    override suspend fun markWatched(key: WatchKey, watched: Boolean) = Unit
    override suspend fun hideFromContinueWatching(postId: Long) = Unit
}

internal class FakePrefs(
    var title: PlaybackPrefs? = null,
    global: PlaybackPrefs = PlaybackPrefs(),
) : PrefsRepository {
    val globalFlow = MutableStateFlow(global)
    val saved = mutableListOf<Pair<Long, PlaybackPrefs>>()
    override fun globalPrefs(): Flow<PlaybackPrefs> = globalFlow
    override suspend fun titlePrefs(postId: Long): PlaybackPrefs? = title
    override suspend fun saveGlobalPrefs(prefs: PlaybackPrefs) {
        globalFlow.value = prefs
    }

    override suspend fun saveTitlePrefs(postId: Long, prefs: PlaybackPrefs) {
        title = prefs
        saved += postId to prefs
    }
}
