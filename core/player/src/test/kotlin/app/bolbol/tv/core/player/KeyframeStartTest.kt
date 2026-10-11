package app.bolbol.tv.core.player

import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class KeyframeStartTest {

    private val maxBack = 15_000_000L

    @Test fun snapsBackToTheKeyframe() {
        assertEquals(598_400_000, KeyframeSnap.choose(600_000_000, 598_400_000, maxBack))
        assertEquals(590_000_000, KeyframeSnap.choose(600_000_000, 590_000_000, maxBack)) // 10 s GOP
        assertEquals(600_000_000, KeyframeSnap.choose(600_000_000, 600_000_000, maxBack)) // already on one
    }

    @Test fun ignoresImplausibleKeyframes() {
        assertEquals(600_000_000, KeyframeSnap.choose(600_000_000, 0, maxBack)) // unseekable file → 0
        assertEquals(600_000_000, KeyframeSnap.choose(600_000_000, 584_000_000, maxBack)) // > 15 s back
        assertEquals(600_000_000, KeyframeSnap.choose(600_000_000, 601_000_000, maxBack)) // after the position
        assertEquals(0, KeyframeSnap.choose(0, 0, maxBack))
    }

    @Test fun onlyTheFirstTrackSelectionIsSnapped() {
        val inner = FakePeriod(keyframeBeforeUs = { it - 1_600_000 })
        val period = KeyframeStartMediaPeriod(inner, maxBack)
        val returned = period.selectTracks(arrayOf(), booleanArrayOf(), arrayOf(), booleanArrayOf(), 600_000_000)
        assertEquals(598_400_000, returned)
        assertEquals(listOf(598_400_000L), inner.selectedAtUs)
        assertEquals(SeekParameters.PREVIOUS_SYNC, inner.askedWith)
        // A later re-selection (e.g. a track change) keeps its position.
        period.selectTracks(arrayOf(), booleanArrayOf(), arrayOf(), booleanArrayOf(), 700_000_000)
        assertEquals(listOf(598_400_000L, 700_000_000L), inner.selectedAtUs)
    }

    @Test fun seeksAndCallbacksPassThroughWithTheWrapperAsSource() {
        val inner = FakePeriod(keyframeBeforeUs = { it })
        val period = KeyframeStartMediaPeriod(inner, maxBack)
        val prepared = ArrayList<MediaPeriod>()
        period.prepare(object : MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: MediaPeriod) { prepared += mediaPeriod }
            override fun onContinueLoadingRequested(source: MediaPeriod) = Unit
        }, 0)
        inner.callback!!.onPrepared(inner)
        assertSame(period, prepared.single())
        assertEquals(42_000_000, period.seekToUs(42_000_000))
        assertEquals(42_000_000L, inner.seekedToUs)
    }

    private class FakePeriod(private val keyframeBeforeUs: (Long) -> Long) : MediaPeriod {
        var callback: MediaPeriod.Callback? = null
        var askedWith: SeekParameters? = null
        val selectedAtUs = ArrayList<Long>()
        var seekedToUs = -1L

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) { this.callback = callback }
        override fun maybeThrowPrepareError() = Unit
        override fun getTrackGroups(): TrackGroupArray = TrackGroupArray.EMPTY
        override fun selectTracks(
            selections: Array<ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long {
            selectedAtUs += positionUs
            return positionUs
        }
        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit
        override fun readDiscontinuity(): Long = androidx.media3.common.C.TIME_UNSET
        override fun seekToUs(positionUs: Long): Long = positionUs.also { seekedToUs = it }
        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
            askedWith = seekParameters
            return keyframeBeforeUs(positionUs)
        }
        override fun getBufferedPositionUs(): Long = 0
        override fun getNextLoadPositionUs(): Long = 0
        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false
        override fun isLoading(): Boolean = false
        override fun reevaluateBuffer(positionUs: Long) = Unit
    }
}
