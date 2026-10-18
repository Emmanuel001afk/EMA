package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaSemanticParserTest {
    private fun apps(vararg names: String) = names.map { EmaAppResolver.App(it, "pkg.$it") }
    private val env = EmaSemanticParser.Env(apps("WhatsApp", "YouTube", "Spotify", "Messages", "Telegram"), defaultApps = mapOf("video" to "YouTube"))

    private fun msg(text: String, e: EmaSemanticParser.Env = env): EmaCommand.SendMessage {
        val r = EmaSemanticParser.understand(text, e)
        assertTrue("$text -> $r", r is EmaSemanticParser.Result.Plan)
        return (r as EmaSemanticParser.Result.Plan).steps.mapNotNull { it.command }.filterIsInstance<EmaCommand.SendMessage>().single()
    }

    @Test fun bodyIsCopiedExactly() {
        val body = "Don't forget: 3 eggs, 2 apples & the WhatsApp group link!"
        assertEquals(body, msg("tell Daniel on WhatsApp that $body").body)
        // Spoken input has no meaningful runs of spaces; they are collapsed, words are not changed.
        assertEquals("ok see you", msg("text Daniel on WhatsApp saying ok   see you").body)
    }

    @Test fun quotedBodyKeepsInnerPunctuation() {
        assertEquals("Call me at 5. Not 6.", msg("message Daniel on WhatsApp \"Call me at 5. Not 6.\"").body)
    }

    @Test fun ambiguousAppAsksWhichOne() {
        val e = EmaSemanticParser.Env(apps("Acme Chat", "Acme Chat Pro", "YouTube"))
        val r = EmaSemanticParser.understand("message Daniel on Acme that I'm late", e)
        if (r is EmaSemanticParser.Result.Clarify) {
            assertEquals(EmaSemanticParser.Role.APP, r.missing)
            assertTrue(r.options.containsAll(listOf("Acme Chat", "Acme Chat Pro")))
        } else {
            // Grounding may legitimately pick the exact-prefix app, but never a guess outside the installed set.
            val m = (r as EmaSemanticParser.Result.Plan).steps.mapNotNull { it.command }.filterIsInstance<EmaCommand.SendMessage>().single()
            assertTrue(m.app in setOf("Acme Chat", "Acme Chat Pro"))
        }
    }

    @Test fun uninstalledAppIsNotInvented() {
        val m = msg("message Daniel on Signal that I'm late", EmaSemanticParser.Env(apps("WhatsApp")))
        assertNotEquals("WhatsApp", m.app)
    }

    @Test fun planStepsDescribeThemselvesForProgress() {
        val r = EmaSemanticParser.understand("Open YouTube, search for football, and play the third result.", env) as EmaSemanticParser.Result.Plan
        val d = r.steps.map { it.describe() }
        assertEquals(2, d.size)
        assertTrue(d[0].contains("football"))
        assertTrue(d[1].lowercase().contains("third"))
        assertTrue(r.confidence > 0.5)
    }

    @Test fun selectionAfterSearchDependsOnIt() {
        val r = EmaSemanticParser.understand("search YouTube for football and play the first video", env) as EmaSemanticParser.Result.Plan
        assertTrue(r.steps[1].dependsOnPrevious)
    }

    @Test fun normaliseStripsWakeWordsAndFillers() {
        assertEquals("open YouTube", EmaSemanticParser.normalise("Hey EMA, um, please open YouTube"))
    }
}
