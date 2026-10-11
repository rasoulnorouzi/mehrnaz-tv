package app.bolbol.tv.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** The remote keys that matter to [LongPressTracker]. */
internal enum class RemoteKey { SELECT, MENU, OTHER }

/** How an action was asked for: OK held down, or the MENU key. */
internal enum class ActionTrigger { LONG_PRESS, MENU }

/** What to do with one key event: let it through (`consumed = false`) and/or fire [trigger]. */
internal data class KeyOutcome(val consumed: Boolean, val trigger: ActionTrigger? = null) {
    companion object {
        val Pass = KeyOutcome(consumed = false)
        val Swallow = KeyOutcome(consumed = true)
    }
}

/**
 * Turns the remote's key stream into "long press OK" and "MENU" actions.
 *
 * A held OK key arrives as one DOWN with `repeatCount == 0` and then auto-repeats (the first repeat
 * after ~500 ms, the system's key-repeat timeout). The plain press passes through untouched, so a
 * short click still reaches the card's own `onClick`; the first repeat fires the action, and the
 * rest of the hold plus the final UP are swallowed so releasing the key does not also click.
 * MENU fires on its DOWN. Pure logic, so it is unit-tested without Compose.
 */
internal class LongPressTracker {
    private var fired = false

    fun onKey(key: RemoteKey, down: Boolean, repeatCount: Int): KeyOutcome = when (key) {
        RemoteKey.OTHER -> KeyOutcome.Pass
        RemoteKey.MENU ->
            if (down && repeatCount == 0) KeyOutcome(consumed = true, trigger = ActionTrigger.MENU) else KeyOutcome.Swallow
        RemoteKey.SELECT -> when {
            down && repeatCount == 0 -> {
                fired = false
                KeyOutcome.Pass
            }
            down && !fired -> {
                fired = true
                KeyOutcome(consumed = true, trigger = ActionTrigger.LONG_PRESS)
            }
            down -> KeyOutcome.Swallow
            fired -> {
                fired = false
                KeyOutcome.Swallow
            }
            else -> KeyOutcome.Pass
        }
    }
}

internal fun KeyEvent.remoteKey(): RemoteKey = when (key) {
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> RemoteKey.SELECT
    Key.Menu -> RemoteKey.MENU
    else -> RemoteKey.OTHER
}

internal val KeyEvent.isDown: Boolean get() = type == KeyEventType.KeyDown

/**
 * Calls [onTrigger] when OK is held (> ~500 ms) or MENU is pressed while this element or a child
 * has focus. Works as a wrapper around cards that only expose `onClick`.
 */
@Composable
internal fun Modifier.longPressOrMenu(onTrigger: (ActionTrigger) -> Unit): Modifier {
    val tracker = remember { LongPressTracker() }
    val current by rememberUpdatedState(onTrigger)
    return onPreviewKeyEvent { event ->
        val key = event.remoteKey()
        if (key == RemoteKey.OTHER) return@onPreviewKeyEvent false
        val outcome = tracker.onKey(key, event.isDown, event.nativeKeyEvent.repeatCount)
        outcome.trigger?.let { current(it) }
        outcome.consumed
    }
}
