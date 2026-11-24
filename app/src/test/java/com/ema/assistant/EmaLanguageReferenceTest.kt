package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class EmaLanguageReferenceTest {
    @Test fun spellingAndLetterCountsUseTheOriginalInput() {
        assertEquals(EmaLanguageReference.Request.Spell("love"), EmaLanguageReference.parse("how do you spell love?"))
        assertEquals(EmaLanguageReference.Request.Spell("book"), EmaLanguageReference.parse("how is book spelled?"))
        val word = EmaLanguageReference.parse("spell café")!!
        assertTrue(EmaLanguageReference.localAnswer(word)!!.contains("c, a, f, é"))
        val count = EmaLanguageReference.parse("how many letters in A-b 7?")!!
        assertTrue(EmaLanguageReference.localAnswer(count)!!.contains("2 letters"))
        assertTrue(EmaLanguageReference.localAnswer(EmaLanguageReference.Request.Alphabet)!!.contains("26 letters"))
    }

    @Test fun definitionsRequireExplicitWordQuestions() {
        assertEquals(EmaLanguageReference.Request.Define("resilience"), EmaLanguageReference.parse("what does resilience mean?"))
        assertEquals(EmaLanguageReference.Request.Define("bank"), EmaLanguageReference.parse("define bank"))
        assertEquals(EmaLanguageReference.Request.Define("new york"), EmaLanguageReference.parse("meaning of New York"))
        assertNull(EmaLanguageReference.parse("what does this button do?"))
        assertNull(EmaLanguageReference.parse("message Ada saying define bank"))
        assertNull(EmaLanguageReference.parse("search the web for meaning of love"))
        assertTrue(EmaLanguageReference.parse("spell") is EmaLanguageReference.Request.Invalid)
        assertTrue(EmaLanguageReference.parse("spell 123") is EmaLanguageReference.Request.Invalid)
    }

    @Test fun contextualMeaningsRequireUniqueEvidence() {
        val entry = EmaLanguageReference.Entry("noun", listOf("land beside a river with water", "a financial institution that accepts money"))
        val request = EmaLanguageReference.parse("meaning of bank in sentence: The bank is beside the river and water")
        assertTrue(request is EmaLanguageReference.Request.SenseInSentence)
        val payload = "meaning of bank in sentence: The bank is beside the river and don't cross the water"
        assertEquals(EmaLanguageUnderstanding.Act.REQUEST, EmaLanguageUnderstanding.assess(payload).act)
        val answer = EmaLanguageReference.formatInSentence("bank", "The bank is beside the river and water", listOf(entry))
        assertTrue(answer.contains("land beside a river"))
        assertTrue(answer.contains("not a certain interpretation"))
        assertTrue(EmaLanguageReference.formatInSentence("bank", "I went to the bank", listOf(entry)).contains("can't reliably choose"))
        assertTrue(EmaLanguageReference.formatInSentence("bank", "I went outside", listOf(entry)).contains("couldn't find"))
    }

    @Test fun inflectionCandidatesAreLabelledNotAsserted() {
        fun zipped(text: String): ByteArray = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        }.toByteArray()
        val assets = mapOf(
            "exceptions" to zipped("children\tnoun\tchild\n"),
            "c" to zipped("child\tnoun\ta young person\n"),
            "d" to zipped("dog\tnoun\ta domestic animal\ndog\tverb\tto follow\n")
        )
        val open: (String) -> java.io.InputStream = { ByteArrayInputStream(assets.getValue(it)) }
        val children = EmaLanguageReference.possibleForms("children", open)
        assertEquals("child", children.single().form.word)
        assertTrue(EmaLanguageReference.formatWithForms("children", emptyList(), children).contains("Possible base forms"))
        val dogs = EmaLanguageReference.possibleForms("dogs", open)
        assertTrue(dogs.any { it.form.word == "dog" && it.form.partOfSpeech == "noun" })
    }

    @Test fun dictionarySearchIsExactAndPresentsMultipleSenses() {
        val rows = "bank\tnoun\triver edge\tfinancial institution\nbank\tverb\tput money in an account\nbanker\tnoun\ta person\n"
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(rows.toByteArray()) } }.toByteArray()
        val entries = EmaLanguageReference.find("bank") { bucket ->
            assertEquals("b", bucket)
            ByteArrayInputStream(bytes)
        }
        assertEquals(2, entries.size)
        val answer = EmaLanguageReference.format("bank", entries)
        assertTrue(answer.contains("river edge"))
        assertTrue(answer.contains("financial institution"))
        assertTrue(answer.contains("verb: put money"))
        assertTrue(answer.contains("context"))
        assertFalse(answer.contains("a person"))
        assertTrue(EmaLanguageReference.find("banks") { ByteArrayInputStream(bytes) }.isEmpty())
        assertTrue(EmaLanguageReference.format("banks", emptyList()).contains("couldn't find"))
    }
}
