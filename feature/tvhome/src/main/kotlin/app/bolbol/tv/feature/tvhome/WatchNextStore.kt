package app.bolbol.tv.feature.tvhome

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.BaseColumns
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.TvContractCompat.PreviewProgramColumns
import androidx.tvprovider.media.tv.TvContractCompat.PreviewPrograms
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import androidx.tvprovider.media.tv.WatchNextProgram

/** The launcher's Watch Next table as seen by this app. Blocking; callers run it on an IO dispatcher. */
internal interface WatchNextStore {
    /** Every row our package owns (the provider hides other apps' rows), including ones the user removed. */
    fun readAll(): List<ExistingProgram>

    fun insert(spec: WatchNextSpec)

    /** false when the row no longer exists (the caller then inserts). */
    fun update(id: Long, spec: WatchNextSpec): Boolean

    fun delete(id: Long)

    fun deleteAll()
}

/**
 * `WatchNextProgram` as stored by TvProvider. Notes (AOSP TvProvider, Android 10):
 *  - queries must not pass a selection (SecurityException unless the caller has ACCESS_ALL_EPG_DATA); the provider
 *    already limits results to the calling package;
 *  - `browsable` cannot be written by apps: [WatchNextProgram.toContentValues] leaves it out.
 */
internal class TvProviderWatchNextStore(context: Context) : WatchNextStore {
    private val resolver = context.applicationContext.contentResolver

    override fun readAll(): List<ExistingProgram> {
        val cursor = resolver.query(WatchNextPrograms.CONTENT_URI, READ_PROJECTION, null, null, null)
            ?: error("TvProvider returned no cursor (no android.media.tv provider on this device?)")
        cursor.use { c ->
            val id = c.getColumnIndexOrThrow(BaseColumns._ID)
            val providerId = c.getColumnIndexOrThrow(PreviewProgramColumns.COLUMN_INTERNAL_PROVIDER_ID)
            val browsable = c.getColumnIndexOrThrow(PreviewProgramColumns.COLUMN_BROWSABLE)
            val engagement = c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS)
            val flag1 = c.getColumnIndexOrThrow(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_FLAG1)
            val rows = ArrayList<ExistingProgram>(c.count)
            while (c.moveToNext()) {
                rows += ExistingProgram(
                    id = c.getLong(id),
                    providerId = if (c.isNull(providerId)) null else c.getString(providerId),
                    browsable = c.isNull(browsable) || c.getInt(browsable) != 0,
                    lastEngagementMs = if (c.isNull(engagement)) 0L else c.getLong(engagement),
                    fingerprint = if (c.isNull(flag1)) null else c.getLong(flag1),
                )
            }
            return rows
        }
    }

    override fun insert(spec: WatchNextSpec) {
        resolver.insert(WatchNextPrograms.CONTENT_URI, spec.toProgram().toContentValues())
            ?: error("TvProvider refused to insert ${spec.key.asString()}")
    }

    override fun update(id: Long, spec: WatchNextSpec): Boolean {
        val values: ContentValues = spec.toProgram().toContentValues()
        // A removed image must not linger: absent columns are left untouched by update().
        if (spec.posterUrl == null) values.putNull(WatchNextPrograms.COLUMN_POSTER_ART_URI)
        return resolver.update(TvContractCompat.buildWatchNextProgramUri(id), values, null, null) > 0
    }

    override fun delete(id: Long) {
        resolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
    }

    override fun deleteAll() {
        // No selection: the provider scopes the delete to our package.
        resolver.delete(WatchNextPrograms.CONTENT_URI, null, null)
    }

    private companion object {
        val READ_PROJECTION = arrayOf(
            BaseColumns._ID,
            PreviewProgramColumns.COLUMN_INTERNAL_PROVIDER_ID,
            PreviewProgramColumns.COLUMN_BROWSABLE,
            WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_FLAG1,
        )
    }
}

internal fun WatchNextSpec.toProgram(): WatchNextProgram {
    val builder = WatchNextProgram.Builder()
        .setType(
            when (kind) {
                ProgramKind.MOVIE -> PreviewPrograms.TYPE_MOVIE
                ProgramKind.TV_EPISODE -> PreviewPrograms.TYPE_TV_EPISODE
            },
        )
        .setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
        .setTitle(title)
        .setLastPlaybackPositionMillis(positionMs)
        .setDurationMillis(durationMs)
        .setLastEngagementTimeUtcMillis(lastEngagementMs)
        .setInternalProviderId(key.asString())
        .setInternalProviderFlag1(fingerprint())
        .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).setPackage(appPackage))
    if (seasonNumber != null) builder.setSeasonNumber(seasonNumber)
    if (episodeNumber != null) builder.setEpisodeNumber(episodeNumber)
    if (episodeTitle != null) builder.setEpisodeTitle(episodeTitle)
    if (posterUrl != null) {
        builder.setPosterArtUri(Uri.parse(posterUrl))
        builder.setPosterArtAspectRatio(
            when (posterShape) {
                PosterShape.WIDE_16_9 -> PreviewPrograms.ASPECT_RATIO_16_9
                PosterShape.POSTER_2_3 -> PreviewPrograms.ASPECT_RATIO_2_3
            },
        )
    }
    return builder.build()
}
