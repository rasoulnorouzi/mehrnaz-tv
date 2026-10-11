package app.bolbol.tv.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A stored preference shown with instant feedback. [state] follows [source] (the repository flow),
 * but [update] changes it immediately and queues the write, so a live preview does not wait for the
 * database round trip.
 *
 * - Writes are saved one at a time, in order (rapid D-pad presses cannot reorder them).
 * - While writes are in flight the repository's older emissions are ignored, so the value does not
 *   flicker back; once the queue is empty the next emission is taken (external changes show up).
 *
 * Use from one thread only (the main thread, via viewModelScope).
 */
internal class OptimisticValue<T>(
    scope: CoroutineScope,
    initial: T,
    source: Flow<T>,
    private val save: suspend (T) -> Unit,
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<T> = _state.asStateFlow()

    private var pendingWrites = 0
    private val queue = Channel<T>(Channel.UNLIMITED)

    init {
        scope.launch {
            source.collect { if (pendingWrites == 0) _state.value = it }
        }
        scope.launch {
            for (value in queue) {
                try {
                    save(value)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep the shown value; the next repository emission corrects it if the write was lost.
                } finally {
                    pendingWrites--
                }
            }
        }
    }

    /** Applies [transform] to the current value, shows it now, and saves it in the background. */
    fun update(transform: (T) -> T) {
        val current = _state.value
        val next = transform(current)
        if (next == current) return
        _state.value = next
        pendingWrites++
        queue.trySend(next)
    }
}
