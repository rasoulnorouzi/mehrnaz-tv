package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.ContinueWatchingRow
import app.bolbol.tv.core.database.PrefsEntity
import app.bolbol.tv.core.database.ProgressEntity
import app.bolbol.tv.core.database.TitleSnapshotEntity
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.model.WatchKey

/* Room entity <-> :core:model conversions. Enums are stored by name; unknown names read back as null. */

internal fun PlaybackProgress.toEntity(): ProgressEntity = ProgressEntity(
    key = key.asString(),
    postId = key.postId,
    season = key.season,
    episode = key.episode,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAtMs = updatedAtMs,
    completed = completed,
)

internal fun ProgressEntity.toModel(): PlaybackProgress = PlaybackProgress(
    key = WatchKey(postId, season, episode),
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAtMs = updatedAtMs,
    completed = completed,
)

internal fun TitleSummary.toSnapshot(hiddenFromContinue: Boolean, lastWatchedAtMs: Long): TitleSnapshotEntity =
    TitleSnapshotEntity(
        postId = id,
        type = type?.name,
        title = title,
        faTitle = faTitle,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        year = year,
        imdbRating = imdbRating,
        hasDub = hasDub,
        hasSubtitle = hasSubtitle,
        hiddenFromContinue = hiddenFromContinue,
        lastWatchedAtMs = lastWatchedAtMs,
    )

internal fun TitleSnapshotEntity.toSummary(): TitleSummary = TitleSummary(
    id = postId,
    type = type?.let { name -> TitleType.entries.firstOrNull { it.name == name } },
    title = title,
    faTitle = faTitle,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    year = year,
    imdbRating = imdbRating,
    hasDub = hasDub,
    hasSubtitle = hasSubtitle,
)

internal fun ContinueWatchingRow.toItem(): ContinueWatchingItem =
    ContinueWatchingItem(title = snapshot.toSummary(), progress = progress.toModel())

internal fun PlaybackPrefs.toEntity(postId: Long): PrefsEntity =
    PrefsEntity(postId = postId, audio = audio?.name, quality = quality, subtitleScale = subtitleScale)

internal fun PrefsEntity.toModel(): PlaybackPrefs = PlaybackPrefs(
    audio = audio?.let { name -> AudioType.entries.firstOrNull { it.name == name } },
    quality = quality,
    subtitleScale = subtitleScale,
)
