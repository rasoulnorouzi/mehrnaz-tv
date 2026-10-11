package app.bolbol.tv.core.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCuesTest {
    /** The real first cue of The.Naked.Gun.2025 ... SoftSub.vtt (two lines). */
    private val creditCue = "..:: ارائه شده توسط وب سايت بلبل جان ::..\n\u200F.::  www.bolboljan.net ::."

    @Test fun detectsTheRealCreditCue() {
        assertTrue(SubtitleCues.isSiteCredit(creditCue))
    }

    @Test fun detectsVariants() {
        assertTrue(SubtitleCues.isSiteCredit("بلبل\u200Cجان"))
        assertTrue(SubtitleCues.isSiteCredit("بلبلجان"))
        assertTrue(SubtitleCues.isSiteCredit("WWW.BolbolJan.NET"))
        assertTrue(SubtitleCues.isSiteCredit("Bolbol Jan"))
    }

    @Test fun keepsDialogue() {
        assertFalse(SubtitleCues.isSiteCredit("\u202B- برید، برید، برید!"))
        assertFalse(SubtitleCues.isSiteCredit("بلبل آواز می\u200Cخواند"))
        assertFalse(SubtitleCues.isSiteCredit(null))
        assertFalse(SubtitleCues.isSiteCredit(""))
    }

    @Test fun filterDropsOnlyCredits() {
        val cues = listOf(creditCue, "\u202B- یالا، یالا!")
        assertEquals(listOf("\u202B- یالا، یالا!"), SubtitleCues.withoutCredits(cues) { it })
    }

    @Test fun filterReturnsSameListWhenNothingToDrop() {
        val cues = listOf("- سریع!", "- یالا!")
        assertSame(cues, SubtitleCues.withoutCredits(cues) { it })
    }

    @Test fun rtlFirstLinesAreLeftAlone() {
        assertEquals(0, SubtitleCues.rtlMarkInsertionPoints("\u202B- برید، برید!").size)
        assertEquals(0, SubtitleCues.rtlMarkInsertionPoints("۲۰۲۵ سال خوبی بود").size)
        assertEquals(0, SubtitleCues.rtlMarkInsertionPoints("2025 سال خوبی بود").size)
    }

    @Test fun latinFirstPersianLineGetsAnRlm() {
        assertArrayEquals(intArrayOf(0), SubtitleCues.rtlMarkInsertionPoints("OK باشه!"))
        assertEquals("\u200FOK باشه!", SubtitleCues.forceRtl("OK باشه!"))
    }

    @Test fun eachLineIsCheckedSeparately() {
        val text = "- سلام\n- FBI اینجاست!"
        assertArrayEquals(intArrayOf(7), SubtitleCues.rtlMarkInsertionPoints(text))
        assertEquals("- سلام\n\u200F- FBI اینجاست!", SubtitleCues.forceRtl(text))
    }

    @Test fun pureLatinLinesAreLeftAlone() {
        assertEquals(0, SubtitleCues.rtlMarkInsertionPoints("FBI\nNew York, 1985").size)
    }
}
