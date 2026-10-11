package app.bolbol.tv.feature.settings

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.SubtitleBackground
import app.bolbol.tv.core.model.SubtitleColor
import app.bolbol.tv.core.model.SubtitlePosition
import app.bolbol.tv.core.model.SubtitleStyle

/** One user edit of the subtitle look (docs/subtitles.md §Settings). */
internal sealed interface SubtitleChange {
    data class Enabled(val value: Boolean) : SubtitleChange

    /** Index into [SubtitleStyle.SIZE_STEPS]. */
    data class SizeStep(val index: Int) : SubtitleChange
    data class Color(val value: SubtitleColor) : SubtitleChange
    data class Background(val value: SubtitleBackground) : SubtitleChange
    data class Bold(val value: Boolean) : SubtitleChange
    data class Position(val value: SubtitlePosition) : SubtitleChange
}

/** The style after [change]; an out-of-range size index leaves it unchanged. */
internal fun SubtitleStyle.reduce(change: SubtitleChange): SubtitleStyle = when (change) {
    is SubtitleChange.Enabled -> copy(enabled = change.value)
    is SubtitleChange.SizeStep ->
        SubtitleStyle.SIZE_STEPS.getOrNull(change.index)?.let { copy(sizeScale = it) } ?: this
    is SubtitleChange.Color -> copy(color = change.value)
    is SubtitleChange.Background -> copy(background = change.value)
    is SubtitleChange.Bold -> copy(bold = change.value)
    is SubtitleChange.Position -> copy(position = change.value)
}

/** Index of the size step closest to [SubtitleStyle.sizeScale] (a stored value may be off-grid). */
internal fun SubtitleStyle.sizeStepIndex(): Int =
    SubtitleStyle.SIZE_STEPS.indices.minBy { kotlin.math.abs(SubtitleStyle.SIZE_STEPS[it] - sizeScale) }

/**
 * One row of chips in a settings page: Persian [label], chip texts, which chip [selectedIndex] is
 * selected in a value of type [S], and the [change] that choosing a chip produces.
 */
internal class OptionSpec<S, C>(
    val label: String,
    val options: List<String>,
    val selectedIndex: (S) -> Int,
    val change: (Int) -> C,
)

/** The six subtitle option rows, in display order. */
internal val SubtitleOptionSpecs: List<OptionSpec<SubtitleStyle, SubtitleChange>> = listOf(
    OptionSpec(
        label = "زیرنویس",
        options = listOf("روشن", "خاموش"),
        selectedIndex = { if (it.enabled) 0 else 1 },
        change = { SubtitleChange.Enabled(it == 0) },
    ),
    OptionSpec(
        label = "اندازه",
        options = listOf("کوچک", "معمولی", "بزرگ", "خیلی بزرگ"),
        selectedIndex = { it.sizeStepIndex() },
        change = { SubtitleChange.SizeStep(it) },
    ),
    OptionSpec(
        label = "رنگ",
        options = listOf("سفید", "زرد", "فیروزه‌ای", "سبز روشن", "صورتی"),
        selectedIndex = { it.color.ordinal },
        change = { SubtitleChange.Color(SubtitleColor.entries[it]) },
    ),
    OptionSpec(
        label = "پس‌زمینه",
        options = listOf("حاشیه", "کادر نیمه‌شفاف"),
        selectedIndex = { it.background.ordinal },
        change = { SubtitleChange.Background(SubtitleBackground.entries[it]) },
    ),
    OptionSpec(
        label = "ضخامت",
        options = listOf("معمولی", "ضخیم"),
        selectedIndex = { if (it.bold) 1 else 0 },
        change = { SubtitleChange.Bold(it == 1) },
    ),
    OptionSpec(
        label = "جایگاه",
        options = listOf("پایین", "کمی بالاتر", "بالاتر"),
        selectedIndex = { it.position.ordinal },
        change = { SubtitleChange.Position(SubtitlePosition.entries[it]) },
    ),
)

/** One user edit of the default playback choices. */
internal sealed interface PlaybackChange {
    data class Audio(val value: AudioType) : PlaybackChange
    data class Quality(val value: Int) : PlaybackChange
}

/** Prefs after [change]; the other fields (including the deprecated subtitleScale) are kept as they are. */
internal fun PlaybackPrefs.reduce(change: PlaybackChange): PlaybackPrefs = when (change) {
    is PlaybackChange.Audio -> copy(audio = change.value)
    is PlaybackChange.Quality -> copy(quality = change.value)
}

internal val AudioOptions = listOf(AudioType.DUBBED, AudioType.SUBTITLED)
internal val QualityOptions = listOf(1080, 720, 480)

/** The two playback option rows. A null preference selects no chip (index -1). */
internal val PlaybackOptionSpecs: List<OptionSpec<PlaybackPrefs, PlaybackChange>> = listOf(
    OptionSpec(
        label = "صدای پیش‌فرض",
        options = listOf("دوبله", "زیرنویس"),
        selectedIndex = { prefs -> AudioOptions.indexOf(prefs.audio) },
        change = { PlaybackChange.Audio(AudioOptions[it]) },
    ),
    OptionSpec(
        label = "کیفیت پیش‌فرض",
        options = listOf("۱۰۸۰", "۷۲۰", "۴۸۰"),
        selectedIndex = { prefs -> QualityOptions.indexOf(prefs.quality) },
        change = { PlaybackChange.Quality(QualityOptions[it]) },
    ),
)
