package app.bolbol.tv.core.lanremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniJsonTest {
    private val bs = '\\'

    private fun parse(json: String) = MiniJson.parseStringMap(json)

    @Test
    fun parsesFlatStringObjects() {
        assertEquals(mapOf("text" to "dune"), parse("""{"text":"dune"}"""))
        assertEquals(mapOf("t" to "abc", "text" to "x y"), parse("""{ "t" : "abc" , "text":"x y" }"""))
        assertEquals(emptyMap<String, String>(), parse("{}"))
        assertEquals(emptyMap<String, String>(), parse("  { }  "))
        assertEquals(mapOf("text" to ""), parse("""{"text":""}"""))
    }

    @Test
    fun lastDuplicateKeyWins() {
        assertEquals(mapOf("a" to "2"), parse("""{"a":"1","a":"2"}"""))
    }

    @Test
    fun parsesUtf8TextDirectly() {
        assertEquals(mapOf("text" to "بلبل"), parse("""{"text":"بلبل"}"""))
    }

    @Test
    fun parsesEscapes() {
        val json = "{\"text\":\"a${bs}\"b${bs}${bs}c${bs}/d${bs}ne${bs}tf${bs}rg${bs}bh${bs}fi\"}"
        assertEquals("a\"b\\c/d\ne\tf\rg\bh" + 12.toChar() + "i", parse(json)!!["text"])
    }

    @Test
    fun parsesUnicodeEscapesIncludingSurrogatePairs() {
        val json = "{\"text\":\"${bs}u0628${bs}u0644${bs}ud83c${bs}udfac\"}"
        val expected = String(charArrayOf(0x0628.toChar(), 0x0644.toChar())) + String(Character.toChars(0x1F3AC))
        assertEquals(expected, parse(json)!!["text"])
    }

    @Test
    fun rejectsEverythingElse() {
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("null"))
        assertNull(parse("[]"))
        assertNull(parse("\"text\""))
        assertNull(parse("{"))
        assertNull(parse("""{"text"}"""))
        assertNull(parse("""{"text":}"""))
        assertNull(parse("""{"text":"x""""))
        assertNull(parse("""{"text":"x",}"""))
        assertNull(parse("""{"text":"x" "b":"y"}"""))
        assertNull(parse("""{"text":"x"} extra"""))
        assertNull(parse("""{"text":"x"}{"text":"y"}"""))
        assertNull(parse("""{text:"x"}"""))
        assertNull(parse("""{'text':'x'}"""))
    }

    @Test
    fun rejectsNonStringValuesAndNesting() {
        assertNull(parse("""{"text":5}"""))
        assertNull(parse("""{"text":true}"""))
        assertNull(parse("""{"text":null}"""))
        assertNull(parse("""{"text":["a"]}"""))
        assertNull(parse("""{"text":{"a":"b"}}"""))
    }

    @Test
    fun rejectsBadEscapesAndRawControlCharacters() {
        assertNull(parse("{\"text\":\"a${bs}qb\"}"))
        assertNull(parse("{\"text\":\"a${bs}\"}"))
        assertNull(parse("{\"text\":\"a${bs}u12\"}"))
        assertNull(parse("{\"text\":\"a${bs}u12zz\"}"))
        assertNull(parse("{\"text\":\"a${bs}u+123\"}"))
        assertNull(parse("{\"text\":\"a\nb\"}")) // raw newline inside a string is illegal JSON
        assertNull(parse("{\"text\":\"a\tb\"}"))
    }
}
