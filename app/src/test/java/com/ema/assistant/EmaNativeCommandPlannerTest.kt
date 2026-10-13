package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compound-request planning.
 *
 * The valuable property here is restraint. Splitting too eagerly truncates
 * message bodies and fires actions the user never asked for; splitting too
 * lazily leaves a two-part request half-done. Both directions are pinned down.
 */
class EmaNativeCommandPlannerTest {

    private fun plan(raw: String) = EmaNativeCommandPlanner.plan(raw)

    // ------------------------------------------------------------- normalization

    @Test
    fun blankInputPlansNothing() {
        assertTrue(plan("").isEmpty())
        assertTrue(plan("   ").isEmpty())
        assertTrue(plan("\n\t ").isEmpty())
    }

    @Test
    fun aSingleCommandPassesThroughNormalized() {
        assertEquals(listOf("open Spotify"), plan("open Spotify"))
        assertEquals(listOf("open Spotify"), plan("   open    Spotify   "))
    }

    @Test
    fun politePrefixesAreStripped() {
        assertEquals(listOf("open Spotify"), plan("please open Spotify"))
        assertEquals(listOf("open Spotify"), plan("kindly open Spotify"))
        assertEquals(listOf("open Spotify"), plan("Please   open Spotify"))
    }

    @Test
    fun aPolitePrefixIsNotStrippedFromTheMiddleOfACommand() {
        // "please" is part of the message body here, not scaffolding.
        assertEquals(
            listOf("message Ada saying please open the gate"),
            plan("message Ada saying please open the gate")
        )
    }

    // ------------------------------------------------------- app-directed compounds

    @Test
    fun openAndPlayBecomesOpenThenPlayOnThatApp() {
        assertEquals(listOf("open Spotify", "play Arcade on Spotify"), plan("open Spotify and play Arcade"))
    }

    @Test
    fun openAndSearchBecomesOpenThenSearchOnThatApp() {
        assertEquals(
            listOf("open YouTube", "search lofi beats on YouTube"),
            plan("open YouTube and search for lofi beats")
        )
    }

    @Test
    fun openAndFindIsTreatedAsASearch() {
        assertEquals(
            listOf("open Maps", "search coffee on Maps"),
            plan("open Maps and find coffee")
        )
    }

    @Test
    fun openAndMessageCollapsesIntoOneMessageCommand() {
        // Opening the app is implied by sending; a separate open step would
        // race the composer.
        assertEquals(
            listOf("message Ada on WhatsApp saying I am on my way"),
            plan("open WhatsApp and message Ada saying I am on my way")
        )
    }

    @Test
    fun aTrailingAndSendIsNotDuplicatedAsAStep() {
        assertEquals(
            listOf("message Ada on WhatsApp saying I am on my way"),
            plan("open WhatsApp and send message Ada saying I am on my way and send")
        )
    }

    @Test
    fun goToAndGoIntoWorkLikeOpen() {
        assertEquals(listOf("open Spotify", "play Arcade on Spotify"), plan("go to Spotify and play Arcade"))
        assertEquals(listOf("open Spotify", "play Arcade on Spotify"), plan("go into Spotify and play Arcade"))
    }

    @Test
    fun listenToIsAMediaRequest() {
        assertEquals(
            listOf("open Spotify", "play Hey Jude on Spotify"),
            plan("open Spotify and listen to Hey Jude")
        )
    }

    // ------------------------------------------------------------- sequence splits

    @Test
    fun semicolonsSplitUnambiguously() {
        assertEquals(
            listOf("open Spotify", "set an alarm for 7 am"),
            plan("open Spotify; set an alarm for 7 am")
        )
    }

    @Test
    fun andSplitsAtASecondActionVerb() {
        assertEquals(
            listOf("open Spotify", "set an alarm for 7 am"),
            plan("open Spotify and set an alarm for 7 am")
        )
    }

    @Test
    fun thenSplitsAtASecondActionVerb() {
        assertEquals(
            listOf("turn on wifi", "open YouTube"),
            plan("turn on wifi and then open YouTube")
        )
    }

    @Test
    fun aDanglingConnectorIsNotLeftOnASplitFragment() {
        plan("turn on wifi and then open YouTube").forEach { step ->
            assertTrue("fragment still ends in a connector: '$step'",
                !step.endsWith("and") && !step.endsWith("then"))
        }
    }

    @Test
    fun threeStepSequencesAllSurvive() {
        assertEquals(
            listOf("turn on wifi", "open YouTube", "set volume to 50 percent"),
            plan("turn on wifi; then open YouTube; then set volume to 50 percent")
        )
    }

    @Test
    fun thenInsideANonMessageCommandStillSplits() {
        assertEquals(
            listOf("open Settings", "turn on wifi"),
            plan("open Settings then turn on wifi")
        )
    }

    // ------------------------------------------------- message bodies stay intact

    @Test
    fun aMessageBodyContainingThenIsNeverSplit() {
        // Splitting here would send "I will land" and then place a phone call.
        assertEquals(
            listOf("text Ada saying I will land then call you"),
            plan("text Ada saying I will land then call you")
        )
    }

    @Test
    fun aMessageBodyContainingAndIsNeverSplit() {
        assertEquals(
            listOf("send a message to Ada saying buy milk and open the gate"),
            plan("send a message to Ada saying buy milk and open the gate")
        )
    }

    @Test
    fun aMessageBodyContainingAVerbAndThenIsNeverSplit() {
        assertEquals(
            listOf("send a message to Ada saying please then open the gate"),
            plan("send a message to Ada saying please then open the gate")
        )
    }

    @Test
    fun aMessageBodyContainingASemicolonIsNeverSplit() {
        assertEquals(
            listOf("message Ada saying bring milk; then open the gate"),
            plan("message Ada saying bring milk; then open the gate")
        )
    }

    @Test
    fun everyMessageOpenerIsProtected() {
        listOf(
            "message Ada saying land then call me",
            "send a message to Ada saying land then call me",
            "send message Ada saying land then call me",
            "text Ada saying land then call me",
            "send a text to Ada saying land then call me",
            "tell Ada to land then call me",
            "email Ada saying land then call me",
            "send an email to Ada saying land then call me"
        ).forEach { command ->
            assertEquals("message body was split: $command", 1, plan(command).size)
        }
    }

    @Test
    fun theBodyIsPreservedVerbatimIncludingPunctuation() {
        val body = "I'll land at 6; then call you and we can eat"
        val planned = plan("message Ada saying $body")
        assertEquals(1, planned.size)
        assertTrue(planned[0].endsWith(body))
    }

    // ------------------------------------------------------------------ idempotence

    @Test
    fun planningAnAlreadyPlannedStepChangesNothing() {
        val steps = plan("open Spotify and play Arcade")
        steps.forEach { step -> assertEquals(listOf(step), plan(step)) }
    }

    @Test
    fun planNeverReturnsBlankSteps() {
        listOf(
            "open Spotify and play Arcade",
            "open Spotify; set an alarm for 7 am",
            "turn on wifi and then open YouTube",
            "message Ada saying I am on my way"
        ).forEach { command ->
            plan(command).forEach { step -> assertTrue("blank step from: $command", step.isNotBlank()) }
        }
    }
}
