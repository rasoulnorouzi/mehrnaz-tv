package app.bolbol.tv.feature.playback

/**
 * Player clock text with ASCII digits ("47:12", "1:02:03"); the UI converts it with
 * toPersianDigits(). [withHours] forces the H: part so current and total times line up.
 */
internal fun formatPlaybackTime(ms: Long, withHours: Boolean = ms >= 3_600_000L): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (withHours || h > 0) {
        "$h:${m.twoDigits()}:${s.twoDigits()}"
    } else {
        "${m.twoDigits()}:${s.twoDigits()}"
    }
}

private fun Long.twoDigits(): String = if (this < 10) "0$this" else toString()
