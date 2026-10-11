package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.ProgressEntity
import app.bolbol.tv.core.database.WatchDao
import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.model.WatchRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Local watch progress on Room. The server keeps none. */
internal class WatchRepositoryImpl(
    private val dao: WatchDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WatchRepository {

    override fun continueWatching(limit: Int): Flow<List<ContinueWatchingItem>> =
        dao.observeContinueWatching(limit).map { rows -> rows.map { it.toItem() } }

    override suspend fun progress(key: WatchKey): PlaybackProgress? = withContext(io) {
        dao.progress(key.asString())?.toModel()
    }

    override fun progressForTitle(postId: Long): Flow<List<PlaybackProgress>> =
        dao.observeProgressForTitle(postId).map { rows -> rows.map { it.toModel() } }

    /**
     * Upserts the title snapshot (un-hiding the title and stamping lastWatchedAt) and the progress row in one
     * transaction. [PlaybackProgress.completed] is also forced to true at >= 95 % of the duration, so a
     * player that never reports "completed" still finishes titles.
     */
    override suspend fun saveProgress(title: TitleSummary, progress: PlaybackProgress) {
        require(title.id == progress.key.postId) { "snapshot ${title.id} does not match key ${progress.key.asString()}" }
        val completed = progress.completed || reachedCompletion(progress.positionMs, progress.durationMs)
        withContext(io) {
            dao.saveProgress(
                snapshot = title.toSnapshot(hiddenFromContinue = false, lastWatchedAtMs = progress.updatedAtMs),
                progress = progress.copy(completed = completed).toEntity(),
            )
        }
    }

    /**
     * true: completed with position = duration (a never-played item gets a completed row with unknown duration);
     * false: forget the progress of that item entirely.
     */
    override suspend fun markWatched(key: WatchKey, watched: Boolean) = withContext(io) {
        if (watched) {
            dao.markWatched(
                key = key.asString(),
                fallback = ProgressEntity(
                    key = key.asString(),
                    postId = key.postId,
                    season = key.season,
                    episode = key.episode,
                    positionMs = 0,
                    durationMs = 0,
                    // 0 keeps this row from becoming "the latest episode" of the title.
                    updatedAtMs = 0,
                    completed = true,
                ),
            )
        } else {
            dao.deleteProgress(key.asString())
        }
    }

    override suspend fun hideFromContinueWatching(postId: Long) = withContext(io) {
        dao.hideFromContinue(postId)
    }

    companion object {
        /** Percent of the duration after which an item counts as watched. */
        const val COMPLETION_PERCENT = 95

        internal fun reachedCompletion(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && positionMs * 100 >= durationMs * COMPLETION_PERCENT
    }
}
