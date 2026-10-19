package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's own sentence, verbatim, brackets and all.
 *
 * It used to be stored as a contact nickname, because the bracket and the
 * colon broke the "package spelling" clause, and the reply mentioned only one
 * of the two phrases.
 */
class EmaShortcutDefinitionTest {

    private val sentence =
        "Remember that whenever I say 'for me' or 'Phormi browser' it means open the Phormi app (package spelling: P H O R M I)"

    private val installed = listOf(
        EmaAppResolver.App("Phormi", "com.phormi.app"),
        EmaAppResolver.App("Phormi Browser", "com.phormi.browser"),
        EmaAppResolver.App("WhatsApp", "com.whatsapp")
    )

    @Test
    fun theOwnersSentenceSavesAnAppShortcut() {
        val definition = EmaShortcuts.parseDefinition(sentence)
        assertNotNull("the definition must be understood", definition)
        val triggers = definition!!.triggers.map { EmaShortcuts.normalizeTrigger(it) }
        assertEquals(listOf("for me", "phormi browser"), triggers)
        assertEquals("phormi", definition.packageSpelling)

        val resolution = EmaShortcuts.resolve(definition.meaning, definition.packageSpelling, installed)
        assertTrue("expected an app, was $resolution", resolution is EmaShortcuts.Resolution.App)
        val app = (resolution as EmaShortcuts.Resolution.App).app
        assertEquals("com.phormi.app", app.packageName)
    }

    @Test
    fun theReplyMentionsBothPhrases() {
        val definition = EmaShortcuts.parseDefinition(sentence)!!
        val memory = EmaMemory()
        val applied = memory.apply(EmaMemory.Op.Shortcut(definition), installed)
        assertNotNull(applied)
        assertTrue(
            "the reply must name both phrases, was: ${applied!!.reply}",
            applied.reply.contains("for me") && applied.reply.contains("Phormi browser")
        )
        val target = applied.memory.shortcuts["for me"]
        assertTrue("the shortcut must be stored, was $target", target is EmaShortcuts.Target.App)
        assertEquals("com.phormi.app", (target as EmaShortcuts.Target.App).packageName)
    }

    @Test
    fun aPackageClauseInBracketsIsSplitOff() {
        val (meaning, spelling) = EmaShortcuts.splitPackageClause("open the Phormi app (package spelling: P H O R M I)")
        assertEquals("open the Phormi app", meaning)
        assertEquals("phormi", spelling)
    }

    @Test
    fun aPackageClauseInWordsIsStillSplitOff() {
        val (meaning, spelling) = EmaShortcuts.splitPackageClause("open the Phormi app, package spelling: P H O R M I")
        assertEquals("open the Phormi app", meaning)
        assertEquals("phormi", spelling)
    }

    @Test
    fun aMeaningWithoutAPackageClauseIsUntouched() {
        val (meaning, spelling) = EmaShortcuts.splitPackageClause("open Spotify and play Calm Down")
        assertEquals("open Spotify and play Calm Down", meaning)
        assertEquals(null, spelling)
    }
}
