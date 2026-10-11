package app.bolbol.tv.feature.home

import app.bolbol.tv.core.model.CatalogRepository
import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.HomeFeed
import app.bolbol.tv.core.model.HomeSection
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.Page
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.TitleDetails
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.model.WatchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal fun title(id: Long, fa: String = "عنوان $id") = TitleSummary(
    id = id, type = null, title = "Title $id", faTitle = fa, posterUrl = null, backdropUrl = null,
    year = 2024, imdbRating = 7.0f, hasDub = true, hasSubtitle = false,
)

internal fun section(id: String, vararg ids: Long, viewAll: ListQuery? = null) =
    HomeSection(id = id, title = "Row $id", items = ids.map { title(it) }, viewAll = viewAll)

internal fun feed(vararg sections: HomeSection, fromCache: Boolean = false) = HomeFeed(sections.toList(), fromCache)

internal fun resumable(id: Long, season: Int = 0, episode: Int = 0) = ContinueWatchingItem(
    title = title(id),
    progress = PlaybackProgress(WatchKey(id, season, episode), 600_000, 3_000_000, updatedAtMs = id, completed = false),
)

internal class FakeCatalog(private val source: () -> Flow<HomeFeed>) : CatalogRepository {
    var homeCalls = 0
    override fun homeFeed(): Flow<HomeFeed> {
        homeCalls++
        return source()
    }

    override suspend fun details(id: Long): TitleDetails = throw UnsupportedOperationException()
    override suspend fun list(query: ListQuery, page: Int): Page<TitleSummary> = throw UnsupportedOperationException()
    override suspend fun search(text: String, page: Int): Page<TitleSummary> = throw UnsupportedOperationException()
}

internal class FakeWatch(initial: List<ContinueWatchingItem> = emptyList()) : WatchRepository {
    val items = MutableStateFlow(initial)
    val markedWatched = mutableListOf<Pair<WatchKey, Boolean>>()
    val hidden = mutableListOf<Long>()
    var failWrites = false
    override fun continueWatching(limit: Int): Flow<List<ContinueWatchingItem>> = items
    override suspend fun progress(key: WatchKey): PlaybackProgress? = null
    override fun progressForTitle(postId: Long): Flow<List<PlaybackProgress>> = MutableStateFlow(emptyList())
    override suspend fun saveProgress(title: TitleSummary, progress: PlaybackProgress) = Unit
    override suspend fun markWatched(key: WatchKey, watched: Boolean) {
        if (failWrites) throw IllegalStateException("db")
        markedWatched += key to watched
    }

    override suspend fun hideFromContinueWatching(postId: Long) {
        if (failWrites) throw IllegalStateException("db")
        hidden += postId
    }
}
