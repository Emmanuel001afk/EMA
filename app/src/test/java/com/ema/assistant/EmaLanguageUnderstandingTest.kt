package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaLanguageUnderstandingTest {
    private val env = EmaSemanticParser.Env(listOf(
        EmaAppResolver.App("YouTube", "com.google.android.youtube"),
        EmaAppResolver.App("Spotify", "com.spotify.music"),
        EmaAppResolver.App("WhatsApp", "com.whatsapp")
    ))
    private fun commands(raw: String): List<String> {
        val result = EmaSemanticParser.understand(raw, env)
        assertTrue("$raw -> $result", result is EmaSemanticParser.Result.Plan)
        return (result as EmaSemanticParser.Result.Plan).steps.map { it.command?.toCanonical() ?: it.legacyText.orEmpty() }
    }

    @Test fun politeSurfaceFormsPreserveTheSameGoalAndRoles() {
        val requests = listOf(
            "open Spotify", "play Arcade on Spotify", "search YouTube for operating systems",
            "message Daniel on WhatsApp saying Don't change this, and call me later.",
            "open YouTube and search for football and play the first result"
        )
        val wrappers = listOf("please ", "could you please ", "can you ", "I would like you to ", "I need you to ", "help me to ")
        for (request in requests) for (wrapper in wrappers) {
            assertEquals("$wrapper$request", commands(request), commands(wrapper + request))
        }
    }

    @Test fun gerundWrappersOnlyRewriteTheLeadingAction() {
        assertEquals("open Spotify", EmaLanguageUnderstanding.assess("would you mind opening Spotify").canonical)
        val raw = "would you mind sending Daniel on WhatsApp saying opening playing sending"
        assertEquals("send Daniel on WhatsApp saying opening playing sending", EmaLanguageUnderstanding.assess(raw).canonical)
    }

    @Test fun negativeRequestsAndNegativeConstraintsNeverBecomePlans() {
        for (raw in listOf("don't send Daniel a message", "please never call Mum", "could you please not open Spotify", "open WhatsApp but don't send anything")) {
            val a = EmaLanguageUnderstanding.assess(raw)
            assertTrue(raw, a.act in setOf(EmaLanguageUnderstanding.Act.NEGATED, EmaLanguageUnderstanding.Act.CONSTRAINED))
            assertTrue(raw, EmaSemanticParser.understand(raw, env) is EmaSemanticParser.Result.NotHandled)
        }
    }

    @Test fun contentNegationAndActionWordsRemainData() {
        for (raw in listOf("message Daniel on WhatsApp saying don't open Spotify and never call me", "search YouTube for don't stop and never give up", "proofread: open Spotify but don't play music")) {
            val a = EmaLanguageUnderstanding.assess(raw)
            assertEquals(raw, EmaLanguageUnderstanding.Act.REQUEST, a.act)
            assertEquals(raw, a.canonical)
        }
    }

    @Test fun funCapabilityAndInstructionQuestionsAreNotCommands() {
        for (raw in listOf("how do I send a message", "can you send messages?", "what can I do with Spotify", "are you able to control apps")) {
            assertEquals(raw, EmaLanguageUnderstanding.Act.QUESTION, EmaLanguageUnderstanding.assess(raw).act)
            assertTrue(EmaSemanticParser.understand(raw, env) is EmaSemanticParser.Result.NotHandled)
            assertTrue(EmaInstructionKnowledge.explain(raw).contains("not executed"))
        }
        assertEquals(EmaLanguageUnderstanding.Act.REQUEST, EmaLanguageUnderstanding.assess("can you send Daniel on WhatsApp saying hello").act)
    }

    @Test fun funExactControlledLanguagePhrasesAreBoundedNotATranslator() {
        for ((raw, canonical) in EmaLanguageUnderstanding.controlledPhrases) assertEquals(canonical, EmaLanguageUnderstanding.assess(raw).canonical)
        assertEquals("Bonjour mon ami", EmaLanguageUnderstanding.assess("Bonjour mon ami").canonical)
        assertEquals("message Ada saying apaga la linterna", EmaLanguageUnderstanding.assess("message Ada saying apaga la linterna").canonical)
    }

    @Test fun funSettingsPreserveMeaningOfOnVersusOff() {
        for (thing in listOf("flashlight", "torch", "bluetooth", "wi-fi")) for (state in listOf("on", "off")) {
            assertEquals("turn $state $thing", EmaLanguageUnderstanding.assess("$state the $thing").canonical)
        }
    }

    @Test fun funAppPurposeIsGroundedAndDoesNotInventPrivateAppSupport() {
        val spotify = EmaApplicationKnowledge.describe(env.installedApps[1])
        assertEquals(EmaApplicationKnowledge.Role.MUSIC, spotify.role)
        assertTrue(spotify.limits.contains("subscription"))
        assertEquals(EmaApplicationKnowledge.Role.UNKNOWN, EmaApplicationKnowledge.describe(EmaAppResolver.App("Spotify Payroll", "internal.payroll")).role)
        assertTrue(EmaApplicationKnowledge.answerQuestion("what is Spotify used for?", env.installedApps)!!.contains("listening"))
        assertTrue(EmaApplicationKnowledge.answerQuestion("what can I do with WhatsApp?", env.installedApps)!!.contains("draft"))
        assertNull(EmaApplicationKnowledge.answerQuestion("message Ada saying what is Spotify used for", env.installedApps))
        assertTrue(EmaApplicationKnowledge.explainTerm("what does draft mean?")!!.contains("not been verified"))
    }

    @Test fun funEmptyAndOversizedRequestsAreNotExecuted() {
        assertEquals(EmaLanguageUnderstanding.Act.EMPTY, EmaLanguageUnderstanding.assess("  ").act)
        assertEquals(EmaLanguageUnderstanding.Act.CONSTRAINED, EmaLanguageUnderstanding.assess("x".repeat(16001)).act)
    }
}
