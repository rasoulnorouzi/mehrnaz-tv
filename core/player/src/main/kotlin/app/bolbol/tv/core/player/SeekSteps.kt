package app.bolbol.tv.core.player

/** Pure D-pad seek arithmetic (unit-tested). */
object SeekSteps {

    /** Step for a held arrow key: 10 s, then 30 s after ~1 s, then 60 s after ~3 s. */
    fun stepForHold(holdMs: Long): Long = when {
        holdMs >= PlayerTuning.SEEK_LARGE_AFTER_MS -> PlayerTuning.SEEK_STEP_LARGE_MS
        holdMs >= PlayerTuning.SEEK_MEDIUM_AFTER_MS -> PlayerTuning.SEEK_STEP_MEDIUM_MS
        else -> PlayerTuning.SEEK_STEP_MS
    }

    /**
     * Clamps a seek target. Forward seeks stop [END_GUARD_MS] before the end so holding RIGHT
     * can't accidentally finish (and mark as watched) the title. Unknown duration (<= 0) only
     * clamps at 0.
     */
    fun target(currentMs: Long, deltaMs: Long, durationMs: Long): Long {
        val raw = (currentMs + deltaMs).coerceAtLeast(0)
        if (durationMs <= 0) return raw
        val max = (durationMs - END_GUARD_MS).coerceAtLeast(0)
        return if (deltaMs > 0) raw.coerceAtMost(maxOf(max, currentMs)) else raw.coerceAtMost(durationMs)
    }

    const val END_GUARD_MS = 1_000L
}

/**
 * Turns arrow-key events (including auto-repeats) into seek steps.
 *
 * The first press always seeks [PlayerTuning.SEEK_STEP_MS]. Auto-repeats accelerate with the
 * hold time ([SeekSteps.stepForHold]) and are rate-limited to one step per
 * [PlayerTuning.SEEK_REPEAT_MIN_INTERVAL_MS]. Times are KeyEvent timestamps (uptime ms).
 */
class SeekKeyRepeater(
    private val minRepeatIntervalMs: Long = PlayerTuning.SEEK_REPEAT_MIN_INTERVAL_MS,
) {
    private var lastStepAtMs = Long.MIN_VALUE

    /** True while a key is auto-repeating (the caller enables scrubbing mode for this period). */
    var isRepeating: Boolean = false
        private set

    /** Returns the step size (positive ms) to apply for this key-down, or null to swallow it. */
    fun onKeyDown(eventTimeMs: Long, downTimeMs: Long, repeatCount: Int): Long? {
        if (repeatCount == 0) {
            isRepeating = false
            lastStepAtMs = eventTimeMs
            return PlayerTuning.SEEK_STEP_MS
        }
        isRepeating = true
        if (lastStepAtMs != Long.MIN_VALUE && eventTimeMs - lastStepAtMs < minRepeatIntervalMs) return null
        lastStepAtMs = eventTimeMs
        return SeekSteps.stepForHold(eventTimeMs - downTimeMs)
    }

    /** Key released; returns true if it had been repeating (scrubbing must be turned off). */
    fun onKeyUp(): Boolean {
        val wasRepeating = isRepeating
        isRepeating = false
        lastStepAtMs = Long.MIN_VALUE
        return wasRepeating
    }
}
