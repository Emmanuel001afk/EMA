package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaSentenceLogicTest {
    private fun solve(raw: String): EmaSentenceLogic.Answer {
        val parsed = EmaSentenceLogic.parse(raw) as EmaSentenceLogic.Parsed.Valid
        return EmaSentenceLogic.solve(parsed.problem)
    }

    @Test fun chainOfClassesIsGroundedInGivenPremises() {
        val answer = solve("reason: all cats are mammals; all mammals are animals; Luna is a cat; is Luna an animal?")
        assertEquals(EmaSentenceLogic.Verdict.YES, answer.verdict)
        assertTrue(answer.positiveEvidence.any { it.contains("Luna is a cat") })
        assertTrue(answer.positiveEvidence.any { it.contains("all mammals are animals") })
        assertEquals(EmaSentenceLogic.Verdict.UNKNOWN, solve("reason: all cats are mammals; is Luna a mammal?").verdict)
    }

    @Test fun exclusionAndNegationDoNotBecomePositiveFacts() {
        assertEquals(EmaSentenceLogic.Verdict.NO, solve("reason: all cats are mammals; no mammals are reptiles; Luna is a cat; is Luna a reptile?").verdict)
        assertEquals(EmaSentenceLogic.Verdict.YES, solve("reason: all cats are mammals; no mammals are reptiles; Luna is a cat; is Luna not a reptile?").verdict)
        assertEquals(EmaSentenceLogic.Verdict.UNKNOWN, solve("reason: no cats are reptiles; Luna is not a cat; is Luna a reptile?").verdict)
        assertEquals(EmaSentenceLogic.Verdict.NO, solve("reason: all cats are mammals; Luna is not a mammal; is Luna a cat?").verdict)
    }

    @Test fun contradictionsAreReportedInsteadOfChoosingAnAnswer() {
        val result = solve("reason: all cats are animals; no cats are animals; Luna is a cat; is Luna an animal?")
        assertEquals(EmaSentenceLogic.Verdict.CONFLICT, result.verdict)
        assertTrue(result.positiveEvidence.isNotEmpty())
        assertTrue(result.negativeEvidence.isNotEmpty())
    }

    @Test fun unsupportedInputAndActionPayloadsCannotTurnIntoCommands() {
        assertNull(EmaSentenceLogic.parse("message Ada saying reason: all cats are pets"))
        assertTrue(EmaSentenceLogic.parse("reason: open Spotify; is Luna a cat?") is EmaSentenceLogic.Parsed.Invalid)
        assertTrue(EmaSentenceLogic.parse("reason: all cats are not animals; Luna is a cat; is Luna an animal?") is EmaSentenceLogic.Parsed.Invalid)
        assertTrue(EmaSentenceLogic.parse("reason: all cats are animals; it is a cat; is it an animal?") is EmaSentenceLogic.Parsed.Invalid)
        assertTrue(EmaSentenceLogic.parse("reason: all cats are animals; ${"x".repeat(3001)}") is EmaSentenceLogic.Parsed.Invalid)
    }

    @Test fun regularPluralsMatchButIrregularFormsAreNotGuessed() {
        assertEquals(EmaSentenceLogic.Verdict.YES, solve("reason: all foxes are reptiles; Luna is a fox; is Luna a reptile?").verdict)
        assertEquals(EmaSentenceLogic.Verdict.UNKNOWN, solve("reason: all children are humans; Luna is a child; is Luna a human?").verdict)
        assertEquals(EmaSentenceLogic.Verdict.UNKNOWN, solve("reason: all species are animals; Luna is a specie; is Luna an animal?").verdict)
    }

    @Test fun cyclesAndUnrelatedIndividualsDoNotCreateFacts() {
        assertEquals(EmaSentenceLogic.Verdict.YES, solve("reason: all cats are mammals; all mammals are cats; Luna is a cat; is Luna a mammal?").verdict)
        assertEquals(EmaSentenceLogic.Verdict.UNKNOWN, solve("reason: all cats are mammals; Luna is a cat; is Ada a mammal?").verdict)
    }
}
