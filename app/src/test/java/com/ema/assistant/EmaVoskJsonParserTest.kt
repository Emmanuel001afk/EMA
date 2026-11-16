package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for Vosk N-best JSON parsing, acoustic confidence calibration,
 * hallucination filtering and multi-segment utterance assembly.
 */
class EmaVoskJsonParserTest {

    @Test
    fun parsesMultiAlternativeLatticeOutputAndCalibratesScores() {
        val json = """
            {
              "alternatives": [
                {
                  "confidence": 265.4,
                  "result": [
                    {"start": 0.12, "end": 0.36, "word": "open"},
                    {"start": 0.36, "end": 0.92, "word": "spotify"}
                  ],
                  "text": "open spotify"
                },
                {
                  "confidence": 248.1,
                  "text": "open spot if i"
                },
                {
                  "confidence": 240.0,
                  "text": "[unk] open spotify"
                }
              ]
            }
        """.trimIndent()

        val parsed = EmaVoskJsonParser.parseResult(json)
        // "[unk] open spotify" deduplicates against "open spotify".
        assertEquals(2, parsed.size)
        assertEquals("open spotify", parsed[0].text)
        assertEquals(2, parsed[0].words.size)
        assertEquals("spotify", parsed[0].words[1].word)
        assertEquals("open spot if i", parsed[1].text)
        assertTrue("Top alternative must outrank runner-up", parsed[0].confidence > parsed[1].confidence)
        assertTrue("Confidence lies in calibrated range", parsed[0].confidence in 0.50f..0.99f)
    }

    @Test
    fun parsesSingleBestWithPerWordConfidences() {
        val json = """
            {
              "result": [
                {"conf": 0.98, "end": 0.42, "start": 0.10, "word": "call"},
                {"conf": 0.91, "end": 0.95, "start": 0.43, "word": "daniel"}
              ],
              "text": "call [unk] daniel"
            }
        """.trimIndent()

        val parsed = EmaVoskJsonParser.parseResult(json)
        assertEquals(1, parsed.size)
        assertEquals("call daniel", parsed[0].text)
        assertTrue(parsed[0].confidence > 0.75f)
        assertEquals(1, parsed[0].unknownCount)
    }

    @Test
    fun parsesPartialWithWordPosteriors() {
        val json = """
            {
              "partial": "set an alarm for six",
              "partial_result": [
                {"conf": 1.0, "start": 0.1, "end": 0.3, "word": "set"},
                {"conf": 0.95, "start": 0.3, "end": 0.4, "word": "an"},
                {"conf": 0.97, "start": 0.4, "end": 0.8, "word": "alarm"},
                {"conf": 0.92, "start": 0.8, "end": 1.0, "word": "for"},
                {"conf": 0.88, "start": 1.0, "end": 1.3, "word": "six"}
              ]
            }
        """.trimIndent()

        val partial = EmaVoskJsonParser.parsePartial(json)
        assertEquals("set an alarm for six", partial.text)
        assertEquals(5, partial.words.size)
        assertTrue(partial.confidence > 0.85f)
    }

    @Test
    fun hallucinationFilterCatchesBriefNoiseFillersButNeverRealShortCommands() {
        val filler = EmaVoskJsonParser.parseResult(
            """{"text":"huh","result":[{"conf":0.42,"start":0.0,"end":0.2,"word":"huh"}]}"""
        )
        assertTrue(EmaVoskJsonParser.isNoiseHallucination(filler, voicedDurationMs = 220L))

        for (cmd in listOf("stop", "pause", "mute", "back", "home", "yes", "no", "help")) {
            val real = EmaVoskJsonParser.parseResult(
                """{"text":"$cmd","result":[{"conf":0.55,"start":0.0,"end":0.25,"word":"$cmd"}]}"""
            )
            assertFalse(
                "Must never discard short command '$cmd'",
                EmaVoskJsonParser.isNoiseHallucination(real, voicedDurationMs = 250L)
            )
        }
    }

    @Test
    fun utteranceAssemblerJoinsMultiSegmentRequestsAndPreservesNBestBeam() {
        val assembler = EmaUtteranceAssembler(maxAlternatives = 4)
        assembler.onSegment(
            """{"alternatives":[{"confidence":120.0,"text":"open whatsapp"},{"confidence":112.0,"text":"open what's up"}]}"""
        )
        val preview = assembler.onPartial("""{"partial":"and message daniel"}""")
        assertEquals("open whatsapp and message daniel", preview.text)

        val assembled = assembler.finish(
            """{"alternatives":[{"confidence":150.0,"text":"and message daniel saying hello"},{"confidence":140.0,"text":"and message dan saying hello"}]}"""
        )
        assertEquals(2, assembled.segmentCount)
        assertEquals("open whatsapp and message daniel saying hello", assembled.hypotheses.first().text)
        assertTrue(assembled.hypotheses.size >= 2)
    }

    @Test
    fun utteranceAssemblerRecoversLastPartialWhenFinalResultIsEmpty() {
        val assembler = EmaUtteranceAssembler()
        assembler.onPartial("""{"partial":"turn on flashlight"}""")
        val assembled = assembler.finish("""{"text":""}""")
        assertEquals("turn on flashlight", assembled.hypotheses.firstOrNull()?.text)
    }

    @Test
    fun deduplicatesBoundaryEchoesPunctuatesPausesAndCompactsLongRecordings() {
        // Boundary overlap dedup ("youtube" echoed across short pause).
        assertEquals(
            "open youtube and play music",
            EmaUtteranceAssembler.joinSegmentText("open youtube", "youtube and play music", pauseMs = 120L, punctuate = false)
        )
        // Pause-aware sentence and paragraph insertion in dictation/file mode.
        val assembler = EmaUtteranceAssembler(maxAlternatives = 5, formatPausePunctuation = true)
        assembler.onSegment("""{"text":"first sentence today"}""", explicitPauseMs = 0L)
        assembler.onSegment("""{"text":"second sentence follows"}""", explicitPauseMs = 1_200L)
        val done = assembler.finish("""{"text":"new paragraph starts here"}""", explicitPauseMs = 2_800L)
        assertEquals(3, done.segmentCount)
        assertEquals(
            "first sentence today. second sentence follows.\n\nnew paragraph starts here",
            done.hypotheses.first().text
        )

        // O(1) prefix compaction over 16 consecutive segments in a long audio file.
        val longForm = EmaUtteranceAssembler(maxAlternatives = 4, formatPausePunctuation = false)
        for (i in 1..15) {
            longForm.onSegment("""{"text":"part$i"}""")
        }
        val full = longForm.finish("""{"text":"part16"}""")
        assertEquals(16, full.segmentCount)
        assertEquals(16, full.wordCount)
        assertTrue(full.hypotheses.first().text.startsWith("part1 part2"))
        assertTrue(full.hypotheses.first().text.endsWith("part15 part16"))

        // Speaking-rate estimation from word timestamps.
        val words = listOf(
            EmaVoskJsonParser.WordTiming("open", 0.0f, 0.3f, 0.9f),
            EmaVoskJsonParser.WordTiming("youtube", 0.3f, 0.7f, 0.9f),
            EmaVoskJsonParser.WordTiming("now", 0.7f, 1.0f, 0.9f)
        )
        assertEquals(180, EmaVoskJsonParser.estimateWordsPerMinute(words))
    }

    @Test
    fun handlesMalformedAndEmptyJsonGracefully() {
        assertTrue(EmaVoskJsonParser.parseResult("").isEmpty())
        assertTrue(EmaVoskJsonParser.parseResult("{broken json").isEmpty())
        assertEquals("", EmaVoskJsonParser.parsePartial("").text)
    }
}
