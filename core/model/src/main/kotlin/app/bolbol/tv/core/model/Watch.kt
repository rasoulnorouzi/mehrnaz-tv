package app.bolbol.tv.core.model

/**
 * Identifies what is being watched, independent of stream URLs (which can change).
 * Movies use season = 0, episode = 0.
 */
data class WatchKey(val postId: Long, val season: Int = 0, val episode: Int = 0) {
    val isEpisode: Boolean get() = season > 0 && episode > 0

    /** Stable storage form "postId:season:episode". */
    fun asString(): String = "$postId:$season:$episode"

    companion object {
        fun parse(value: String): WatchKey? {
            val parts = value.split(':')
            if (parts.size != 3) return null
            val id = parts[0].toLongOrNull() ?: return null
            val s = parts[1].toIntOrNull() ?: return null
            val e = parts[2].toIntOrNull() ?: return null
            return WatchKey(id, s, e)
        }
    }
}

/** Saved playback position. Written by the player, read by home/details/player. */
data class PlaybackProgress(
    val key: WatchKey,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
    /** True once the viewer passed ~95 % or the credits. */
    val completed: Boolean,
) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
}

/** A "Continue Watching" entry: the title snapshot is stored so the row paints offline. */
data class ContinueWatchingItem(
    val title: TitleSummary,
    val progress: PlaybackProgress,
)

/** Remembered playback choices (global defaults and per-title overrides). */
data class PlaybackPrefs(
    val audio: AudioType? = null,
    val quality: Int? = null,
    /** Deprecated: use [SubtitleStyle.sizeScale] via PrefsRepository.subtitleStyle(). Removed after M1.10. */
    val subtitleScale: Float = 1.0f,
)

/** Everything the player screen needs to start; built by the details screen. */
data class PlaybackRequest(
    val key: WatchKey,
    val title: TitleSummary,
    /** Secondary line, e.g. "فصل ۱ · قسمت ۲", null for movies. */
    val subtitleLine: String?,
    val streams: List<StreamOption>,
    /** Start position; null = ask/resume from saved progress. */
    val startPositionMs: Long?,
    /** Next episode to auto-play, null for movies or the last episode. */
    val next: WatchKey?,
    /** Episode before this one (across seasons), null for movies or the first episode (v1.1-1). */
    val previous: WatchKey? = null,
    /**
     * The whole series in watch order (seasons and their episodes sorted), for the in-player
     * «قسمت‌ها» panel (v1.1-4). Empty for movies.
     */
    val seasons: List<Season> = emptyList(),
)
