package app.bolbol.tv.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** The playback speeds the «سرعت پخش» chips offer (v1.2-1). Pure, unit-tested. */
object PlaybackSpeeds {
    const val NORMAL = 1f

    /** Slowest first (RTL: the slowest chip sits at the start/right). Media3 keeps the pitch at 1.0 (natural voice). */
    val CHOICES: List<Float> = listOf(0.5f, 0.75f, NORMAL, 1.25f, 1.5f, 2f)

    /** The nearest choice; NaN, infinity, zero or negative values become [NORMAL]. */
    fun normalize(speed: Float): Float {
        if (!speed.isFinite() || speed <= 0f) return NORMAL
        return CHOICES.minByOrNull { abs(it - speed) } ?: NORMAL
    }

    fun isNormal(speed: Float): Boolean = abs(speed - NORMAL) < 0.001f
}

/**
 * Remembers the chosen speed for the rest of the **app session** (v1.2-1): the next episode, auto-next,
 * «قسمت قبل/بعد» and the panel keep it. It lives in memory only: no Room/DB/DataStore, so it is back to
 * «عادی» when the process restarts. One process-wide instance, [Session], is shared by every
 * [PlaybackController] (each episode opens a new player and controller).
 */
class PlaybackSpeedMemory(initial: Float = PlaybackSpeeds.NORMAL) {
    private val _speed = MutableStateFlow(PlaybackSpeeds.normalize(initial))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** Stores the nearest valid speed and returns it. */
    fun set(speed: Float): Float {
        val value = PlaybackSpeeds.normalize(speed)
        _speed.value = value
        return value
    }

    companion object {
        /** The app-session holder (reset only by a process restart). */
        val Session = PlaybackSpeedMemory()
    }
}
