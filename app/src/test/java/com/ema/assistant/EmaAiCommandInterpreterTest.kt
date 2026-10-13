package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for EMA's optional AI layer.
 *
 * The important property: **the AI can only ever propose commands EMA can
 * actually execute.** Every canonical command advertised in the prompt is either
 * parsed by the deterministic grammar or handled as a literal device command by
 * the router, and this test proves the grammar half of that claim. If someone
 * adds a phrase to the prompt without teaching EMA to execute it, this fails.
 */
class EmaAiCommandInterpreterTest {

    @Test
    fun everyAdvertisedGrammarCommandIsExecutable() {
        val unexecutable = EmaAiCommandInterpreter.CANONICAL_COMMANDS
            .filter { it.grammar }
            .filter { EmaCommandGrammar.interpret(it.example) == null }
            .map { "${it.template}  (example: ${it.example})" }

        assertEquals(
            "The AI was offered canonical commands EMA cannot execute:\n" + unexecutable.joinToString("\n"),
            0, unexecutable.size
        )
    }

    @Test
    fun everyCommandMarkedAsLiteralIsRecognisedByTheRouterVocabulary() {
        val unrecognized = EmaAiCommandInterpreter.CANONICAL_COMMANDS
            .filterNot { it.grammar }
            .filterNot { EmaSystemCommandVocabulary.recognises(it.example.lowercase()) }
            .map { "${it.template}  (example: ${it.example})" }

        assertEquals(
            "These literal commands are advertised to the AI but are not in " +
                "EmaSystemCommandVocabulary, so the router gate would reject them:\n" +
                unrecognized.joinToString("\n"),
            0, unrecognized.size
        )
    }

    @Test
    fun everyAdvertisedCommandPassesTheDeviceActionGate() {
        // End-to-end contract: if the AI proposes any command EMA advertises, the
        // router must accept it as a device action rather than downgrading the
        // plan to a spoken answer.
        val rejected = EmaAiCommandInterpreter.CANONICAL_COMMANDS
            .filterNot { EmaSystemCommandVocabulary.looksLikeDeviceAction(it.example.lowercase()) }
            .map { "${it.template}  (example: ${it.example})" }

        assertEquals(
            "The AI action gate would reject these advertised commands:\n" + rejected.joinToString("\n"),
            0, rejected.size
        )
    }

    @Test
    fun theGrammarCanReparseItsOwnCanonicalOutput() {
        // EMA re-executes AI proposals through the same grammar. A command whose
        // canonical form the grammar cannot read back would loop or be dropped.
        val broken = EmaAiCommandInterpreter.CANONICAL_COMMANDS
            .filter { it.grammar }
            .mapNotNull { EmaCommandGrammar.interpret(it.example) }
            .map { it.toCanonical() }
            .filter { canonical -> EmaCommandGrammar.interpret(canonical) == null }

        assertEquals(
            "Grammar cannot reparse its own canonical output:\n" + broken.joinToString("\n"),
            0, broken.size
        )
    }

    @Test
    fun everyCanonicalEntryHasATemplateAndAnExample() {
        EmaAiCommandInterpreter.CANONICAL_COMMANDS.forEach {
            assertTrue("blank template", it.template.isNotBlank())
            assertTrue("blank example for ${it.template}", it.example.isNotBlank())
            assertFalse(
                "example should not still contain a placeholder: ${it.example}",
                it.example.contains('<')
            )
        }
    }

    @Test
    fun templatesAreUniqueSoThePromptIsUnambiguous() {
        val templates = EmaAiCommandInterpreter.CANONICAL_COMMANDS.map { it.template }
        assertEquals(templates.size, templates.distinct().size)
    }

    @Test
    fun thePromptEmbedsTheWholeVocabulary() {
        val vocabulary = EmaAiCommandInterpreter.canonicalVocabulary()
        EmaAiCommandInterpreter.CANONICAL_COMMANDS.forEach {
            assertTrue(
                "template missing from generated vocabulary: ${it.template}",
                vocabulary.contains(it.template)
            )
        }
    }

    @Test
    fun theVocabularyIsACommaSeparatedSingleLine() {
        val vocabulary = EmaAiCommandInterpreter.canonicalVocabulary()
        assertFalse("vocabulary must stay on one line", vocabulary.contains('\n'))
        assertTrue(vocabulary.contains(", "))
    }

    @Test
    fun theActionVocabularyIsSubstantial() {
        // A guard against someone trimming the list to nothing.
        assertTrue(
            "expected a broad native action vocabulary",
            EmaAiCommandInterpreter.CANONICAL_COMMANDS.size >= 40
        )
    }

    // ------------------------------------------------------ screen context gate

    @Test
    fun screenDumpsAreAttachedWhenTheRequestRefersToTheScreen() {
        assertTrue(EmaAiCommandInterpreter.needsScreenContext("tap this button"))
        assertTrue(EmaAiCommandInterpreter.needsScreenContext("what does the screen say"))
        assertTrue(EmaAiCommandInterpreter.needsScreenContext("read the visible text here"))
        assertTrue(EmaAiCommandInterpreter.needsScreenContext("scroll down the page"))
        assertTrue(EmaAiCommandInterpreter.needsScreenContext("type my email"))
        assertTrue(EmaAiCommandInterpreter.needsScreenContext("what is it showing"))
    }

    @Test
    fun knowledgeQuestionsDoNotCarryScreenContext() {
        // These are the requests that actually reach the AI in native-first mode,
        // and none of them needs a 10 KB accessibility dump.
        assertFalse(EmaAiCommandInterpreter.needsScreenContext("what is the capital of Ghana"))
        assertFalse(EmaAiCommandInterpreter.needsScreenContext("explain photosynthesis briefly"))
        assertFalse(EmaAiCommandInterpreter.needsScreenContext("who wrote Things Fall Apart"))
        assertFalse(EmaAiCommandInterpreter.needsScreenContext("give me three dinner ideas"))
    }

    @Test
    fun theGateIsCaseInsensitive() {
        assertTrue(EmaAiCommandInterpreter.needsScreenContext("TAP THIS BUTTON"))
        assertFalse(EmaAiCommandInterpreter.needsScreenContext("WHAT IS THE CAPITAL OF GHANA"))
    }

    // ------------------------------------------------------------- parse

    @Test
    fun plainJsonActionsAreParsed() {
        val parsed = EmaAiCommandInterpreter.parse(
            """{"mode":"actions","actions":["open Spotify","play Arcade on Spotify"],"answer":"On it."}"""
        )
        assertTrue(parsed is EmaAiCommandInterpreter.Result.Actions)
        val actions = parsed as EmaAiCommandInterpreter.Result.Actions
        assertEquals(listOf("open Spotify", "play Arcade on Spotify"), actions.actions)
        assertEquals("On it.", actions.answer)
    }

    @Test
    fun markdownFencedJsonIsParsed() {
        val parsed = EmaAiCommandInterpreter.parse(
            "```json\n{\"mode\":\"answer\",\"answer\":\"Accra is the capital of Ghana.\"}\n```"
        )
        assertTrue(parsed is EmaAiCommandInterpreter.Result.Answer)
        assertEquals(
            "Accra is the capital of Ghana.",
            (parsed as EmaAiCommandInterpreter.Result.Answer).text
        )
    }

    @Test
    fun jsonEmbeddedInProseIsStillFound() {
        val parsed = EmaAiCommandInterpreter.parse(
            "Sure! {\"mode\":\"answer\",\"answer\":\"It is 24 degrees.\"} Hope that helps."
        )
        assertEquals("It is 24 degrees.", (parsed as EmaAiCommandInterpreter.Result.Answer).text)
    }

    @Test
    fun nonJsonTextBecomesTheAnswerVerbatim() {
        val parsed = EmaAiCommandInterpreter.parse("Accra is the capital of Ghana.")
        assertTrue(parsed is EmaAiCommandInterpreter.Result.Answer)
        assertEquals("Accra is the capital of Ghana.", (parsed as EmaAiCommandInterpreter.Result.Answer).text)
    }

    @Test
    fun malformedJsonFallsBackToTheRawText() {
        val raw = "{mode: actions, actions: [}"
        val parsed = EmaAiCommandInterpreter.parse(raw)
        assertTrue(parsed is EmaAiCommandInterpreter.Result.Answer)
        assertEquals(raw, (parsed as EmaAiCommandInterpreter.Result.Answer).text)
    }

    @Test
    fun blankActionListsProduceNoActions() {
        val parsed = EmaAiCommandInterpreter.parse("""{"mode":"actions","actions":[],"answer":"Nothing to do."}""")
        assertTrue(parsed is EmaAiCommandInterpreter.Result.Actions)
        assertTrue((parsed as EmaAiCommandInterpreter.Result.Actions).actions.isEmpty())
    }

    @Test
    fun blankActionEntriesAreDropped() {
        val parsed = EmaAiCommandInterpreter.parse(
            """{"mode":"actions","actions":["open Spotify","  ",""],"answer":"On it."}"""
        )
        assertEquals(
            listOf("open Spotify"),
            (parsed as EmaAiCommandInterpreter.Result.Actions).actions
        )
    }

    @Test
    fun unknownModeIsTreatedAsAnAnswerNotAsActions() {
        val parsed = EmaAiCommandInterpreter.parse(
            """{"mode":"banana","answer":"Hello."}"""
        )
        assertTrue(parsed is EmaAiCommandInterpreter.Result.Answer)
    }

    @Test
    fun limitationsAreSurfacedRatherThanSwallowed() {
        val parsed = EmaAiCommandInterpreter.parse(
            """{"mode":"answer","answer":"I cannot do that.","limitation":"Android exposes no flashlight control here."}"""
        )
        assertEquals(
            "Android exposes no flashlight control here.",
            (parsed as EmaAiCommandInterpreter.Result.Answer).limitation
        )
    }

    @Test
    fun actionsCarryALimitationWhenTheModelReportsOne() {
        val parsed = EmaAiCommandInterpreter.parse(
            """{"mode":"actions","actions":["open Spotify"],"answer":"Opening.","limitation":"Playback may need a tap."}"""
        )
        assertNotNull(parsed)
        assertEquals(
            "Playback may need a tap.",
            (parsed as EmaAiCommandInterpreter.Result.Actions).limitation
        )
    }
}
