package app.bolbol.tv.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Thrown by repositories for expected failures; the UI shows a friendly Persian message. */
sealed class CatalogException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Offline(cause: Throwable? = null) : CatalogException("offline", cause)
    class AllMirrorsDown(cause: Throwable? = null) : CatalogException("all mirrors down", cause)
    class NotFound : CatalogException("not found")
    class BadResponse(cause: Throwable? = null) : CatalogException("bad response", cause)
}

/** Read-only catalogue (implemented in core:network + core:database). */
interface CatalogRepository {
    /** Emits cached rows first (if any, fromCache = true), then fresh rows. Throws only if both fail. */
    fun homeFeed(): Flow<HomeFeed>

    suspend fun details(id: Long): TitleDetails

    suspend fun list(query: ListQuery, page: Int): Page<TitleSummary>

    suspend fun search(text: String, page: Int = 1): Page<TitleSummary>

    /** Genre chips for the Movies / Series pages (v1.4), in display order; empty for other categories. */
    suspend fun genres(category: Category): List<Genre> = emptyList()

    /**
     * Search keyboard predictions (v1.4): up to [limit] completions for what has been typed so far
     * (title words / titles), best first. Must be cheap: local data or one cached request, never a crawl.
     */
    suspend fun suggestions(prefix: String, limit: Int = 8): List<String> = emptyList()

    /**
     * v1.6: one page of titles matching [filter], from the TV's copy of the site's daily index. Null when there is no
     * index yet (the UI then falls back to [list]).
     */
    suspend fun browse(filter: CatalogFilter, page: Int): Page<TitleSummary>? = null

    /** v1.6: genre / country / decade choices for [type], most common first; null without an index. */
    suspend fun facets(type: TitleType): CatalogFacets? = null

    /**
     * v1.6: Home rows computed on the TV: «پیشنهاد برای شما» (like [watchedIds], newest watched first), «تازه‌های
     * دوبله فارسی», «تازه‌های این هفته» and a top-rated genre that changes daily. Empty without an index.
     */
    suspend fun localHomeRows(watchedIds: List<Long>): List<LocalHomeRow> = emptyList()
}

/** Local watch progress and history (Room). The server keeps none. */
interface WatchRepository {
    /** Unfinished items, most recently watched first; one entry per title (latest episode). */
    fun continueWatching(limit: Int = 20): Flow<List<ContinueWatchingItem>>

    suspend fun progress(key: WatchKey): PlaybackProgress?

    /** All saved progress for one title (all episodes), for progress bars on the details screen. */
    fun progressForTitle(postId: Long): Flow<List<PlaybackProgress>>

    /** Called by the player every ~10 s and on pause/stop. [title] is stored as a snapshot for offline painting. */
    suspend fun saveProgress(title: TitleSummary, progress: PlaybackProgress)

    suspend fun markWatched(key: WatchKey, watched: Boolean)

    /** Removes a title from Continue Watching (keeps per-episode watched flags). */
    suspend fun hideFromContinueWatching(postId: Long)
}

interface PrefsRepository {
    fun globalPrefs(): Flow<PlaybackPrefs>
    suspend fun titlePrefs(postId: Long): PlaybackPrefs?
    suspend fun saveGlobalPrefs(prefs: PlaybackPrefs)
    suspend fun saveTitlePrefs(postId: Long, prefs: PlaybackPrefs)

    // Subtitles (docs/subtitles.md). Defaults keep older implementations/fakes compiling;
    // the real implementation in core:data overrides all four.
    fun subtitleStyle(): Flow<SubtitleStyle> = flowOf(SubtitleStyle())
    suspend fun saveSubtitleStyle(style: SubtitleStyle) {}

    /** Per-title timing offset in ms (positive = subtitles appear later). 0 when never set. */
    suspend fun subtitleOffsetMs(postId: Long): Int = 0
    suspend fun saveSubtitleOffsetMs(postId: Long, offsetMs: Int) {}
}
