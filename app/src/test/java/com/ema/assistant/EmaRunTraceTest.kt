package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device run trace.
 *
 * The trace is only useful if it is honest and if it keeps private text out,
 * so both are tested here.
 */
class EmaRunTraceTest {

    @Test
    fun aRequestBodyIsCutToTheFirstWords() {
        val long = "send John a message saying " + "x".repeat(200)
        val redacted = EmaRunTrace.redact(long)
        assertTrue("the body must not be kept whole", redacted.length <= EmaRunTrace.MAX_TEXT + 1)
        assertTrue(redacted.startsWith("send John a message saying"))
    }

    @Test
    fun aShortRequestIsKeptAsItIs() {
        assertEquals("open Spotify and play Calm Down", EmaRunTrace.redact("open Spotify and play Calm Down"))
    }

    @Test
    fun secretsAreNotStoredAtAll() {
        assertEquals("[withheld]", EmaRunTrace.redact("my password is hunter2"))
        assertEquals("[withheld]", EmaRunTrace.redact("the otp is 123456"))
        assertEquals("[withheld]", EmaRunTrace.redact("card number 4111111111111111"))
        assertTrue(EmaRunTrace.looksSecret("the security code is 4321"))
        assertFalse(EmaRunTrace.looksSecret("open Spotify and play Calm Down"))
    }

    @Test
    fun theTraceCanBeReadBack() {
        val trace = EmaRunTrace(
            at = 1234L,
            text = "open Spotify and play Calm Down",
            alternatives = listOf("open Spotify and play Cold Down"),
            layer = "semantic",
            steps = listOf(
                EmaRunTrace.Step("Open Spotify", "UNVERIFIED", "I opened Spotify, but its screen was still changing.", 120L),
                EmaRunTrace.Step("Play Calm Down", "SUCCESS", "Playing Calm Down in Spotify.", 900L)
            ),
            screenControl = true,
            foreground = listOf("com.spotify.music"),
            durationMs = 1020L,
            result = "Playing Calm Down in Spotify."
        )
        val parsed = EmaRunTrace.parse(trace.format())
        assertNotNull(parsed)
        assertEquals("semantic", parsed!!.layer)
        assertEquals(2, parsed.steps.size)
        assertEquals("Play Calm Down", parsed.steps[1].description)
        assertEquals("SUCCESS", parsed.steps[1].status)
        assertEquals(900L, parsed.steps[1].durationMs)
        assertTrue(parsed.screenControl)
        assertEquals(1020L, parsed.durationMs)
        assertEquals(listOf("com.spotify.music"), parsed.foreground)
        assertEquals("Playing Calm Down in Spotify.", parsed.result)
    }

    @Test
    fun aStepMessageWithTheSeparatorIsStillReadBack() {
        val trace = EmaRunTrace(
            at = 1L,
            text = "x",
            alternatives = emptyList(),
            layer = "plan",
            steps = listOf(EmaRunTrace.Step("Send", "FAILED", "a | b | c", 5L)),
            screenControl = false,
            foreground = emptyList(),
            durationMs = 5L,
            result = "failed"
        )
        val parsed = EmaRunTrace.parse(trace.format())
        assertNotNull(parsed)
        assertEquals(1, parsed!!.steps.size)
        assertEquals("FAILED", parsed.steps[0].status)
    }

    @Test
    fun theFailingStepIsTheFirstOneThatDidNotWork() {
        val trace = EmaRunTrace(
            at = 1L,
            text = "x",
            alternatives = emptyList(),
            layer = "plan",
            steps = listOf(
                EmaRunTrace.Step("Open Spotify", "UNVERIFIED", "still changing", 10L),
                EmaRunTrace.Step("Play Calm Down", "FAILED", "no play button", 20L)
            ),
            screenControl = true,
            foreground = emptyList(),
            durationMs = 30L,
            result = "failed"
        )
        val explanation = trace.explainFailure()
        assertNotNull(explanation)
        assertTrue(explanation!!.contains("Play Calm Down"))
        assertTrue(explanation.contains("no play button"))
    }

    @Test
    fun aRunThatWorkedHasNoFailureToExplain() {
        val trace = EmaRunTrace(
            at = 1L,
            text = "x",
            alternatives = emptyList(),
            layer = "plan",
            steps = listOf(EmaRunTrace.Step("Open Spotify", "SUCCESS", "Opened Spotify.", 10L)),
            screenControl = true,
            foreground = emptyList(),
            durationMs = 10L,
            result = "Opened Spotify."
        )
        assertNull(trace.explainFailure())
    }

    @Test
    fun theBuilderKeepsTheRunSmall() {
        val builder = EmaRunTraceBuilder(1L)
        builder.text = "open Spotify"
        builder.alternatives = listOf("open spotify", "open spotty", "open spoti", "open spote", "open spot")
        repeat(20) { builder.addForeground("pkg$it") }
        repeat(5) { builder.addStep("step $it", "SUCCESS", "ok", it.toLong()) }
        val trace = builder.build()
        assertTrue("the foreground list must stay short", trace.foreground.size <= 8)
        assertTrue("the guesses must stay short", trace.alternatives.size <= 4)
        assertEquals(5, trace.steps.size)
    }

    @Test
    fun aStepMessageIsRedactedWhenItIsStored() {
        val builder = EmaRunTraceBuilder(1L)
        builder.addStep("Send", "FAILED", "my password is hunter2", 1L)
        assertEquals("[withheld]", builder.build().steps[0].message)
    }

    @Test
    fun textThatIsNotATraceIsNotReadBack() {
        assertNull(EmaRunTrace.parse("just some text"))
    }
}
