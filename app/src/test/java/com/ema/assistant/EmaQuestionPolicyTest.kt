package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

/** Routing corpus, not an accuracy benchmark for an AI service or Android device. */
class EmaQuestionPolicyTest {
    private val outsideQuestions = listOf(
        "how do I cook rice?",
        "how do I send a parcel?",
        "how do I learn Spanish?",
        "why does it rain?",
        "why do birds migrate?",
        "what does photosynthesis mean?",
        "is it possible to grow tomatoes indoors?"
    )

    @Test fun outsideQuestionsAreNotMistakenForDeviceInstructions() {
        for (question in outsideQuestions) {
            assertEquals(question, EmaLanguageUnderstanding.Act.QUESTION, EmaLanguageUnderstanding.assess(question).act)
            assertNull(question, EmaInstructionKnowledge.explainIfSupported(question))
            assertEquals(question, EmaQuestionPolicy.Decision.Unavailable,
                EmaQuestionPolicy.decide(question, EmaAssistantMode.NATIVE_ONLY, true))
            assertEquals(question, EmaQuestionPolicy.Decision.Unavailable,
                EmaQuestionPolicy.decide(question, EmaAssistantMode.NATIVE_FIRST, false))
            assertEquals(question, EmaQuestionPolicy.Decision.AskAi,
                EmaQuestionPolicy.decide(question, EmaAssistantMode.NATIVE_FIRST, true))
            assertEquals(question, EmaQuestionPolicy.Decision.AskAi,
                EmaQuestionPolicy.decide(question, EmaAssistantMode.AI_ASSIST, true))
        }
    }

    @Test fun supportedWorkflowQuestionsRemainLocalInEveryMode() {
        for (q in listOf("how do I send a message?", "can you work offline?", "how do I turn on the flashlight?")) {
            for (mode in EmaAssistantMode.values()) {
                val result = EmaQuestionPolicy.decide(q, mode, true)
                assertTrue("$mode $q", result is EmaQuestionPolicy.Decision.Local)
                assertTrue((result as EmaQuestionPolicy.Decision.Local).answer.contains("not executed"))
            }
        }
    }

    @Test fun explanationsRequireMoreThanAnOverlappingKeyword() {
        for (q in listOf("how do I send a package?", "how do I play chess?", "how do I read a map?", "how do I cook rice?")) {
            assertNull(q, EmaInstructionKnowledge.explainIfSupported(q))
        }
        assertNotNull(EmaInstructionKnowledge.explainIfSupported("how do I send a message?"))
    }

    @Test fun searchIsAnExplicitChoiceNotAnAutomaticFallback() {
        val message = EmaQuestionPolicy.unavailableMessage()
        assertTrue(message.contains("search the web for"))
        assertTrue(message.contains("send your query"))
        assertTrue(message.contains("answers may be wrong"))
        val parsed = EmaCommandGrammar.interpret("search the web for weather in Lagos")
        assertTrue(parsed is EmaCommand.WebSearch)
    }
}
