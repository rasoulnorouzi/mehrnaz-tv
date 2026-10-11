package app.bolbol.tv.core.data

import app.bolbol.tv.core.database.ContinueWatchingRow
import app.bolbol.tv.core.database.PrefsEntity
import app.bolbol.tv.core.database.ProgressEntity
import app.bolbol.tv.core.database.SubtitleStyleEntity
import app.bolbol.tv.core.database.TitleSnapshotEntity
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.SubtitleBackground
import app.bolbol.tv.core.model.SubtitleColor
import app.bolbol.tv.core.model.SubtitlePosition
import app.bolbol.tv.core.model.SubtitleStyle
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

internal fun SubtitleStyle.toEntity(): SubtitleStyleEntity = SubtitleStyleEntity(
    enabled = enabled,
    sizeScale = sizeScale,
    color = color.name,
    background = background.name,
    bold = bold,
    position = position.name,
)

/** Unknown stored enum names fall back to the defaults of [SubtitleStyle]. */
internal fun SubtitleStyleEntity.toModel(): SubtitleStyle {
    val defaults = SubtitleStyle()
    return SubtitleStyle(
        enabled = enabled,
        sizeScale = sizeScale,
        color = SubtitleColor.entries.firstOrNull { it.name == color } ?: defaults.color,
        background = SubtitleBackground.entries.firstOrNull { it.name == background } ?: defaults.background,
        bold = bold,
        position = SubtitlePosition.entries.firstOrNull { it.name == position } ?: defaults.position,
    )
}

/**
 * Rounds a timing offset to the nearest [SubtitleStyle.OFFSET_STEP_MS] (halves away from zero, so +250 -> +500 and
 * -250 -> -500) and clamps it to +-[SubtitleStyle.OFFSET_MAX_MS].
 */
internal fun normalizeSubtitleOffsetMs(offsetMs: Int): Int {
    val step = SubtitleStyle.OFFSET_STEP_MS.toLong()
    val magnitude = (kotlin.math.abs(offsetMs.toLong()) + step / 2) / step * step
    val signed = if (offsetMs < 0) -magnitude else magnitude
    return signed.coerceIn(-SubtitleStyle.OFFSET_MAX_MS.toLong(), SubtitleStyle.OFFSET_MAX_MS.toLong()).toInt()
}
