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
     * player that never reports "completed" still finishes titles; an unknown duration (<= 0) never forces it.
     * The row is a `source = local` row, and its real duration replaces an unknown one left by a watch-list import.
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
     * Saves progress pulled from the site's watch-list, as a `source = remote` row. Imports are **never completed**:
     * the server says nothing about a finished item and its duration is untrusted, so completion stays with the local
     * player ([saveProgress]) and the user ([markWatched]). A duration that would put [PlaybackProgress.positionMs]
     * at or past the completion mark is dropped to 0 (= unknown) instead of finishing the title. The real duration
     * replaces an unknown one the next time the local player saves progress for the key.
     */
    suspend fun importRemote(title: TitleSummary, progress: PlaybackProgress) {
        require(title.id == progress.key.postId) { "snapshot ${title.id} does not match key ${progress.key.asString()}" }
        val durationMs = if (reachedCompletion(progress.positionMs, progress.durationMs)) 0 else progress.durationMs.coerceAtLeast(0)
        withContext(io) {
            dao.saveProgress(
                snapshot = title.toSnapshot(hiddenFromContinue = false, lastWatchedAtMs = progress.updatedAtMs),
                progress = progress.copy(durationMs = durationMs, completed = false).toEntity(ProgressEntity.SOURCE_REMOTE),
            )
        }
    }

    /**
     * Resets remote-imported rows that an older pull left with an untrusted duration (marked completed, or a duration
     * under 60 s / over 6 h) to "not completed, duration unknown" (QA-03). Never touches rows the local player or the
     * user wrote. Idempotent. Returns the number of rows repaired.
     */
    suspend fun repairRemoteImports(): Int = withContext(io) { dao.repairRemoteImports() }

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

        /** false when the duration is unknown (<= 0): without a duration nothing can have been "reached". */
        internal fun reachedCompletion(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && positionMs * 100 >= durationMs * COMPLETION_PERCENT
    }
}
