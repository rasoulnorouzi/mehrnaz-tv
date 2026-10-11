package app.bolbol.tv.core.player.subtitles

/**
 * One subtitle cue, ready to draw: tags stripped, entities decoded, bidi-fixed lines
 * (see [SubtitleCues.prepareLine]). Times are media time in ms, [endMs] exclusive.
 */
data class SubtitleCue(val startMs: Long, val endMs: Long, val lines: List<String>)

/**
 * An immutable, start-sorted list of cues with an O(log n) lookup of what is on screen at a
 * given time. Overlapping cues are supported (their lines are stacked in start order).
 */
class SubtitleTrack(cues: List<SubtitleCue>) {

    /** Cues sorted by start time (stable, so equal starts keep file order). */
    val cues: List<SubtitleCue> = if (cues.isSorted()) cues else cues.sortedBy { it.startMs }

    private val starts = LongArray(this.cues.size) { this.cues[it].startMs }

    /** maxEnds[i] = the latest end among cues[0..i]: lets the lookup stop scanning back early. */
    private val maxEnds = LongArray(this.cues.size).also { out ->
        var max = Long.MIN_VALUE
        for (i in this.cues.indices) {
            max = maxOf(max, this.cues[i].endMs)
            out[i] = max
        }
    }

    val size: Int get() = cues.size

    /**
     * Lines on screen at [timeMs]. Returns the cue's own list when exactly one cue is active
     * (no allocation, so the caller can compare by identity/equals cheaply), an empty list when
     * none is, and the concatenation when cues overlap.
     */
    fun linesAt(timeMs: Long): List<String> {
        val last = lastStartingAtOrBefore(timeMs)
        if (last < 0) return emptyList()
        var single: SubtitleCue? = null
        var multiple: ArrayList<SubtitleCue>? = null
        var i = last
        while (i >= 0 && maxEnds[i] > timeMs) {
            val cue = cues[i]
            if (cue.endMs > timeMs) {
                when {
                    single == null -> single = cue
                    multiple == null -> multiple = arrayListOf(single, cue)
                    else -> multiple.add(cue)
                }
            }
            i--
        }
        if (multiple != null) {
            multiple.reverse() // back to start order
            return multiple.flatMap { it.lines }
        }
        return single?.lines ?: emptyList()
    }

    /** Index of the last cue with startMs <= [timeMs], or -1. */
    private fun lastStartingAtOrBefore(timeMs: Long): Int {
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= timeMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    companion object {
        val EMPTY = SubtitleTrack(emptyList())

        private fun List<SubtitleCue>.isSorted(): Boolean {
            for (i in 1 until size) if (this[i].startMs < this[i - 1].startMs) return false
            return true
        }
    }
}
