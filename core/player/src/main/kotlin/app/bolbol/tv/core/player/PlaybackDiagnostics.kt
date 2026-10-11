package app.bolbol.tv.core.player

import android.os.SystemClock

/**
 * Live numbers for the hidden debug overlay and perf tooling (docs/perf-budget.md).
 * All times in ms; -1 / null = not measured yet.
 */
data class PlaybackDiagnostics(
    /** start()/switchStream() → first rendered frame, from the injected clock. */
    val ttffMs: Long = -1,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Current video track bitrate (bit/s) from the container, -1 if unknown. */
    val videoBitrate: Int = -1,
    /** e.g. "OMX.MTK.VIDEO.DECODER.AVC" (hardware) or "c2.android.avc.decoder" (software). */
    val videoDecoder: String? = null,
    /** Media buffered ahead of the playhead. */
    val bufferedAheadMs: Long = 0,
    /** Sum of onDroppedVideoFrames since the controller was created. */
    val droppedFrames: Long = 0,
    /** Rebuffers (not caused by seeks/startup), from PlaybackStats. */
    val rebufferCount: Int = 0,
    /** Mean length of a single rebuffer, from PlaybackStats (-1 = none yet). */
    val meanRebufferMs: Long = -1,
    /** Mean time from a seek until playback is ready again, from PlaybackStats (-1 = none yet). */
    val meanSeekMs: Long = -1,
    /** Mean time from prepare to first ready, from PlaybackStats (-1 = not joined yet). */
    val meanJoinMs: Long = -1,
    /** Latest bandwidth estimate (bit/s), -1 if none. */
    val bandwidthEstimate: Long = -1,
    /** Last quality/audio switch: target and time to its first frame, e.g. "1080p/SUBTITLED 2610 ms (moov 1830)". */
    val lastSwitch: String? = null,
    /** Playback speed (v1.2-1); the overlay shows it only when it isn't 1×. */
    val speed: Float = PlaybackSpeeds.NORMAL,
) {
    /** Short monospace lines for the overlay (Latin digits on purpose: this is data). */
    fun lines(): List<String> = listOf(
        "ttff  ${ms(ttffMs)}",
        "video ${videoWidth}x$videoHeight" + if (videoBitrate > 0) "  ${kbps(videoBitrate.toLong())}" else "",
        "codec ${videoDecoder ?: "-"}",
        "buf   ${bufferedAheadMs / 1000}.${(bufferedAheadMs % 1000) / 100} s",
        "drop  $droppedFrames",
        "rebuf $rebufferCount  mean ${ms(meanRebufferMs)}",
        "seek  mean ${ms(meanSeekMs)}  join ${ms(meanJoinMs)}",
        "bw    " + if (bandwidthEstimate > 0) kbps(bandwidthEstimate) else "-",
    ) + listOfNotNull(
        lastSwitch?.let { "swtch $it" },
        if (PlaybackSpeeds.isNormal(speed)) null else "speed ${speed}x",
    )

    private fun kbps(bits: Long): String = "${bits / 1000} kbps"

    private fun ms(value: Long): String = if (value >= 0) "$value ms" else "-"
}

/** Time source, injectable for tests. */
interface PlaybackClock {
    /** Monotonic time for durations (TTFF, throttling). */
    fun elapsedRealtimeMs(): Long

    /** Wall-clock time stored in PlaybackProgress.updatedAtMs. */
    fun currentTimeMs(): Long

    companion object {
        val System: PlaybackClock = object : PlaybackClock {
            override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
            override fun currentTimeMs(): Long = java.lang.System.currentTimeMillis()
        }
    }
}
