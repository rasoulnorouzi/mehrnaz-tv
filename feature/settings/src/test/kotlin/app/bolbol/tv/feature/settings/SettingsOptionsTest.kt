package app.bolbol.tv.feature.settings

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.SubtitleBackground
import app.bolbol.tv.core.model.SubtitleColor
import app.bolbol.tv.core.model.SubtitlePosition
import app.bolbol.tv.core.model.SubtitleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsOptionsTest {
    private val base = SubtitleStyle()

    @Test
    fun enabledToggles() {
        assertEquals(base.copy(enabled = false), base.reduce(SubtitleChange.Enabled(false)))
        assertEquals(base, base.copy(enabled = false).reduce(SubtitleChange.Enabled(true)))
    }

    @Test
    fun everySizeStepSetsItsScale() {
        SubtitleStyle.SIZE_STEPS.forEachIndexed { i, scale ->
            assertEquals(base.copy(sizeScale = scale), base.reduce(SubtitleChange.SizeStep(i)))
        }
    }

    @Test
    fun outOfRangeSizeStepIsIgnored() {
        assertSame(base, base.reduce(SubtitleChange.SizeStep(-1)))
        assertSame(base, base.reduce(SubtitleChange.SizeStep(SubtitleStyle.SIZE_STEPS.size)))
    }

    @Test
    fun colorBackgroundBoldPosition() {
        assertEquals(base.copy(color = SubtitleColor.YELLOW), base.reduce(SubtitleChange.Color(SubtitleColor.YELLOW)))
        assertEquals(
            base.copy(background = SubtitleBackground.BOX),
            base.reduce(SubtitleChange.Background(SubtitleBackground.BOX)),
        )
        assertEquals(base.copy(bold = false), base.reduce(SubtitleChange.Bold(false)))
        SubtitlePosition.entries.forEach {
            assertEquals(base.copy(position = it), base.reduce(SubtitleChange.Position(it)))
        }
    }

    @Test
    fun aChangeTouchesOnlyItsOwnField() {
        val custom = SubtitleStyle(
            enabled = false, sizeScale = 1.5f, color = SubtitleColor.YELLOW,
            background = SubtitleBackground.BOX, bold = false, position = SubtitlePosition.HIGH,
        )
        assertEquals(custom.copy(sizeScale = 0.8f), custom.reduce(SubtitleChange.SizeStep(0)))
        assertEquals(custom.copy(color = SubtitleColor.WHITE), custom.reduce(SubtitleChange.Color(SubtitleColor.WHITE)))
        assertEquals(custom.copy(bold = true), custom.reduce(SubtitleChange.Bold(true)))
    }

    @Test
    fun sizeStepIndexPicksTheClosestStep() {
        assertEquals(1, base.sizeStepIndex())
        assertEquals(0, base.copy(sizeScale = 0.7f).sizeStepIndex())
        assertEquals(2, base.copy(sizeScale = 1.2f).sizeStepIndex())
        assertEquals(3, base.copy(sizeScale = 2f).sizeStepIndex())
    }

    @Test
    fun specsMatchTheDocumentedOptions() {
        assertEquals(
            listOf("زیرنویس", "اندازه", "رنگ", "پس‌زمینه", "ضخامت", "جایگاه"),
            SubtitleOptionSpecs.map { it.label },
        )
        assertEquals(listOf("روشن", "خاموش"), SubtitleOptionSpecs[0].options)
        assertEquals(listOf("کوچک", "معمولی", "بزرگ", "خیلی بزرگ"), SubtitleOptionSpecs[1].options)
        assertEquals(listOf("سفید", "زرد"), SubtitleOptionSpecs[2].options)
        assertEquals(listOf("حاشیه", "کادر نیمه‌شفاف"), SubtitleOptionSpecs[3].options)
        assertEquals(listOf("معمولی", "ضخیم"), SubtitleOptionSpecs[4].options)
        assertEquals(listOf("پایین", "کمی بالاتر", "بالاتر"), SubtitleOptionSpecs[5].options)
    }

    @Test
    fun defaultStyleSelectsTheDocumentedDefaults() {
        // docs/subtitles.md: on, normal size, white, outline, bold, lowest position.
        val selected = SubtitleOptionSpecs.map { it.selectedIndex(base) }
        assertEquals(listOf(0, 1, 0, 0, 1, 0), selected)
    }

    @Test
    fun choosingAChipSelectsThatChipAndChangesNothingElse() {
        SubtitleOptionSpecs.forEachIndexed { row, spec ->
            spec.options.indices.forEach { chip ->
                val after = base.reduce(spec.change(chip))
                assertEquals("row $row chip $chip", chip, spec.selectedIndex(after))
                SubtitleOptionSpecs.forEachIndexed { other, otherSpec ->
                    if (other != row) {
                        assertEquals(otherSpec.selectedIndex(base), otherSpec.selectedIndex(after))
                    }
                }
            }
        }
    }

    @Test
    fun playbackChangesKeepTheOtherFields() {
        val prefs = PlaybackPrefs(audio = AudioType.SUBTITLED, quality = 480, subtitleScale = 1.25f)
        assertEquals(prefs.copy(audio = AudioType.DUBBED), prefs.reduce(PlaybackChange.Audio(AudioType.DUBBED)))
        assertEquals(prefs.copy(quality = 1080), prefs.reduce(PlaybackChange.Quality(1080)))
    }

    @Test
    fun playbackSpecs() {
        val (audio, quality) = PlaybackOptionSpecs
        assertEquals(listOf("دوبله", "زیرنویس"), audio.options)
        assertEquals(listOf("۱۰۸۰", "۷۲۰", "۴۸۰"), quality.options)
        // Nothing chosen yet: no chip selected.
        assertEquals(-1, audio.selectedIndex(PlaybackPrefs()))
        assertEquals(-1, quality.selectedIndex(PlaybackPrefs()))

        var prefs = PlaybackPrefs()
        prefs = prefs.reduce(audio.change(1)).reduce(quality.change(2))
        assertEquals(PlaybackPrefs(audio = AudioType.SUBTITLED, quality = 480), prefs)
        assertEquals(1, audio.selectedIndex(prefs))
        assertEquals(2, quality.selectedIndex(prefs))
        assertEquals(1080, prefs.reduce(quality.change(0)).quality)
        assertEquals(AudioType.DUBBED, prefs.reduce(audio.change(0)).audio)
    }

    @Test
    fun unknownStoredQualityMapsToNoChip() {
        assertEquals(-1, PlaybackOptionSpecs[1].selectedIndex(PlaybackPrefs(quality = 360)))
    }
}
