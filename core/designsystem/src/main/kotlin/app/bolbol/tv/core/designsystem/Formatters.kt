package app.bolbol.tv.core.designsystem

import app.bolbol.tv.core.model.PlaybackProgress

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

/** «ادامه از ۱۲:۳۴»: where a title with an unknown length will resume (the saved position, Persian digits). */
fun formatResumeFromFa(positionMs: Long): String =
    if (positionMs < 1_000L) "ادامه" else "ادامه از ${formatDurationFa(positionMs)}"

/**
 * The progress label of a started title (Continue Watching card, episode row):
 *  - [completed]: «تمام شد», and **only** then;
 *  - length unknown ([durationMs] <= 0, e.g. progress synced from the website, which has no reliable duration):
 *    «ادامه از ۱۲:۳۴», the saved position;
 *  - else the time left («۲۴ دقیقه مانده»), at least «۱ دقیقه مانده» for an unfinished title.
 */
fun formatProgressLabelFa(positionMs: Long, durationMs: Long, completed: Boolean = false): String = when {
    completed -> "تمام شد"
    durationMs <= 0L -> formatResumeFromFa(positionMs)
    else -> formatRemainingFa((durationMs - positionMs).coerceAtLeast(1L))
}

/**
 * Second line of a Continue Watching card: «قسمت ۳ · ۲۴ دقیقه مانده» for an episode (the season is shown from season 2
 * on, «فصل ۲ · قسمت ۳ · …»), just the [formatProgressLabelFa] label for a movie.
 */
fun formatContinueSubtitleFa(progress: PlaybackProgress): String {
    val label = formatProgressLabelFa(progress.positionMs, progress.durationMs, progress.completed)
    val key = progress.key
    return if (key.isEpisode) {
        formatEpisodeFa(key.season, key.episode, includeSeason = key.season > 1) + " · " + label
    } else {
        label
    }
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
