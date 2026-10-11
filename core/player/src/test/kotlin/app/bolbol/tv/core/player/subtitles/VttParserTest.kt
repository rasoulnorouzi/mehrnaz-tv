package app.bolbol.tv.core.player.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VttParserTest {

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)) { "missing test resource $name" }.readText()

    private val nakedGun by lazy { VttParser.parse(resource("naked_gun_2025.vtt")) }
    private val lego by lazy { VttParser.parse(resource("lego_one_piece_s01e01.vtt")) }

    private fun SubtitleTrack.allText() = cues.flatMap { it.lines }

    // ---- real files (research: The.Naked.Gun.2025 480p SoftSub, LEGO.ONE.PIECE.S01E01) ----------

    @Test fun nakedGunCueCount() {
        // 1331 timed blocks in the file, 3 of them site credits.
        assertEquals(1328, nakedGun.size)
        assertTrue(nakedGun.size > 100)
    }

    @Test fun legoCueCount() {
        // 484 timed blocks, 6 credits (intro, 3 × Telegram/Instagram, 2 × bare "بلبل جان").
        assertEquals(478, lego.size)
    }

    @Test fun creditCuesAreDropped() {
        for (track in listOf(nakedGun, lego)) {
            assertTrue(track.cues.none { cue -> SubtitleCues.isSiteCredit(cue.lines.joinToString("\n")) })
            assertTrue(track.allText().none { it.contains("bolboljan", ignoreCase = true) || it.contains("bollbolljan") })
        }
        // The 00:00:01 → 00:00:16 credit is gone: the first cue is real dialogue.
        assertEquals(96_462L, nakedGun.cues.first().startMs)
        assertEquals(18_875L, lego.cues.first().startMs)
    }

    @Test fun dialogueMentioningBolbolIsKept() {
        // "برامون عین بلبل چهچه می‌زنه" (a nightingale, not the site) must survive the credit filter.
        assertTrue(nakedGun.allText().any { it.contains("عین بلبل چهچه") })
    }

    @Test fun firstRealCueKeepsItsRleLines() {
        val first = nakedGun.cues.first()
        assertEquals(96_462L, first.startMs)
        assertEquals(99_215L, first.endMs)
        assertEquals(listOf("\u202B- برید، برید، برید!", "\u202B- یالا، یالا!"), first.lines)
    }

    @Test fun rleLinesArePreservedVerbatim() {
        val rleLines = lego.allText().filter { it.startsWith("\u202B") }
        assertEquals(598, rleLines.size) // every RLE line of the file (none is a credit)
        assertTrue(lego.allText().contains("\u202Bوان‌پیس،"))
    }

    @Test fun colorTagsAreStripped() {
        val all = nakedGun.allText() + lego.allText()
        assertTrue(all.none { it.contains('<') || it.contains('>') })
        assertTrue(nakedGun.allText().contains("♪ Starship - Nothing's Gonna Stop Us Now ♪"))
    }

    @Test fun timesAreParsed() {
        val last = nakedGun.cues.last()
        assertEquals(5_108_427L, last.startMs) // 01:25:08.427
        assertEquals(5_110_095L, last.endMs) // 01:25:10.095
        assertEquals(listOf("ای بابا، چه وضعشـه؟"), last.lines)
        assertEquals(34_250L, lego.cues.first { it.startMs == 29_917L }.endMs) // 00:00:29.917 --> 00:00:34.250
        assertTrue(nakedGun.cues.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        assertTrue(nakedGun.cues.all { it.endMs > it.startMs })
    }

    @Test fun visualOrderLinesFromTheFileAreRepaired() {
        val text = nakedGun.allText()
        // ".راست می‌گفتی ها" and "!خدایی بازم؟" were typed for LTR players.
        assertTrue(text.contains("راست می‌گفتی ها."))
        assertTrue(text.contains("خدایی بازم؟!"))
        assertFalse(text.any { it.startsWith(".") || it.startsWith("!") })
        // "عه -" (speaker dash typed last) becomes "- عه".
        assertTrue(text.contains("- عه"))
    }

    // ---- synthetic edge cases ---------------------------------------------------------------

    @Test fun parsesHeaderNotesStylesIdentifiersAndSettings() {
        val vtt = """
            |WEBVTT - some title
            |Kind: captions
            |
            |NOTE a comment
            |that spans lines
            |
            |STYLE
            |::cue { color: yellow }
            |
            |intro-1
            |00:01.000 --> 00:02.500 align:start position:10%
            |<v Frank>سلام</v>
            |
            |2
            |00:00:03.000 --> 00:00:04.000
            |<i>یک</i> &amp; <b>دو</b>&nbsp;&lt;سه&gt;
            |دومین خط
        """.trimMargin()
        val track = VttParser.parse(vtt)
        assertEquals(2, track.size)
        assertEquals(SubtitleCue(1_000, 2_500, listOf("سلام")), track.cues[0])
        assertEquals(listOf("یک & دو\u00A0<سه>", "دومین خط"), track.cues[1].lines)
    }

    @Test fun handlesBomCrlfAndMissingHeader() {
        val crlf = "\uFEFFWEBVTT\r\n\r\n00:00:01.000 --> 00:00:02.000\r\nسلام\r\n"
        assertEquals(listOf("سلام"), VttParser.parse(crlf).cues.single().lines)
        val srtLike = "1\n00:00:01,500 --> 00:00:02,000\nسلام\n"
        assertEquals(1_500L, VttParser.parse(srtLike).cues.single().startMs)
    }

    @Test fun dropsEmptyZeroLengthAndBrokenCues() {
        val vtt = """
            |WEBVTT
            |
            |00:00:05.000 --> 00:00:05.000
            |zero length
            |
            |00:00:06.000 --> 00:00:07.000
            |<c.red></c>
            |
            |00:00:xx.000 --> 00:00:09.000
            |bad time
            |
            |orphan text without timing
            |
            |00:00:10.000 --> 00:00:11.000
            |خوب
        """.trimMargin()
        assertEquals(listOf(SubtitleCue(10_000, 11_000, listOf("خوب"))), VttParser.parse(vtt).cues)
    }

    @Test fun sortsByStartTime() {
        val vtt = "WEBVTT\n\n00:00:09.000 --> 00:00:10.000\nدوم\n\n00:00:01.000 --> 00:00:02.000\nاول\n"
        assertEquals(listOf(1_000L, 9_000L), VttParser.parse(vtt).cues.map { it.startMs })
    }

    @Test fun cueWithoutBlankLineBeforeTheNextTiming() {
        val vtt = "WEBVTT\n00:00:01.000 --> 00:00:02.000\nاول\n00:00:03.000 --> 00:00:04.000\nدوم\n"
        assertEquals(listOf(listOf("اول"), listOf("دوم")), VttParser.parse(vtt).cues.map { it.lines })
    }

    @Test fun decodesBidiAndNumericEntities() {
        assertEquals("\u200Fa\u200Eb", VttParser.cleanLine("&rlm;a&lrm;b"))
        assertEquals("AB", VttParser.cleanLine("&#65;&#x42;"))
        assertEquals("&unknown; & x", VttParser.cleanLine("&unknown; & x"))
        assertEquals("before", VttParser.cleanLine("before<unterminated tag"))
        assertEquals("a b", VttParser.cleanLine("a <00:00:01.000>b")) // inline karaoke timestamp
    }

    @Test fun timestamps() {
        assertEquals(3_723_456L, VttParser.parseTimestamp("01:02:03.456"))
        assertEquals(63_456L, VttParser.parseTimestamp("01:03.456"))
        assertEquals(360_000_000L + 1, VttParser.parseTimestamp("100:00:00.001"))
        assertEquals(1_500L, VttParser.parseTimestamp("00:00:01.5"))
        assertEquals(2_000L, VttParser.parseTimestamp("00:00:02"))
        assertNull(VttParser.parseTimestamp("00:60:00.000"))
        assertNull(VttParser.parseTimestamp("1.000"))
        assertNull(VttParser.parseTimestamp("-0:01.000"))
        assertNull(VttParser.parseTimestamp("00:01.x"))
    }
}
