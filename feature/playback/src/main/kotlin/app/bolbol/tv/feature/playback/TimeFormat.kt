package app.bolbol.tv.feature.playback

import app.bolbol.tv.core.designsystem.toPersianDigits

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

/**
 * Subtitle sync offset in seconds, Persian digits and decimal separator: 1500 → "+۱٫۵",
 * -500 → "-۰٫۵", 2000 → "+۲", 0 → "۰". A signed value is wrapped in an LTR isolate
 * (U+2066 … U+2069) so the sign stays on the number's left inside the RTL sentence.
 */
internal fun formatSubtitleOffset(offsetMs: Int): String {
    if (offsetMs == 0) return "۰"
    val abs = kotlin.math.abs(offsetMs)
    val whole = abs / 1000
    val tenths = (abs % 1000) / 100
    val number = (if (tenths == 0) "$whole" else "$whole$PERSIAN_DECIMAL$tenths").toPersianDigits()
    val sign = if (offsetMs > 0) "+" else "-"
    return "$LTR_ISOLATE$sign$number$POP_ISOLATE"
}

private const val PERSIAN_DECIMAL = '٫'
private const val LTR_ISOLATE = '\u2066'
private const val POP_ISOLATE = '\u2069'
