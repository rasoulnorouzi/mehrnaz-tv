@file:OptIn(UnstableApi::class)

package app.bolbol.tv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ForwardingMediaSourceFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * "Start this item at the keyframe before the requested position" (v1.1-2, quality switch).
 *
 * `setMediaItem(item, positionMs)` ignores `SeekParameters`: ExoPlayer loads from the keyframe
 * before the position and decodes (without showing) every frame up to it. The site's x264 files
 * have up to 10 s between keyframes, so a 1080p switch could download ~5 MB and decode ~250
 * frames before the first picture. Items tagged with [KeyframeStart] (set as
 * `MediaItem.Builder.setTag`) start exactly at that keyframe instead (≤ 10 s earlier, mean ~1.5 s,
 * so nothing is skipped). Only the start is snapped; later seeks use the player's normal mode.
 */
object KeyframeStart

/** Pure rule for the snapped start (unit-tested). */
object KeyframeSnap {
    /**
     * [adjustedUs] is the media's keyframe at or before [requestedUs]. Use it only when it is a
     * real step back of at most [maxBackUs]; anything else (0 from an unseekable file, a
     * keyframe after the position) keeps the requested position.
     */
    fun choose(requestedUs: Long, adjustedUs: Long, maxBackUs: Long): Long =
        if (adjustedUs in (requestedUs - maxBackUs)..requestedUs && adjustedUs >= 0) adjustedUs else requestedUs
}

/** Wraps the sources of items tagged [KeyframeStart]; everything else passes through untouched. */
internal class KeyframeStartMediaSourceFactory(delegate: MediaSource.Factory) : ForwardingMediaSourceFactory(delegate) {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val source = super.createMediaSource(mediaItem)
        return if (mediaItem.localConfiguration?.tag === KeyframeStart) KeyframeStartMediaSource(source) else source
    }
}

internal class KeyframeStartMediaSource(source: MediaSource) : WrappingMediaSource(source) {
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        KeyframeStartMediaPeriod(super.createPeriod(id, allocator, startPositionUs), PlayerTuning.SWITCH_MAX_SNAP_BACK_MS * 1_000)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        super.releasePeriod((mediaPeriod as KeyframeStartMediaPeriod).inner)
    }
}

/**
 * Delegates everything to [inner], except that the *first* track selection (= the start
 * position; MediaPeriodHolder adopts the position it returns) is moved back to the previous
 * keyframe via [SeekParameters.PREVIOUS_SYNC].
 */
internal class KeyframeStartMediaPeriod(
    val inner: MediaPeriod,
    private val maxSnapBackUs: Long,
) : MediaPeriod, MediaPeriod.Callback {

    private var callback: MediaPeriod.Callback? = null
    private var startChosen = false

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        inner.prepare(this, positionUs)
    }

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        val start = if (startChosen) {
            positionUs
        } else {
            startChosen = true
            KeyframeSnap.choose(positionUs, inner.getAdjustedSeekPositionUs(positionUs, SeekParameters.PREVIOUS_SYNC), maxSnapBackUs)
        }
        return inner.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, start)
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        callback?.onPrepared(this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        callback?.onContinueLoadingRequested(this)
    }

    override fun maybeThrowPrepareError() = inner.maybeThrowPrepareError()
    override fun getTrackGroups(): TrackGroupArray = inner.trackGroups
    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> = inner.getStreamKeys(trackSelections)
    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = inner.discardBuffer(positionUs, toKeyframe)
    override fun readDiscontinuity(): Long = inner.readDiscontinuity()
    override fun setUsesStreamPrerollFlags() = inner.setUsesStreamPrerollFlags()
    override fun seekToUs(positionUs: Long): Long = inner.seekToUs(positionUs)
    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        inner.getAdjustedSeekPositionUs(positionUs, seekParameters)
    override fun getBufferedPositionUs(): Long = inner.bufferedPositionUs
    override fun getNextLoadPositionUs(): Long = inner.nextLoadPositionUs
    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = inner.continueLoading(loadingInfo)
    override fun isLoading(): Boolean = inner.isLoading
    override fun reevaluateBuffer(positionUs: Long) = inner.reevaluateBuffer(positionUs)
    override fun setEndPositionUs(endPositionUs: Long): Long = inner.setEndPositionUs(endPositionUs)
}
