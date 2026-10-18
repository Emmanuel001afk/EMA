package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaMemoryTest {
    private fun run(m: EmaMemory, text: String): EmaMemory.Applied? = EmaMemory.parse(text)?.let { m.apply(it) }

    @Test fun rememberRecallForget() {
        var m = EmaMemory()
        val saved = run(m, "Remember that my gate code reminder is under the mat")!!
        // "code" with no digits is not a secret
        m = saved.memory
        assertEquals("Your gate code reminder is under the mat.", run(m, "what is my gate code reminder")!!.reply)
        m = run(m, "forget my gate code reminder")!!.memory
        assertNull(run(m, "what is my gate code reminder"))
    }

    @Test fun secretsAreRefused() {
        val m = EmaMemory()
        for (text in listOf("remember my password is hunter2", "remember that my ATM pin is 1234", "remember my bank account number is 0123456789")) {
            val r = run(m, text)!!
            assertEquals(m, r.memory)
            assertTrue(r.reply.contains("don't store"))
        }
    }

    @Test fun remindersAreNotMemory() {
        assertNull(EmaMemory.parse("remember to buy milk"))
        assertNull(EmaMemory.parse("remind me to call Mum at 8"))
    }

    @Test fun aliasesAndDefaults() {
        var m = EmaMemory()
        m = run(m, "when I say my wife I mean Ada Obi")!!.memory
        assertEquals("Ada Obi", m.aliases["wife"])
        m = run(m, "use Telegram for messages")!!.memory
        assertEquals("Telegram", m.defaults["messaging"])
        m = run(m, "set my default video app to YouTube")!!.memory
        assertEquals("YouTube", m.defaults["video"])
        val listed = run(m, "what do you remember")!!.reply
        assertTrue(listed.contains("Ada Obi") && listed.contains("Telegram"))
        assertEquals(EmaMemory(), run(m, "forget everything")!!.memory)
    }

    @Test fun boundedSize() {
        var m = EmaMemory()
        for (i in 1..EmaMemory.MAX_FACTS) m = run(m, "remember my item$i is thing$i")!!.memory
        val r = run(m, "remember my extra is too much")!!
        assertEquals(EmaMemory.MAX_FACTS, r.memory.facts.size)
        assertTrue(r.reply.contains("full"))
    }

    @Test fun encodingRoundTripsAwkwardText() {
        val m = EmaMemory(facts = mapOf("note" to "tab\there, new\nline, back\\slash"), aliases = mapOf("boss" to "Mr. Okafor"), defaults = mapOf("music" to "Spotify"))
        assertEquals(m, EmaMemory.decode(EmaMemory.encode(m)))
        assertEquals(EmaMemory(), EmaMemory.decode(null))
        assertEquals(EmaMemory(), EmaMemory.decode("garbage\nlines"))
    }

    @Test fun aliasIsUsedByUnderstanding() {
        val apps = listOf(EmaAppResolver.App("WhatsApp", "w"))
        val r = EmaSemanticParser.understand("text my wife on WhatsApp that I'm outside", EmaSemanticParser.Env(apps, aliases = mapOf("wife" to "Ada Obi")))
        val msg = (r as EmaSemanticParser.Result.Plan).steps.single().command as EmaCommand.SendMessage
        assertEquals("Ada Obi", msg.recipient)
        assertEquals("I'm outside", msg.body)
    }

    @Test fun defaultMessagingAppIsUsedWhenNoneNamed() {
        val apps = listOf(EmaAppResolver.App("Telegram", "t"), EmaAppResolver.App("WhatsApp", "w"))
        val r = EmaSemanticParser.understand("text mum good morning", EmaSemanticParser.Env(apps, defaultApps = mapOf("messaging" to "Telegram")))
        val msg = (r as EmaSemanticParser.Result.Plan).steps.single().command as EmaCommand.SendMessage
        assertEquals("Telegram", msg.app)
    }
}
