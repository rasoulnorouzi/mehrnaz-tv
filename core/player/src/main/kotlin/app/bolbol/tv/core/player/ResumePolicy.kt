package app.bolbol.tv.core.player

import app.bolbol.tv.core.model.PlaybackProgress

/** What the player screen does before the first frame. */
sealed interface ResumeDecision {
    /** Play from 0 (nothing saved, almost nothing watched, or already finished). */
    data object StartFromBeginning : ResumeDecision

    /** Show "ادامه از …" / "از ابتدا"; [positionMs] is the saved position. */
    data class AskUser(val positionMs: Long) : ResumeDecision

    /** Start at [positionMs] without asking (the caller asked for an explicit position). */
    data class ResumeAt(val positionMs: Long) : ResumeDecision
}

/** Pure resume rules (unit-tested in ResumePolicyTest). */
object ResumePolicy {

    /**
     * @param saved progress stored for this WatchKey, or null.
     * @param requestedStartMs PlaybackRequest.startPositionMs; non-null always wins
     *   (e.g. the details screen's "ادامه از ۴۷:۱۲" button already asked the user).
     */
    fun decide(
        saved: PlaybackProgress?,
        requestedStartMs: Long?,
        minPositionMs: Long = PlayerTuning.RESUME_MIN_POSITION_MS,
        minRemainingMs: Long = PlayerTuning.RESUME_MIN_REMAINING_MS,
    ): ResumeDecision {
        if (requestedStartMs != null) {
            return if (requestedStartMs > 0) ResumeDecision.ResumeAt(requestedStartMs) else ResumeDecision.StartFromBeginning
        }
        if (saved == null || saved.completed || saved.durationMs <= 0) return ResumeDecision.StartFromBeginning
        return if (saved.positionMs >= minPositionMs && saved.remainingMs >= minRemainingMs) {
            ResumeDecision.AskUser(saved.positionMs)
        } else {
            ResumeDecision.StartFromBeginning
        }
    }
}
