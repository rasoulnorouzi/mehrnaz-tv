package app.bolbol.tv.core.designsystem

/*
 * Number and time formatting for UI text.
 *
 * Digit policy (decided once, here):
 *  - UI chrome that is counted or timed uses Persian numerals: durations, remaining time,
 *    episode / season numbers, counters.  -> formatDurationFa, formatRemainingFa, formatEpisodeFa,
 *    formatRuntimeFa, Int.toPersianDigits().
 *  - Values that read as data stay Latin: release years ("2025") and IMDb ratings ("8.1").
 *    -> formatImdb(); pass year.toString() as-is.
 *
 * Nothing here uses String.format / Locale, so the output does not depend on the TV's system
 * language (on an Arabic/Persian locale String.format would already emit localised digits).
 * This file must stay free of Android classes so it can be unit-tested on the JVM.
 */

private const val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

/** Converts ASCII digits to Persian digits for UI chrome (times, counters, episode numbers). */
fun String.toPersianDigits(): String = buildString(length) {
    for (c in this@toPersianDigits) append(if (c in '0'..'9') PERSIAN_DIGITS[c - '0'] else c)
}

fun Int.toPersianDigits(): String = toString().toPersianDigits()

/**
 * Clock-style duration with Persian numerals: "۱:۲۳:۴۵" (1 h 23 m 45 s) or "۴۷:۱۲" (47 m 12 s).
 * Hours are shown only when non-zero; the first unit is not zero-padded. Negative input = 0.
 */
fun formatDurationFa(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val ss = seconds.toString().padStart(2, '0')
    val raw = if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:$ss"
    } else {
        "$minutes:$ss"
    }
    return raw.toPersianDigits()
}

/**
 * Time left, rounded **up** to whole minutes: "۲۴ دقیقه مانده", "۱ ساعت و ۵ دقیقه مانده",
 * "۲ ساعت مانده". Anything under a minute reads "۱ دقیقه مانده"; zero or less is "تمام شد".
 */
fun formatRemainingFa(ms: Long): String {
    if (ms <= 0L) return "تمام شد"
    val totalMinutes = ((ms + 59_999L) / 60_000L).coerceAtLeast(1L)
    return "${formatMinutesFa(totalMinutes)} مانده"
}

/** Runtime for meta lines: "۴۵ دقیقه", "۱ ساعت و ۴۲ دقیقه", "۲ ساعت". */
fun formatRuntimeFa(minutes: Int): String = formatMinutesFa(minutes.coerceAtLeast(0).toLong())

private fun formatMinutesFa(totalMinutes: Long): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0L -> "$m دقیقه"
        m == 0L -> "$h ساعت"
        else -> "$h ساعت و $m دقیقه"
    }.toPersianDigits()
}

/** "فصل ۱ · قسمت ۳", or just "قسمت ۳" when [includeSeason] is false. */
fun formatEpisodeFa(season: Int, episode: Int, includeSeason: Boolean = true): String =
    if (includeSeason) "فصل ${season.toPersianDigits()} · قسمت ${episode.toPersianDigits()}"
    else "قسمت ${episode.toPersianDigits()}"

/** IMDb rating with one decimal, **Latin** digits and a dot: 8.1f -> "8.1", 7f -> "7.0". */
fun formatImdb(rating: Float): String {
    val tenths = Math.round(rating * 10f).coerceAtLeast(0)
    return "${tenths / 10}.${tenths % 10}"
}
