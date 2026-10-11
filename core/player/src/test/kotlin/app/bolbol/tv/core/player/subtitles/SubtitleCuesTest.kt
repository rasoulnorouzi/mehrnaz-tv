package app.bolbol.tv.core.player.subtitles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCuesTest {
    /** The real first cue of The.Naked.Gun.2025 ... SoftSub.vtt (two lines, tags stripped). */
    private val creditCue = "..:: ارائه شده توسط وب سايت بلبل جان ::..\n\u200F.::  www.bolboljan.net ::."

    @Test fun detectsTheRealCreditCue() {
        assertTrue(SubtitleCues.isSiteCredit(creditCue))
    }

    @Test fun detectsVariants() {
        assertTrue(SubtitleCues.isSiteCredit("بلبل‌جان"))
        assertTrue(SubtitleCues.isSiteCredit("بلبلجان"))
        assertTrue(SubtitleCues.isSiteCredit("WWW.BolbolJan.NET"))
        assertTrue(SubtitleCues.isSiteCredit("Bolbol Jan"))
        // LEGO ONE PIECE S01E01 00:17:49: the social handles alone, without the site name.
        assertTrue(SubtitleCues.isSiteCredit(".:: telegram: @bollbolljan ::.\n.:: instagram: @bollbolljan_Com ::."))
    }

    @Test fun keepsDialogue() {
        assertFalse(SubtitleCues.isSiteCredit("\u202B- برید، برید، برید!"))
        assertFalse(SubtitleCues.isSiteCredit("بلبل آواز می‌خواند"))
        assertFalse(SubtitleCues.isSiteCredit(null))
        assertFalse(SubtitleCues.isSiteCredit(""))
    }

    // ---- RLM rule -------------------------------------------------------------------------

    @Test fun rtlFirstLinesAreLeftAlone() {
        assertEquals(0, SubtitleCues.rtlMarkInsertionPoints("\u202B- برید، برید!").size)
        assertEquals(0, SubtitleCues.rtlMarkInsertionPoints("۲۰۲۵ سال خوبی بود").size)
        assertEquals(0, SubtitleCues.rtlMarkInsertionPoints("2025 سال خوبی بود").size)
        assertEquals(0, SubtitleCues.rtlMarkInsertionPoints("- سلام").size)
    }

    @Test fun latinFirstPersianLineGetsAnRlm() {
        assertArrayEquals(intArrayOf(0), SubtitleCues.rtlMarkInsertionPoints("OK باشه!"))
        assertEquals("\u200FOK باشه!", SubtitleCues.forceRtl("OK باشه!"))
        assertEquals("\u200F- FBI اینجاست", SubtitleCues.forceRtl("- FBI اینجاست"))
    }

    @Test fun eachLineIsCheckedSeparately() {
        val text = "- سلام\n- FBI اینجاست!"
        assertArrayEquals(intArrayOf(7), SubtitleCues.rtlMarkInsertionPoints(text))
        assertEquals("- سلام\n\u200F- FBI اینجاست!", SubtitleCues.forceRtl(text))
    }

    @Test fun pureLatinLinesAreLeftAlone() {
        assertEquals(0, SubtitleCues.rtlMarkInsertionPoints("FBI\nNew York, 1985").size)
    }

    @Test fun rleLinesAreKeptAsIs() {
        // First strong char is Latin, but the author already forced RTL with U+202B.
        val line = "\u202B- FBI اینجاست!"
        assertSame(line, SubtitleCues.forceRtl(line))
        assertSame(line, SubtitleCues.prepareLine(line))
    }

    // ---- visual-order repair ----------------------------------------------------------------

    @Test fun leadingSentencePunctuationMovesToTheEnd() {
        assertEquals("راست می‌گفتی ها.", SubtitleCues.repairVisualOrder(".راست می‌گفتی ها"))
        assertEquals("خدایی بازم؟!", SubtitleCues.repairVisualOrder("!خدایی بازم؟"))
        assertEquals("ولی...", SubtitleCues.repairVisualOrder("...ولی"))
        assertEquals("بعد اون صحبت‌ها،", SubtitleCues.repairVisualOrder("،بعد اون صحبت‌ها"))
        assertEquals("چی?!", SubtitleCues.repairVisualOrder("?! چی"))
    }

    @Test fun trailingSpeakerDashMovesToTheFront() {
        assertEquals("- چه خبره؟", SubtitleCues.repairVisualOrder("چه خبره؟ -"))
        assertEquals("- عه", SubtitleCues.repairVisualOrder("عه -"))
        assertEquals("- وای!", SubtitleCues.repairVisualOrder("!وای -"))
    }

    @Test fun logicalOrderLinesAreUntouched() {
        for (line in listOf(
            "- برید، برید!",
            "خب، بث، به سلامتی خودمون",
            "« اسلحه‌ی برهنه »",
            "حالت خوبـه؟",
            "- سلام -", // starts with a dash already
            "ولی --",
            "\u202B.اول نقطه", // explicit RLE: the author's choice
            "...and then", // no Persian
            "",
        )) {
            assertSame(line, SubtitleCues.repairVisualOrder(line))
        }
    }

    @Test fun prepareLineRepairsThenForcesRtl() {
        assertEquals("\u200FFBI اینجاست.", SubtitleCues.prepareLine(".FBI اینجاست"))
        assertEquals("- عه", SubtitleCues.prepareLine("عه -"))
    }
}
