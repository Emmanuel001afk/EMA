package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaGroundedQuestionTest {
    @Test fun explicitScreenAndDocumentQuestionsAreRecognized() {
        assertEquals(EmaGroundedQuestion.Request(EmaGroundedQuestion.Source.SCREEN, "the deadline", true),
            EmaGroundedQuestion.parse("What does this screen say about the deadline?"))
        assertEquals(EmaGroundedQuestion.Request(EmaGroundedQuestion.Source.DOCUMENT, "Friday", true),
            EmaGroundedQuestion.parse("what does it mention about Friday?"))
        assertEquals(EmaGroundedQuestion.Request(EmaGroundedQuestion.Source.SCREEN, "When is the meeting"),
            EmaGroundedQuestion.parse("When is the meeting on this page?"))
        assertEquals(EmaGroundedQuestion.Source.DOCUMENT,
            EmaGroundedQuestion.parse("Who is the author in the document?")?.source)
    }

    @Test fun unrelatedCommandsAndKnowledgeQuestionsDoNotAcquireSources() {
        for (text in listOf("what is the weather in Lagos?", "open the screen", "message Ada saying what is on this screen", "what does it say", "what does it say about ?", "what is on the screen")) {
            assertNull(text, EmaGroundedQuestion.parse(text))
        }
        assertNull(EmaGroundedQuestion.parse("what is " + "x".repeat(2001) + " on this screen"))
    }

    @Test fun answersUseOnlySuppliedSourceAndDoNotInventMissingFacts() {
        val request = EmaGroundedQuestion.parse("what does this screen say about the deadline?")!!
        val answer = EmaScreenIntelligence.answerAboutText(request.question,
            "The project deadline is Friday. The team meets Monday.", topicOnly = request.topicOnly)
        assertTrue(answer.matched)
        assertTrue(answer.text.contains("Friday"))
        val missing = EmaScreenIntelligence.answerAboutText("what does it say about elephants", "The project deadline is Friday.")
        assertFalse(missing.matched)
        val wrongTopic = EmaScreenIntelligence.answerAboutText("flight deadline", "The workshop starts Friday at 10:30 am.", topicOnly = true)
        assertFalse(wrongTopic.matched)
    }
}
