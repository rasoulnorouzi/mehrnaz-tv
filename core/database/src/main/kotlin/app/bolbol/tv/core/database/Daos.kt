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

    /** Marks an existing row completed with position = duration. Returns the number of rows changed (0 or 1). */
    @Query("UPDATE progress SET completed = 1, positionMs = durationMs WHERE `key` = :key")
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
     *  2. Keep it only if that row is not completed and the title was not hidden.
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
