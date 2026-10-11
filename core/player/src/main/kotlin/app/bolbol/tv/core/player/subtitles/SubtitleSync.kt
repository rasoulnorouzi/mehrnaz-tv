package app.bolbol.tv.core.player.subtitles

import app.bolbol.tv.core.model.SubtitleStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keeps [lines] in step with the playhead (docs/subtitles.md §Engine 3).
 *
 * A [tickMs] ticker runs only while playing (and a track is loaded and subtitles are enabled);
 * seeks, pauses, offset and track changes call [update] directly. Each update is one binary
 * search at `position - offsetMs`. [lines] is a StateFlow, so it emits only when the visible
 * text changes: Compose recomposes a few times per sentence, not 10 times a second.
 *
 * Main-thread only (it reads ExoPlayer's position through [positionMs]); [scope] runs the ticker.
 */
class SubtitleSync(
    private val scope: CoroutineScope,
    private val positionMs: () -> Long,
    private val tickMs: Long = TICK_MS,
) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** The lines on screen now; empty when there is no track, subtitles are off, or between cues. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** The loaded track; null while loading, for dubbed streams, or after a failure. */
    var track: SubtitleTrack? = null
        set(value) {
            field = value
            refresh()
        }

    /** Positive = subtitles appear later (the cue for media time t shows at t + offset). */
    var offsetMs: Int = 0
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }

    var enabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }

    private var playing = false
    private var released = false
    private var ticker: Job? = null

    /** Player.Listener.onIsPlayingChanged. */
    fun setPlaying(playing: Boolean) {
        if (this.playing == playing) return
        this.playing = playing
        refresh()
    }

    /** Recomputes the visible lines now (after a seek, a pause, a discontinuity…). */
    fun update() {
        if (released) return
        val t = track
        _lines.value = if (t == null || !enabled) emptyList() else t.linesAt(positionMs() - offsetMs)
    }

    fun release() {
        released = true
        ticker?.cancel()
        ticker = null
        track = null
        _lines.value = emptyList()
    }

    private fun refresh() {
        if (released) return
        update()
        val shouldTick = playing && enabled && track != null
        if (shouldTick && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive) {
                    delay(tickMs)
                    update()
                }
            }
        } else if (!shouldTick) {
            ticker?.cancel()
            ticker = null
        }
    }

    companion object {
        /** 100 ms: a cue appears at most ~1/10 s late, invisible to a reader. */
        const val TICK_MS = 100L
    }
}

/**
 * Pure rules for the per-title sync offset (docs/subtitles.md §Settings): ±120 s
 * ([SubtitleStyle.OFFSET_MAX_MS]) on a 0.5 s grid, changed by 0.5 s or 5 s steps.
 */
object SubtitleOffset {
    /** «زودتر/دیرتر ۰٫۵ ثانیه». */
    const val SMALL_STEP_MS = SubtitleStyle.OFFSET_STEP_MS

    /** «زودتر/دیرتر ۵ ثانیه». */
    const val LARGE_STEP_MS = 5_000

    /** Clamped to ±[SubtitleStyle.OFFSET_MAX_MS] and rounded (halves away from zero) to [SubtitleStyle.OFFSET_STEP_MS]. */
    fun normalize(offsetMs: Int): Int {
        val step = SubtitleStyle.OFFSET_STEP_MS
        val clamped = offsetMs.coerceIn(-SubtitleStyle.OFFSET_MAX_MS, SubtitleStyle.OFFSET_MAX_MS)
        val steps = (abs(clamped).toDouble() / step).roundToInt()
        return (if (clamped < 0) -steps else steps) * step
    }

    /** One step: [deltaMs] = ±[SMALL_STEP_MS] or ±[LARGE_STEP_MS] (negative = earlier). Stops at the limits. */
    fun shift(current: Int, deltaMs: Int): Int =
        normalize((current.toLong() + deltaMs).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
}

/**
 * Hold-to-repeat timing for the sync buttons (pure, unit-tested): the press steps at once, the
 * first repeat comes after [FIRST_REPEAT_MS], then one every [REPEAT_INTERVAL_MS]. Holding a 5 s
 * button reaches +50 s in ~1.4 s; a 0.5 s button covers 10 s in ~2.7 s.
 */
object HoldRepeat {
    const val FIRST_REPEAT_MS = 400L
    const val REPEAT_INTERVAL_MS = 120L

    /** Safety stop if the key-up is never seen (more than enough to cross the whole ±120 s range). */
    const val MAX_STEPS = 600

    /** Wait before step [index]: 0 = the press itself (immediately), 1 = the first repeat, then the interval. */
    fun delayBefore(index: Int): Long = when {
        index <= 0 -> 0L
        index == 1 -> FIRST_REPEAT_MS
        else -> REPEAT_INTERVAL_MS
    }

    /** Steps applied after holding OK for [heldMs] (the press itself counts as one). */
    fun stepsAfter(heldMs: Long): Int = when {
        heldMs < FIRST_REPEAT_MS -> 1
        else -> minOf(MAX_STEPS, 2 + ((heldMs - FIRST_REPEAT_MS) / REPEAT_INTERVAL_MS).toInt())
    }
}
