package app.bolbol.tv.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Progress and title snapshots. One DAO because every multi-row operation spans both tables.
 * Abstract class (not interface) so [Transaction] methods can have bodies without jvm-default flags.
 */
@Dao
abstract class WatchDao {

    // --- writes ------------------------------------------------------------------------------

    @Upsert
    abstract suspend fun upsertSnapshot(snapshot: TitleSnapshotEntity)

    @Upsert
    abstract suspend fun upsertProgress(progress: ProgressEntity)

    /** Snapshot + progress in one transaction, so Continue Watching never sees one without the other. */
    @Transaction
    open suspend fun saveProgress(snapshot: TitleSnapshotEntity, progress: ProgressEntity) {
        upsertSnapshot(snapshot)
        upsertProgress(progress)
    }

    /**
     * Marks an existing row completed with position = duration (an unknown duration keeps the position) and as a local
     * row, since the user decided. Returns the number of rows changed (0 or 1).
     */
    @Query(
        """
        UPDATE progress
        SET completed = 1, positionMs = CASE WHEN durationMs > 0 THEN durationMs ELSE positionMs END, source = 'local'
        WHERE `key` = :key
        """,
    )
    abstract suspend fun markCompleted(key: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(progress: ProgressEntity)

    /**
     * "Mark as watched": completes the existing row, or inserts [fallback] (a completed row with unknown
     * duration) when the item was never played. Does not touch updatedAtMs of an existing row, so marking an
     * old episode watched does not change which episode Continue Watching points at.
     */
    @Transaction
    open suspend fun markWatched(key: String, fallback: ProgressEntity) {
        if (markCompleted(key) == 0) insertIfAbsent(fallback)
    }

    @Query("DELETE FROM progress WHERE `key` = :key")
    abstract suspend fun deleteProgress(key: String)

    /**
     * Repairs rows that an earlier watch-list pull imported with an untrusted server duration (QA-03). Only
     * `source = 'remote'` rows are touched, i.e. nothing the local player or the user wrote. A remote row is reset to
     * not completed with an unknown duration when its duration cannot be real (under 60 s or over 6 h), or when it is
     * marked completed although the position is under 95 % of the duration. Idempotent; run it on every pull.
     * Returns the number of rows repaired.
     */
    @Query(
        """
        UPDATE progress SET completed = 0, durationMs = 0
        WHERE source = 'remote'
          AND (
            (durationMs > 0 AND (durationMs < 60000 OR durationMs > 21600000 OR durationMs < positionMs))
            OR (completed = 1 AND (durationMs < 60000 OR positionMs * 100 < durationMs * 95))
          )
        """,
    )
    abstract suspend fun repairRemoteImports(): Int

    /** Removes a title from Continue Watching only; progress rows (watched ticks) stay. */
    @Query("UPDATE title_snapshot SET hiddenFromContinue = 1 WHERE postId = :postId")
    abstract suspend fun hideFromContinue(postId: Long)

    // --- reads -------------------------------------------------------------------------------

    @Query("SELECT * FROM progress WHERE `key` = :key")
    abstract suspend fun progress(key: String): ProgressEntity?

    /** All saved progress of one title, ordered season then episode (movies have a single 0:0 row). */
    @Query("SELECT * FROM progress WHERE postId = :postId ORDER BY season, episode")
    abstract fun observeProgressForTitle(postId: Long): Flow<List<ProgressEntity>>

    /**
     * Continue Watching: one entry per title.
     *  1. For every title take its most recent progress row (highest updatedAtMs; ties broken by key so
     *     exactly one row qualifies).
     *  2. Keep it only if that row is not completed and the title was not hidden. Rows with an unknown duration
     *     (durationMs = 0, imported from the site's watch-list) are kept: they are real unfinished items.
     *  3. Join the snapshot to paint the row, newest first.
     * Snapshot columns are aliased with an `s_` prefix to match [ContinueWatchingRow].
     */
    @Query(
        """
        SELECT p.*,
               s.postId AS s_postId, s.type AS s_type, s.title AS s_title, s.faTitle AS s_faTitle,
               s.posterUrl AS s_posterUrl, s.backdropUrl AS s_backdropUrl, s.year AS s_year,
               s.imdbRating AS s_imdbRating, s.hasDub AS s_hasDub, s.hasSubtitle AS s_hasSubtitle,
               s.hiddenFromContinue AS s_hiddenFromContinue, s.lastWatchedAtMs AS s_lastWatchedAtMs
        FROM progress AS p
        INNER JOIN title_snapshot AS s ON s.postId = p.postId
        WHERE p.`key` = (
                SELECT p2.`key` FROM progress AS p2
                WHERE p2.postId = p.postId
                ORDER BY p2.updatedAtMs DESC, p2.`key` DESC
                LIMIT 1)
          AND p.completed = 0
          AND s.hiddenFromContinue = 0
        ORDER BY p.updatedAtMs DESC
        LIMIT :limit
        """,
    )
    abstract fun observeContinueWatching(limit: Int): Flow<List<ContinueWatchingRow>>
}

/** Playback preferences; postId 0 is the global row. */
@Dao
interface PrefsDao {
    @Query("SELECT * FROM prefs WHERE postId = :postId")
    fun observe(postId: Long): Flow<PrefsEntity?>

    @Query("SELECT * FROM prefs WHERE postId = :postId")
    suspend fun get(postId: Long): PrefsEntity?

    @Upsert
    suspend fun upsert(prefs: PrefsEntity)

    // --- subtitles (docs/subtitles.md): kept in their own tables so saving playback prefs and subtitle
    // --- settings can never overwrite each other.

    /** Global subtitle look; emits null while nothing was ever saved (the repository maps that to defaults). */
    @Query("SELECT * FROM subtitle_style WHERE id = 1")
    fun observeSubtitleStyle(): Flow<SubtitleStyleEntity?>

    @Upsert
    suspend fun upsertSubtitleStyle(style: SubtitleStyleEntity)

    @Query("SELECT offsetMs FROM subtitle_offset WHERE postId = :postId")
    suspend fun subtitleOffsetMs(postId: Long): Int?

    @Upsert
    suspend fun upsertSubtitleOffset(offset: SubtitleOffsetEntity)

    /** Offset 0 is stored as "no row". */
    @Query("DELETE FROM subtitle_offset WHERE postId = :postId")
    suspend fun deleteSubtitleOffset(postId: Long)
}

/** Raw home JSON cache; a single row with id 1. */
@Dao
interface HomeCacheDao {
    @Query("SELECT * FROM home_cache WHERE id = 1")
    suspend fun get(): HomeCacheEntity?

    @Upsert
    suspend fun upsert(entity: HomeCacheEntity)
}

/**
 * "My List". Single-statement writes guarded by the expected `pendingSync` value, so a sync running in the background
 * can never overwrite a change the user made meanwhile (see `AccountRepositoryImpl.syncMyList`).
 * Abstract class like [WatchDao] so tests can fake it.
 */
@Dao
abstract class MyListDao {

    /** Visible items (no tombstones), newest first. */
    @Query("SELECT * FROM my_list WHERE pendingSync != 2 ORDER BY addedAtMs DESC, postId DESC")
    abstract fun observeVisible(): Flow<List<MyListEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM my_list WHERE postId = :postId AND pendingSync != 2)")
    abstract fun observeContains(postId: Long): Flow<Boolean>

    /** Every row including tombstones, for the sync. */
    @Query("SELECT * FROM my_list")
    abstract suspend fun all(): List<MyListEntity>

    @Query("SELECT * FROM my_list WHERE postId = :postId")
    abstract suspend fun get(postId: Long): MyListEntity?

    /** Rows still waiting for the server (adds and tombstones). */
    @Query("SELECT COUNT(*) FROM my_list WHERE pendingSync != 0")
    abstract suspend fun pendingCount(): Int

    @Upsert
    abstract suspend fun upsert(entity: MyListEntity)

    /** Server-side item we do not have yet. Returns the rowid, or -1 when the row already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(entity: MyListEntity): Long

    /** Local removal: keep the row hidden until the server confirms it. */
    @Query("UPDATE my_list SET pendingSync = 2 WHERE postId = :postId")
    abstract suspend fun markRemoved(postId: Long)

    @Query("DELETE FROM my_list WHERE postId = :postId")
    abstract suspend fun delete(postId: Long)

    /** Marks the row as in sync, but only if it is still in the [expected] pending state. */
    @Query("UPDATE my_list SET pendingSync = 0 WHERE postId = :postId AND pendingSync = :expected")
    abstract suspend fun clearPending(postId: Long, expected: Int)

    /** Deletes the row, but only if it is still in the [expected] pending state. */
    @Query("DELETE FROM my_list WHERE postId = :postId AND pendingSync = :expected")
    abstract suspend fun deleteIfPending(postId: Long, expected: Int)

    /** After a login every synced row becomes "to add", so the first sync is a union and deletes nothing. */
    @Query("UPDATE my_list SET pendingSync = 1 WHERE pendingSync = 0")
    abstract suspend fun markSyncedAsPendingAdd()
}

/** Offline saving (v1.4). Status values are DownloadStatus names; the engine in :core:download is the only writer. */
@Dao
interface DownloadDao {
    /** Newest first. */
    @Query("SELECT * FROM download ORDER BY createdAtMs DESC, `key` DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download WHERE postId = :postId ORDER BY season, episode")
    fun observeForTitle(postId: Long): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download ORDER BY createdAtMs DESC, `key` DESC")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM download WHERE `key` = :key")
    suspend fun get(key: String): DownloadEntity?

    /** The oldest queued item: downloads run one at a time in the order they were asked for. */
    @Query("SELECT * FROM download WHERE status = 'QUEUED' ORDER BY createdAtMs ASC, `key` ASC LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Query("UPDATE download SET bytesDownloaded = :bytes, totalBytes = :total WHERE `key` = :key")
    suspend fun updateProgress(key: String, bytes: Long, total: Long?)

    @Query("UPDATE download SET status = :status, failure = :failure WHERE `key` = :key")
    suspend fun updateStatus(key: String, status: String, failure: String?)

    @Query("UPDATE download SET validator = :validator WHERE `key` = :key")
    suspend fun updateValidator(key: String, validator: String?)

    @Query("UPDATE download SET subtitlePath = :subtitlePath, posterPath = :posterPath WHERE `key` = :key")
    suspend fun updateSidecars(key: String, subtitlePath: String?, posterPath: String?)

    /** App start: whatever was running or waiting for the network is queued again. Returns the rows changed. */
    @Query("UPDATE download SET status = 'QUEUED', failure = NULL WHERE status IN ('DOWNLOADING', 'PAUSED')")
    suspend fun requeueInterrupted(): Int

    /** The network is back: downloads paused for it are queued again. Returns the rows changed. */
    @Query("UPDATE download SET status = 'QUEUED', failure = NULL WHERE status = 'PAUSED'")
    suspend fun requeuePaused(): Int

    @Query("DELETE FROM download WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM download")
    suspend fun deleteAll()
}
