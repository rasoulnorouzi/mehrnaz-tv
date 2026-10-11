package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.network.mapper.cleanTitle
import app.bolbol.tv.core.network.mapper.decodeHtmlEntities
import app.bolbol.tv.core.network.mapper.formatSize
import app.bolbol.tv.core.network.mapper.parseAudio
import app.bolbol.tv.core.network.mapper.parseImdb
import app.bolbol.tv.core.network.mapper.parseMetacritic
import app.bolbol.tv.core.network.mapper.parseOrdinal
import app.bolbol.tv.core.network.mapper.parseQuality
import app.bolbol.tv.core.network.mapper.parseRuntimeMinutes
import app.bolbol.tv.core.network.mapper.parseYear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextMapperTest {

    // --- titles -------------------------------------------------------------------------------

    @Test
    fun decodesEntitiesAndStripsTypeLabel() {
        assertEquals("The Devil’s Rain 1975", cleanTitle("فیلم The Devil&#8217;s Rain 1975"))
    }

    @Test
    fun stripsSeriesLabel() {
        assertEquals("LEGO ONE PIECE", cleanTitle("سریال LEGO ONE PIECE"))
    }

    @Test
    fun titleWithoutLabelIsUnchanged() {
        assertEquals("Mechamato Movie 2022", cleanTitle("Mechamato Movie 2022"))
    }

    @Test
    fun stripsEveryKnownLabel() {
        assertEquals("X 2020", cleanTitle("فیلم ترکی X 2020"))
        assertEquals("X 2020", cleanTitle("فیلم هندی X 2020"))
        assertEquals("X 2020", cleanTitle("فیلم X 2020"))
        assertEquals("X", cleanTitle("سریال ترکی X"))
        assertEquals("X", cleanTitle("سریال کره ای X"))
        assertEquals("X", cleanTitle("سریال کره‌ای X"))
        assertEquals("X", cleanTitle("سریال X"))
        assertEquals("X", cleanTitle("انیمه X"))
        assertEquals("X", cleanTitle("انیمیشن X"))
    }

    @Test
    fun longerLabelWinsOverItsPrefix() {
        // "فیلم" is a prefix of "فیلم ترکی"; the whole two-word label must go.
        assertEquals("Baazigar 1993", cleanTitle("فیلم هندی Baazigar 1993"))
    }

    @Test
    fun trimsLeadingAndTrailingSpace() {
        assertEquals("The Naked Gun 2025", cleanTitle(" فیلم The Naked Gun 2025 "))
    }

    @Test
    fun labelMustBeFollowedBySpace() {
        assertEquals("فیلمساز X", cleanTitle("فیلمساز X"))
        assertEquals("فیلم", cleanTitle("فیلم"))
        assertEquals("فیلم", cleanTitle("فیلم "))
    }

    @Test
    fun onlyTheLeadingLabelIsRemoved() {
        assertEquals("The فیلم Story", cleanTitle("فیلم The فیلم Story"))
    }

    @Test
    fun ampersandEntityInTitle() {
        assertEquals("Your Friends & Neighbors", cleanTitle("سریال Your Friends &#038; Neighbors"))
        assertEquals("Tom & Jerry", cleanTitle("فیلم Tom &amp; Jerry"))
    }

    // --- entities -----------------------------------------------------------------------------

    @Test
    fun decodesNumericHexAndNamedEntities() {
        assertEquals("a’b", "a&#8217;b".decodeHtmlEntities())
        assertEquals("a’b", "a&#x2019;b".decodeHtmlEntities())
        assertEquals("a’b", "a&#X2019;b".decodeHtmlEntities())
        assertEquals("–", "&#8211;".decodeHtmlEntities())
        assertEquals("& \" < > '", "&amp; &quot; &lt; &gt; &apos;".decodeHtmlEntities())
        assertEquals("café", "caf&eacute;".decodeHtmlEntities())
        assertEquals("😀", "&#128512;".decodeHtmlEntities())
    }

    @Test
    fun leavesUnknownOrInvalidEntitiesAlone() {
        assertEquals("&nosuchentity;", "&nosuchentity;".decodeHtmlEntities())
        assertEquals("a & b", "a & b".decodeHtmlEntities())
        assertEquals("&#0;", "&#0;".decodeHtmlEntities())
        assertEquals("&#xD800;", "&#xD800;".decodeHtmlEntities())
        assertEquals("AT&T", "AT&T".decodeHtmlEntities())
        assertEquals("plain", "plain".decodeHtmlEntities())
    }

    // --- numbers ------------------------------------------------------------------------------

    @Test
    fun parsesYear() {
        assertEquals(2025, parseYear("2025"))
        assertEquals(2025, parseYear("2025-03-04"))
        assertEquals(2025, parseYear("۲۰۲۵"))
        assertNull(parseYear("0"))
        assertNull(parseYear(""))
        assertNull(parseYear("n/a"))
    }

    @Test
    fun parsesImdb() {
        assertEquals(6.3f, parseImdb("6.3")!!, 0.0001f)
        assertEquals(7.6f, parseImdb(" 7,6 ")!!, 0.0001f)
        assertNull(parseImdb("0.0"))
        assertNull(parseImdb("0"))
        assertNull(parseImdb(""))
        assertNull(parseImdb("abc"))
    }

    @Test
    fun parsesMetacritic() {
        assertEquals(64, parseMetacritic("64%"))
        assertEquals(58, parseMetacritic("58"))
        assertNull(parseMetacritic("0%"))
        assertNull(parseMetacritic("0"))
        assertNull(parseMetacritic(""))
    }

    @Test
    fun parsesRuntime() {
        assertEquals(85, parseRuntimeMinutes("85 دقیقه"))
        assertEquals(65, parseRuntimeMinutes("65"))
        assertEquals(90, parseRuntimeMinutes("۹۰ دقیقه"))
        assertNull(parseRuntimeMinutes(""))
        assertNull(parseRuntimeMinutes("0 دقیقه"))
    }

    @Test
    fun parsesQuality() {
        assertEquals(1080, parseQuality("1080"))
        assertEquals(1080, parseQuality("BluRay 1080p"))
        assertEquals(720, parseQuality("WEB-DL 720p"))
        assertEquals(480, parseQuality("BluRay 480P"))
        assertEquals(720, parseQuality("BluRay x264 720p"))
        assertEquals(2160, parseQuality("4K"))
        assertNull(parseQuality(""))
        assertNull(parseQuality("BluRay"))
        assertNull(parseQuality("x264"))
    }

    @Test
    fun parsesOrdinals() {
        assertEquals(1, parseOrdinal("Season 1"))
        assertEquals(12, parseOrdinal("Episode 12"))
        assertNull(parseOrdinal("Special"))
        assertNull(parseOrdinal("Season 0"))
    }

    @Test
    fun parsesAudio() {
        assertEquals(AudioType.DUBBED, parseAudio("dub"))
        assertEquals(AudioType.DUBBED, parseAudio(" DUB "))
        assertEquals(AudioType.SUBTITLED, parseAudio("sub"))
        assertEquals(AudioType.SUBTITLED, parseAudio(""))
    }

    @Test
    fun formatsSizes() {
        assertEquals("2.42 GB", formatSize("2.42 GigaByte "))
        assertEquals("780 MB", formatSize("780 MegaByte "))
        assertEquals("1.1 TB", formatSize("1.1 TeraByte"))
        assertEquals("500 KB", formatSize("500 KiloBytes"))
        assertEquals("2.42 GB", formatSize("2.42 GB"))
        assertNull(formatSize(""))
        assertNull(formatSize("   "))
    }
}
