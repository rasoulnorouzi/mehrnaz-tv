package app.bolbol.tv.feature.playback

import app.bolbol.tv.core.model.OfflineMedia
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PlaybackRequest
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.pick

/**
 * What the player plays (pure, unit-tested): the network [streams] of the request with the preferred one, or (v1.4) a
 * saved copy, which is then the only stream, so quality/audio switching and the lower-quality fallback have nothing to
 * offer, and no CDN host is pre-warmed.
 */
internal data class PlaybackSource(
    val streams: List<StreamOption>,
    /** null = nothing playable (the «لینک پخشی … پیدا نشد» panel). */
    val stream: StreamOption?,
    val offline: Boolean,
) {
    /** The request the controller gets: only the streams that may be played. */
    fun requestFor(request: PlaybackRequest): PlaybackRequest =
        if (streams == request.streams) request else request.copy(streams = streams)

    companion object {
        /** A saved copy of exactly this episode/movie; null for another key. */
        fun saved(request: PlaybackRequest, media: OfflineMedia): PlaybackSource? =
            if (media.key != request.key) null else PlaybackSource(listOf(media.stream), media.stream, offline = true)

        fun online(request: PlaybackRequest, prefs: PlaybackPrefs): PlaybackSource =
            PlaybackSource(request.streams, request.streams.pick(prefs), offline = false)
    }
}
