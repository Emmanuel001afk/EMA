package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI response path is pure string work, so it is tested as such.
 *
 * The parser is deliberately tolerant: model output arrives in code fences,
 * wrapped in prose, and sometimes slightly malformed. What must never happen is
 * an exception, or silently dropping the assistant's words.
 */
class EmaJsonTest {

    @Test
    fun readsAFlatObject() {
        val parsed = EmaJson.readObject("""{"mode":"answer","answer":"Accra."}""")
        assertEquals("answer", parsed?.get("mode"))
        assertEquals("Accra.", parsed?.get("answer"))
    }

    @Test
    fun readsNestedArrays() {
        val parsed = EmaJson.readObject("""{"actions":["open Spotify","play Arcade"]}""")
        val actions = parsed?.get("actions") as? List<*>
        assertEquals(listOf("open Spotify", "play Arcade"), actions)
    }

    @Test
    fun readsNumbersBooleansAndNull() {
        val parsed = EmaJson.readObject("""{"count":3,"ratio":0.5,"ok":true,"missing":null}""")
        assertEquals(3L, parsed?.get("count"))
        assertEquals(0.5, parsed?.get("ratio"))
        assertEquals(true, parsed?.get("ok"))
        assertTrue(parsed!!.containsKey("missing"))
        assertNull(parsed["missing"])
    }

    @Test
    fun readsNestedObjects() {
        val parsed = EmaJson.readObject("""{"outer":{"inner":"value"}}""")
        val outer = parsed?.get("outer") as? Map<*, *>
        assertEquals("value", outer?.get("inner"))
    }

    @Test
    fun findsAnObjectEmbeddedInProse() {
        val parsed = EmaJson.readObject("""Sure! {"mode":"answer","answer":"Yes."} Hope that helps.""")
        assertEquals("Yes.", parsed?.get("answer"))
    }

    @Test
    fun handlesEscapes() {
        val parsed = EmaJson.readObject("""{"text":"line\nbreak \"quoted\" tab\tx"}""")
        assertEquals("line\nbreak \"quoted\" tab\tx", parsed?.get("text"))
    }

    @Test
    fun handlesUnicodeEscapes() {
        val parsed = EmaJson.readObject("""{"text":"caf\u00e9"}""")
        assertEquals("café", parsed?.get("text"))
    }

    @Test
    fun toleratesTrailingCommas() {
        val parsed = EmaJson.readObject("""{"a":"1","b":"2",}""")
        assertEquals("1", parsed?.get("a"))
        assertEquals("2", parsed?.get("b"))
    }

    @Test
    fun toleratesAnArrayTrailingComma() {
        val parsed = EmaJson.readObject("""{"actions":["one",]}""")
        assertEquals(listOf("one"), parsed?.get("actions"))
    }

    @Test
    fun toleratesWhitespaceAndNewlines() {
        val parsed = EmaJson.readObject(
            """
            {
              "mode" : "actions",
              "actions" : [ "open Spotify" ]
            }
            """.trimIndent()
        )
        assertEquals("actions", parsed?.get("mode"))
        assertEquals(listOf("open Spotify"), parsed?.get("actions"))
    }

    @Test
    fun returnsNullForNonJson() {
        assertNull(EmaJson.readObject("Accra is the capital of Ghana."))
        assertNull(EmaJson.readObject(""))
        assertNull(EmaJson.readObject("   "))
    }

    @Test
    fun returnsNullForAMalformedObject() {
        assertNull(EmaJson.readObject("{mode: actions, actions: [}"))
    }

    @Test
    fun neverThrowsOnTruncatedInput() {
        // Truncated model output must degrade, not crash the assistant.
        listOf(
            """{"mode":"answ""",
            """{"actions":["open""",
            "{",
            "[",
            """{"a":""",
            """{"a":"""
        ).forEach { input ->
            val parsed = runCatching { EmaJson.readObject(input) }
            assertTrue("threw on: $input", parsed.isSuccess)
        }
    }

    @Test
    fun lastValueWinsForDuplicateKeys() {
        val parsed = EmaJson.readObject("""{"mode":"answer","mode":"actions"}""")
        assertEquals("actions", parsed?.get("mode"))
    }

    // ----------------------------------------------------------- field helpers

    @Test
    fun stringReturnsEmptyForMissingOrNonStringValues() {
        val parsed = EmaJson.readObject("""{"mode":"answer","count":3}""")!!
        assertEquals("answer", EmaJson.string(parsed, "mode"))
        assertEquals("", EmaJson.string(parsed, "absent"))
        assertEquals("", EmaJson.string(parsed, "count"))
    }

    @Test
    fun stringListSkipsBlankAndNonStringEntries() {
        val parsed = EmaJson.readObject("""{"actions":["open Spotify","  ",null,3,"play Arcade"]}""")!!
        assertEquals(listOf("open Spotify", "play Arcade"), EmaJson.stringList(parsed, "actions"))
    }

    @Test
    fun stringListIsEmptyWhenTheFieldIsNotAnArray() {
        val parsed = EmaJson.readObject("""{"actions":"open Spotify"}""")!!
        assertTrue(EmaJson.stringList(parsed, "actions").isEmpty())
    }

    @Test
    fun stringListIsEmptyWhenTheFieldIsMissing() {
        val parsed = EmaJson.readObject("""{"mode":"actions"}""")!!
        assertTrue(EmaJson.stringList(parsed, "actions").isEmpty())
    }

    @Test
    fun stringListTrimsEntries() {
        val parsed = EmaJson.readObject("""{"actions":["  open Spotify  "]}""")!!
        assertEquals(listOf("open Spotify"), EmaJson.stringList(parsed, "actions"))
    }

    @Test
    fun anEmptyObjectIsStillAnObject() {
        val parsed = EmaJson.readObject("{}")
        assertFalse(parsed == null)
        assertTrue(parsed!!.isEmpty())
    }
}
