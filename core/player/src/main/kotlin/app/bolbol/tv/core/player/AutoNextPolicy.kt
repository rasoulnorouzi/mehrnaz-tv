package app.bolbol.tv.core.player

/** Where the "قسمت بعد" (next episode) card is in its life. */
sealed interface AutoNextState {
    /** Card hidden; not in the last [AutoNextPolicy.WINDOW_MS] yet. */
    data object Waiting : AutoNextState

    /**
     * Card shown with [remainingMs] of the countdown left. [startedAtEnd] = it only appeared after
     * playback ended (the window was skipped, or the user had dismissed it), so reaching the end
     * doesn't fire it immediately.
     */
    data class Counting(val remainingMs: Long, val startedAtEnd: Boolean) : AutoNextState

    /** BACK on the card before the end: hidden while the user watches the credits. */
    data object Dismissed : AutoNextState

    /** BACK on the card after the end: stays hidden (the normal ended controls show). */
    data object DismissedAtEnd : AutoNextState

    /** Leave for the next episode now. Terminal. */
    data object PlayNext : AutoNextState
}

/** One sample of the player for [AutoNextPolicy.step]. */
data class AutoNextInput(
    val positionMs: Long,
    /** 0 while unknown. */
    val durationMs: Long,
    val ended: Boolean,
    /** Player.isPlaying: the countdown runs while the video plays (and after it ended). */
    val playing: Boolean,
    /** Controls, side panel, a dialog or an error cover the card: freeze (never fire) meanwhile. */
    val held: Boolean,
)

/**
 * Pure auto-next rules (M2.2, unit-tested):
 * - The card appears in the last [WINDOW_MS] of an episode (only for episodes longer than
 *   [MIN_DURATION_MS]), or when playback ends if it hasn't appeared before.
 * - It counts down [COUNTDOWN_MS] while the video plays or has ended; paused/buffering/[held] freezes it.
 * - Countdown over, or the video ends while the card was counting → [AutoNextState.PlayNext].
 * - BACK dismisses it ([dismiss]); after a dismissal it comes back once at the end with a fresh countdown.
 * - Seeking back out of the window resets it to [AutoNextState.Waiting].
 */
object AutoNextPolicy {
    const val WINDOW_MS = 30_000L
    const val COUNTDOWN_MS = 10_000L

    /** Shorter videos only get the card at the end (otherwise it would show from the start). */
    const val MIN_DURATION_MS = 2 * WINDOW_MS

    fun inWindow(positionMs: Long, durationMs: Long): Boolean =
        durationMs >= MIN_DURATION_MS && durationMs - positionMs <= WINDOW_MS

    /** Next state after [elapsedMs] of wall time since the previous step. */
    fun step(state: AutoNextState, input: AutoNextInput, elapsedMs: Long): AutoNextState {
        val ended = input.ended
        val window = !ended && inWindow(input.positionMs, input.durationMs)
        return when (state) {
            AutoNextState.PlayNext -> state
            AutoNextState.Waiting -> when {
                ended -> AutoNextState.Counting(COUNTDOWN_MS, startedAtEnd = true)
                window -> AutoNextState.Counting(COUNTDOWN_MS, startedAtEnd = false)
                else -> state
            }
            is AutoNextState.Counting -> when {
                !ended && !window -> AutoNextState.Waiting // seeked back, or restarted after the end
                input.held -> state
                ended && !state.startedAtEnd -> AutoNextState.PlayNext
                else -> {
                    val advancing = input.playing || ended
                    val left = if (advancing) state.remainingMs - elapsedMs.coerceAtLeast(0) else state.remainingMs
                    if (left <= 0) AutoNextState.PlayNext else AutoNextState.Counting(left, state.startedAtEnd && ended)
                }
            }
            AutoNextState.Dismissed -> when {
                ended -> AutoNextState.Counting(COUNTDOWN_MS, startedAtEnd = true)
                !window -> AutoNextState.Waiting
                else -> state
            }
            AutoNextState.DismissedAtEnd -> when {
                ended -> state
                window -> AutoNextState.Dismissed
                else -> AutoNextState.Waiting
            }
        }
    }

    /** BACK on the card. */
    fun dismiss(state: AutoNextState, ended: Boolean): AutoNextState =
        if (state is AutoNextState.Counting) {
            if (ended) AutoNextState.DismissedAtEnd else AutoNextState.Dismissed
        } else {
            state
        }

    /** The card is on screen (when not covered by other chrome). */
    fun cardVisible(state: AutoNextState): Boolean = state is AutoNextState.Counting
}
