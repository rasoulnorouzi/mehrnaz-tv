package app.bolbol.tv.core.player

import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.WatchKey

/** Why a progress save is being attempted. Everything except [TICK] bypasses the throttle. */
enum class SaveReason(val forced: Boolean) {
    TICK(false),
    PAUSE(true),
    STOP(true),
    ERROR(true),
    ENDED(true),
    SWITCH(true),
    TRIM_MEMORY(true),
    RELEASE(true),
}

/**
 * Pure throttle + rules for writing watch progress (unit-tested, no Android types).
 *
 * - Periodic saves at most every [intervalMs]; forced saves (pause, stop, error, …) always pass.
 * - Nothing is saved before the duration is known or while the position is below [minPositionMs].
 * - `completed` = position >= [completedFraction] of the duration, or playback ended.
 * - A save identical to the previous one (same position + completed flag) is skipped, so
 *   pause → stop → release doesn't write three times, and opening then leaving without
 *   playing doesn't bump the item in Continue Watching.
 */
class ProgressSavePolicy(
    private val intervalMs: Long = PlayerTuning.PROGRESS_SAVE_INTERVAL_MS,
    private val minPositionMs: Long = PlayerTuning.MIN_SAVE_POSITION_MS,
    private val completedFraction: Double = PlayerTuning.COMPLETED_FRACTION,
) {
    private var lastSaveElapsedMs: Long? = null
    private var lastPositionMs: Long? = null
    private var lastCompleted = false

    /**
     * Call when a new item/stream starts. [baselinePositionMs] is where playback starts
     * (usually the saved position); saving exactly that position again is skipped.
     */
    fun reset(nowElapsedMs: Long, baselinePositionMs: Long?) {
        lastSaveElapsedMs = nowElapsedMs
        lastPositionMs = baselinePositionMs
        lastCompleted = false
    }

    /**
     * Returns the progress to persist now, or null when nothing should be written.
     * [durationMs] <= 0 (incl. C.TIME_UNSET) means "unknown".
     */
    fun evaluate(
        key: WatchKey,
        positionMs: Long,
        durationMs: Long,
        ended: Boolean,
        reason: SaveReason,
        nowElapsedMs: Long,
        nowWallMs: Long,
    ): PlaybackProgress? {
        val progress = buildProgress(key, positionMs, durationMs, ended, nowWallMs, minPositionMs, completedFraction)
            ?: return null
        if (!reason.forced) {
            val last = lastSaveElapsedMs
            if (last != null && nowElapsedMs - last < intervalMs) return null
        }
        if (progress.positionMs == lastPositionMs && progress.completed == lastCompleted) return null
        lastSaveElapsedMs = nowElapsedMs
        lastPositionMs = progress.positionMs
        lastCompleted = progress.completed
        return progress
    }

    companion object {
        /** Pure mapping from player numbers to a [PlaybackProgress], or null if not saveable. */
        fun buildProgress(
            key: WatchKey,
            positionMs: Long,
            durationMs: Long,
            ended: Boolean,
            nowWallMs: Long,
            minPositionMs: Long = PlayerTuning.MIN_SAVE_POSITION_MS,
            completedFraction: Double = PlayerTuning.COMPLETED_FRACTION,
        ): PlaybackProgress? {
            if (durationMs <= 0) return null
            val position = if (ended) durationMs else positionMs.coerceIn(0, durationMs)
            if (position < minPositionMs) return null
            val completed = ended || position >= completedFraction * durationMs
            return PlaybackProgress(key, position, durationMs, nowWallMs, completed)
        }
    }
}
