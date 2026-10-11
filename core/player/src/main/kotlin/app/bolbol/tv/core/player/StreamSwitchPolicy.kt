package app.bolbol.tv.core.player

/**
 * Watchdog for one quality/audio switch (v1.1-2). Pure: the controller feeds it times and the
 * last time a byte of the new stream arrived, and does what [check] returns.
 *
 * - The first frame (or READY) ends the switch: [end].
 * - At [firstFrameTimeoutMs] with no first frame:
 *   - bytes still arriving (within [stallMs]) → keep waiting (slow Wi-Fi is not a failure; a
 *     1080p moov box alone is 2-4.5 MB);
 *   - stalled → re-prepare on a fresh connection, up to [maxReprepares] times, each with a new
 *     [firstFrameTimeoutMs];
 *   - stalled again → give up: the error panel, which offers «کیفیت پایین‌تر».
 * - [hardLimitMs] after the switch it gives up even if bytes trickle in.
 */
class StreamSwitchPolicy(
    private val firstFrameTimeoutMs: Long = PlayerTuning.SWITCH_FIRST_FRAME_TIMEOUT_MS,
    private val stallMs: Long = PlayerTuning.SWITCH_STALL_MS,
    private val hardLimitMs: Long = PlayerTuning.SWITCH_HARD_LIMIT_MS,
    private val maxReprepares: Int = PlayerTuning.SWITCH_MAX_REPREPARES,
) {
    sealed interface Action {
        /** Nothing to do (no switch running). */
        data object None : Action

        /** Check again in [delayMs]. */
        data class Wait(val delayMs: Long) : Action

        /** Re-prepare the new stream on a fresh connection, then check again in [delayMs]. */
        data class Reprepare(val delayMs: Long) : Action

        /** Stop the switch and show the error panel. The switch has ended. */
        data object GiveUp : Action
    }

    var isSwitching: Boolean = false
        private set

    /** Re-prepares done in the current (or last) switch. */
    var reprepares: Int = 0
        private set

    private var startedAtMs = 0L
    private var deadlineMs = 0L

    /** A switch starts now (a newer switch replaces a running one). Returns the delay until the first [check]. */
    fun begin(nowMs: Long): Long {
        isSwitching = true
        reprepares = 0
        startedAtMs = nowMs
        deadlineMs = nowMs + firstFrameTimeoutMs
        return firstFrameTimeoutMs
    }

    /** First frame / ready / error / stop: the switch is over. */
    fun end() {
        isSwitching = false
    }

    /** [lastByteAtMs] = last byte of the new stream, -1 if none yet. */
    fun check(nowMs: Long, lastByteAtMs: Long): Action {
        if (!isSwitching) return Action.None
        if (nowMs < deadlineMs) return Action.Wait(deadlineMs - nowMs) // early (stale) timer
        val hardDeadline = startedAtMs + hardLimitMs
        if (nowMs >= hardDeadline) return giveUp()
        val progressing = lastByteAtMs >= 0 && nowMs - lastByteAtMs < stallMs
        if (progressing) {
            deadlineMs = minOf(nowMs + stallMs, hardDeadline)
            return Action.Wait(deadlineMs - nowMs)
        }
        if (reprepares < maxReprepares) {
            reprepares++
            deadlineMs = minOf(nowMs + firstFrameTimeoutMs, hardDeadline)
            return Action.Reprepare(deadlineMs - nowMs)
        }
        return giveUp()
    }

    private fun giveUp(): Action {
        isSwitching = false
        return Action.GiveUp
    }
}

/**
 * Timeline of one switch, for the `BolbolSwitch` log line the manager reads on the TV.
 * All times are absolute (controller clock); -1 = didn't happen.
 */
data class SwitchTimeline(
    val startedAtMs: Long,
    val from: String,
    val to: String,
    val host: String,
    val requestedPositionMs: Long,
    val preparedAtMs: Long = -1,
    val firstFrameAtMs: Long = -1,
    val readyAtMs: Long = -1,
    /** player.currentPosition at the first frame (shows the keyframe snap). */
    val startPositionMs: Long = -1,
    val loadErrors: Int = 0,
    val reprepares: Int = 0,
    val transfer: TransferSnapshot = TransferSnapshot(),
) {
    /**
     * `switch 720p/SUBTITLED->1080p/SUBTITLED host=dl20.acenteri.ir result=ok firstByte=412ms
     * moov=1830ms rangeByte=2200ms firstFrame=2610ms ready=3900ms snap=-1.6s requests=2 mb=4.9 errors=0 reprepares=0`.
     * Times are from the switch; "-" = didn't happen.
     */
    fun logLine(result: String): String {
        val t = transfer
        val mb = t.bytes / 100_000 / 10.0
        val snap = if (startPositionMs >= 0) "%+.1fs".format(java.util.Locale.ROOT, (startPositionMs - requestedPositionMs) / 1000.0) else "-"
        return "switch $from->$to host=$host result=$result" +
            " firstByte=${since(t.firstResponseAtMs)} moov=${since(preparedAtMs)} rangeByte=${since(rangeResponseAtMs())}" +
            " firstFrame=${since(firstFrameAtMs)} ready=${since(readyAtMs)} snap=$snap" +
            " requests=${t.requests} mb=$mb errors=$loadErrors reprepares=$reprepares"
    }

    /** The response to the range request at the start position (the request after the moov). */
    private fun rangeResponseAtMs(): Long =
        if (transfer.requests >= 2 && transfer.lastResponseAtMs > transfer.firstResponseAtMs) transfer.lastResponseAtMs else -1

    private fun since(atMs: Long): String = if (atMs >= 0) "${atMs - startedAtMs}ms" else "-"
}
