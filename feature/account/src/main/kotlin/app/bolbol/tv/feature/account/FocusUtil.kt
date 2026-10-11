package app.bolbol.tv.feature.account

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Requests focus once the target is attached: right after a screen (or a lazy grid item) first composes the target may
 * not be laid out yet (requestFocus then throws), so retry over the next few frames.
 */
internal suspend fun FocusRequester.requestFocusWhenAttached() {
    repeat(FOCUS_ATTEMPTS) {
        try {
            requestFocus()
            return
        } catch (_: IllegalStateException) {
            withFrameNanos { }
        }
    }
}

private const val FOCUS_ATTEMPTS = 5
