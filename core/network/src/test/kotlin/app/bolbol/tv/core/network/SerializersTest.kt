package app.bolbol.tv.core.network

import app.bolbol.tv.core.network.dto.DetailDto
import app.bolbol.tv.core.network.dto.FlagSerializer
import app.bolbol.tv.core.network.dto.LenientLongSerializer
import app.bolbol.tv.core.network.dto.LenientStringSerializer
import app.bolbol.tv.core.network.dto.PagedEnvelopeDto
import app.bolbol.tv.core.network.dto.SummaryDto
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializersTest {

    @Serializable
    private data class Probe(
        @Serializable(with = LenientStringSerializer::class) val s: String = "d",
        @Serializable(with = LenientLongSerializer::class) val n: Long = -1,
        @Serializable(with = FlagSerializer::class) val f: Boolean = false,
    )

    private fun probe(json: String) = BolboljanJson.decodeFromString(Probe.serializer(), json)

    @Test
    fun lenientStringAcceptsNumbersStringsAndNull() {
        assertEquals("2025", probe("""{"s":2025}""").s)
        assertEquals("2025", probe("""{"s":"2025"}""").s)
        assertEquals("6.3", probe("""{"s":6.3}""").s)
        assertEquals("true", probe("""{"s":true}""").s)
        assertEquals("d", probe("""{"s":null}""").s) // null coerces to the default
        assertEquals("d", probe("""{}""").s)
        assertEquals("", probe("""{"s":[]}""").s) // wrong shape -> empty, never a crash
        assertEquals("", probe("""{"s":{"a":1}}""").s)
    }

    @Test
    fun lenientLongAcceptsNumbersAndNumericStrings() {
        assertEquals(12L, probe("""{"n":12}""").n)
        assertEquals(12L, probe("""{"n":"12"}""").n)
        assertEquals(12L, probe("""{"n":12.0}""").n)
        assertEquals(0L, probe("""{"n":""}""").n)
        assertEquals(0L, probe("""{"n":"abc"}""").n)
        assertEquals(-1L, probe("""{"n":null}""").n)
    }

    @Test
    fun flagAcceptsOnOffBooleansAndOnes() {
        assertTrue(probe("""{"f":"on"}""").f)
        assertTrue(probe("""{"f":"ON"}""").f)
        assertTrue(probe("""{"f":true}""").f)
        assertTrue(probe("""{"f":"true"}""").f)
        assertTrue(probe("""{"f":1}""").f)
        assertTrue(probe("""{"f":"1"}""").f)
        assertFalse(probe("""{"f":""}""").f)
        assertFalse(probe("""{"f":false}""").f)
        assertFalse(probe("""{"f":0}""").f)
        assertFalse(probe("""{"f":null}""").f)
        assertFalse(probe("""{}""").f)
        assertFalse(probe("""{"f":"off"}""").f)
    }

    @Test
    fun dubbedAndSubtitleBooleansAndOnOffStringsBothDecode() {
        val summary = BolboljanJson.decodeFromString(SummaryDto.serializer(), """{"id":1,"dubbed":true,"subtitle":"on"}""")
        assertTrue(summary.dubbed)
        assertTrue(summary.subtitle)
    }

    @Test
    fun linkObjectsKeepTheirKeysAndNumericOrder() {
        val detail = BolboljanJson.decodeFromString(
            DetailDto.serializer(),
            """
            {"player_links":{"10":{"play_link":"c"},"2":{"play_link":"b"},"0":{"play_link":"a"}},
             "download_links":{"0":{"dl_link":"a"},"2":{"dl_link":"b"},"10":{"dl_link":"c"}}}
            """,
        )
        assertEquals(listOf("0", "2", "10"), detail.playerLinks.map { it.key })
        assertEquals(listOf("a", "b", "c"), detail.playerLinks.map { it.playLink })
        assertEquals(listOf("0", "2", "10"), detail.downloadLinks.map { it.key })
    }

    @Test
    fun linkArraysGetIndexKeys() {
        val detail = BolboljanJson.decodeFromString(
            DetailDto.serializer(),
            """{"player_links":[{"play_link":"a"},{"play_link":"b"}]}""",
        )
        assertEquals(listOf("0", "1"), detail.playerLinks.map { it.key })
    }

    @Test
    fun emptyAndWrongShapedListsBecomeEmptyLists() {
        for (value in listOf("[]", "{}", "\"\"", "null", "false", "0")) {
            val detail = BolboljanJson.decodeFromString(
                DetailDto.serializer(),
                """{"player_links":$value,"download_links":$value,"actors":$value,"related_posts":$value}""",
            )
            assertTrue(value, detail.playerLinks.isEmpty())
            assertTrue(value, detail.downloadLinks.isEmpty())
            assertTrue(value, detail.actors.isEmpty())
            assertTrue(value, detail.relatedPosts.isEmpty())
        }
    }

    @Test
    fun unknownKeysAreIgnored() {
        val page = BolboljanJson.decodeFromString(
            PagedEnvelopeDto.serializer(),
            """{"success":true,"page":"2","total_page":"9","brand_new_field":{"x":[1,2,3]},"data":{"0":{"id":5,"title":"T","extra":1}}}""",
        )
        assertEquals(2, page.page)
        assertEquals(9, page.totalPage)
        assertEquals(5L, page.data.single().id)
    }
}
