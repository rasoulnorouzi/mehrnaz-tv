package app.bolbol.tv.core.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room entities. Plain storage types: no domain logic, mapping to :core:model types lives in :core:data.
 * Booleans are stored as 0/1, enums as their name, so the schema is easy to read with `sqlite3`.
 */

/**
 * Playback position for one movie or episode.
 * [key] is the WatchKey string "postId:season:episode" (movies: "353371:0:0").
 * Stream URLs are deliberately not stored: they change with CDN hosts.
 */
@Entity(tableName = "progress", indices = [Index("postId")])
data class ProgressEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    val postId: Long,
    val season: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
    val completed: Boolean,
)

/**
 * Last known TitleSummary of a watched title, so Continue Watching paints offline.
 * [type] is the TitleType name or null. [hiddenFromContinue] is set by "remove from Continue Watching"
 * and cleared by the next saved progress.
 */
@Entity(tableName = "title_snapshot")
data class TitleSnapshotEntity(
    @PrimaryKey val postId: Long,
    val type: String?,
    val title: String,
    val faTitle: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: Int?,
    val imdbRating: Float?,
    val hasDub: Boolean,
    val hasSubtitle: Boolean,
    val hiddenFromContinue: Boolean,
    val lastWatchedAtMs: Long,
)

/** Playback preferences. [postId] 0 is the global default row; any other id overrides it for one title. */
@Entity(tableName = "prefs")
data class PrefsEntity(
    @PrimaryKey val postId: Long,
    /** AudioType name or null (no preference). */
    val audio: String?,
    val quality: Int?,
    val subtitleScale: Float,
) {
    companion object {
        const val GLOBAL_POST_ID = 0L
    }
}

/**
 * The user's global subtitle look (docs/subtitles.md), single row with id 1. Enums are stored by name; an unknown
 * name reads back as the default, so a newer/older build never crashes on it.
 */
@Entity(tableName = "subtitle_style")
data class SubtitleStyleEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val enabled: Boolean,
    val sizeScale: Float,
    /** SubtitleColor name. */
    val color: String,
    /** SubtitleBackground name. */
    val background: String,
    val bold: Boolean,
    /** SubtitlePosition name. */
    val position: String,
) {
    companion object {
        const val SINGLE_ROW_ID = 1
    }
}

/** Per-title subtitle timing offset in ms (positive = subtitles later). A missing row means 0. */
@Entity(tableName = "subtitle_offset")
data class SubtitleOffsetEntity(
    @PrimaryKey val postId: Long,
    val offsetMs: Int,
)

/** Single-row cache (id = 1) of the raw `post/all` JSON, so the home screen paints before the network answers. */
@Entity(tableName = "home_cache")
data class HomeCacheEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val json: String,
    val fetchedAtMs: Long,
) {
    companion object {
        const val SINGLE_ROW_ID = 1
    }
}

/** Result row of the Continue Watching join: the latest progress row of a title plus its snapshot. */
data class ContinueWatchingRow(
    @Embedded val progress: ProgressEntity,
    @Embedded(prefix = "s_") val snapshot: TitleSnapshotEntity,
)
