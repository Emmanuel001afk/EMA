package com.ema.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure JVM tests for EMA's offline speech front-end: DC/rumble removal, AGC,
 * soft limiting, adaptive VAD, pre-roll retention, continuation-aware
 * endpointing, multi-rate resampling and bounded RIFF/WAVE decoding.
 */
class EmaSpeechAudioProcessorTest {

    private fun sineShorts(
        sampleRate: Int,
        freqHz: Double,
        amplitude: Double,
        durationMs: Int,
        dcOffset: Double = 0.0
    ): ShortArray {
        val count = sampleRate * durationMs / 1000
        return ShortArray(count) { i ->
            val v = dcOffset + amplitude * sin(2.0 * PI * freqHz * i / sampleRate)
            v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun silenceBytes(durationMs: Int, amplitude: Int = 25): ByteArray {
        val count = 16_000 * durationMs / 1000
        val samples = ShortArray(count) { i -> if (i % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }
        return EmaSpeechAudioProcessor.shortsToBytes(samples)
    }

    private fun rms(samples: ShortArray, from: Int = 0): Double {
        if (from >= samples.size) return 0.0
        var sum = 0.0
        for (i in from until samples.size) sum += samples[i].toDouble() * samples[i].toDouble()
        return sqrt(sum / (samples.size - from))
    }

    @Test
    fun highPassRejectsLargeDcOffsetWhilePreservingVoiceTone() {
        val processor = EmaSpeechAudioProcessor(
            EmaSpeechAudioProcessor.Config(enableHighPass = true, enableAgc = false)
        )
        // Pure +9000 DC bias should decay to near zero after ~150 ms.
        val dc = ShortArray(16_000 / 2) { 9_000 }
        val filteredDc = EmaSpeechAudioProcessor.bytesToShorts(processor.conditionBatch(dc))
        val tailMean = (4_000 until filteredDc.size).map { abs(filteredDc[it].toInt()) }.average()
        assertTrue("DC bias must be suppressed (got $tailMean)", tailMean < 40.0)

        // 800 Hz speech-band tone should pass nearly unattenuated.
        processor.reset()
        val voice = sineShorts(16_000, 800.0, 5_000.0, durationMs = 400, dcOffset = 6_000.0)
        val filteredVoice = EmaSpeechAudioProcessor.bytesToShorts(processor.conditionBatch(voice))
        val outRms = rms(filteredVoice, from = 2_000)
        assertTrue("Voice tone energy must be preserved (got $outRms)", outRms in 3_200.0..3_900.0)
    }

    @Test
    fun agcBoostsQuietSpeechWithoutClippingLoudPeaks() {
        val processor = EmaSpeechAudioProcessor(
            EmaSpeechAudioProcessor.Config(enableHighPass = true, enableAgc = true)
        )
        val quiet = sineShorts(16_000, 550.0, amplitude = 900.0, durationMs = 800)
        val boosted = EmaSpeechAudioProcessor.bytesToShorts(processor.conditionBatch(quiet))
        val inRms = rms(quiet, from = 4_000)
        val outRms = rms(boosted, from = 4_000)
        assertTrue("Quiet voice should be boosted ($inRms -> $outRms)", outRms > inRms * 1.8)

        processor.reset()
        val loud = sineShorts(16_000, 550.0, amplitude = 31_500.0, durationMs = 500)
        val limited = EmaSpeechAudioProcessor.bytesToShorts(processor.conditionBatch(loud))
        val maxAbs = limited.maxOf { abs(it.toInt()) }
        assertTrue("Soft limiter must prevent hard 32767 clipping (max=$maxAbs)", maxAbs <= 32_760)
    }

    @Test
    fun vadEmitsPreRollOnSpeechOnsetAndIgnoresAmbientHum() {
        val processor = EmaSpeechAudioProcessor(
            EmaSpeechAudioProcessor.Config(
                preRollMs = 320,
                noSpeechTimeoutMs = 2_000L
            )
        )
        // 500 ms of quiet ambient hum -> never opens the gate.
        repeat(10) {
            val d = processor.process(silenceBytes(50, amplitude = 40))
            assertFalse(d.isVoiced)
            assertFalse(processor.hasSpeechStarted)
            assertTrue(d.audioToDecode.isEmpty())
            assertEquals(EmaSpeechAudioProcessor.Action.WAIT_FOR_SPEECH, d.action)
        }

        // Now deliver speech-band audio: onset fires and includes pre-roll frames.
        val speechFrame = EmaSpeechAudioProcessor.shortsToBytes(
            sineShorts(16_000, 620.0, amplitude = 4_500.0, durationMs = 50)
        )
        var started = false
        var decodedBytesOnStart = 0
        repeat(4) {
            val d = processor.process(speechFrame)
            if (d.speechJustStarted) {
                started = true
                decodedBytesOnStart = d.audioToDecode.sumOf { it.size }
            }
        }
        assertTrue("Speech onset must be detected", started)
        // Single 50 ms frame is 1600 bytes; pre-roll must include several prior frames too.
        assertTrue("Pre-roll must precede onset frame (bytes=$decodedBytesOnStart)", decodedBytesOnStart >= 6_400)
    }

    @Test
    fun continuationAwareEndpointerHoldsLongerOnIncompleteClause() {
        fun silenceUntilEndpoint(partialText: String): Long {
            val p = EmaSpeechAudioProcessor(EmaSpeechAudioProcessor.Config.forProfile(EmaRecognitionProfile.COMMAND))
            // Warm background then speak for 500 ms.
            repeat(4) { p.process(silenceBytes(50)) }
            val speech = EmaSpeechAudioProcessor.shortsToBytes(
                sineShorts(16_000, 500.0, amplitude = 5_000.0, durationMs = 50)
            )
            repeat(10) { p.process(speech) }
            p.onDecoderTranscript(partialText)

            var elapsedSilenceMs = 0L
            while (elapsedSilenceMs < 5_000L) {
                elapsedSilenceMs += 50L
                val d = p.process(silenceBytes(50))
                if (d.action == EmaSpeechAudioProcessor.Action.ENDPOINT_COMPLETE) break
            }
            return elapsedSilenceMs
        }

        val completeWait = silenceUntilEndpoint("turn on flashlight")
        val incompleteWait = silenceUntilEndpoint("send a message to")
        val compoundWait = silenceUntilEndpoint("open whatsapp and")

        assertTrue(
            "Incomplete slot ($incompleteWait ms) must wait noticeably longer than complete command ($completeWait ms)",
            incompleteWait >= completeWait + 450L
        )
        assertTrue(
            "Trailing conjunction ($compoundWait ms) must wait longer than complete command ($completeWait ms)",
            compoundWait >= completeWait + 450L
        )
        assertTrue(
            "isIncompleteClause flags open slots",
            EmaSpeechAudioProcessor.isIncompleteClause("set an alarm for")
        )
        assertFalse(
            "isIncompleteClause accepts finished commands",
            EmaSpeechAudioProcessor.isIncompleteClause("set an alarm for six thirty am")
        )
    }

    @Test
    fun noSpeechTimeoutTriggersCleanlyWhenRoomStaysSilent() {
        val processor = EmaSpeechAudioProcessor(
            EmaSpeechAudioProcessor.Config(noSpeechTimeoutMs = 1_500L)
        )
        var action = EmaSpeechAudioProcessor.Action.WAIT_FOR_SPEECH
        for (frame in 0 until 40) {
            action = processor.process(silenceBytes(50)).action
            if (action == EmaSpeechAudioProcessor.Action.TIMEOUT_NO_SPEECH) break
        }
        assertEquals(EmaSpeechAudioProcessor.Action.TIMEOUT_NO_SPEECH, action)
    }

    @Test
    fun resamplerHandles48k44k18kAndStereoAccurately() {
        // 48 kHz mono -> exact 3:1 decimation to 16 kHz.
        val s48 = sineShorts(48_000, 1_000.0, 8_000.0, durationMs = 200)
        val out48 = EmaSpeechAudioProcessor.resampleToMono16k(s48, 48_000, channels = 1)
        assertEquals(3_200, out48.size)
        assertTrue("1 kHz energy preserved from 48 kHz", rms(out48, 200) > 4_800.0)

        // 44.1 kHz mono -> ~3200 samples for 200 ms.
        val s44 = sineShorts(44_100, 750.0, 8_000.0, durationMs = 200)
        val out44 = EmaSpeechAudioProcessor.resampleToMono16k(s44, 44_100, channels = 1)
        assertTrue("Expected ~3200 samples, got ${out44.size}", out44.size in 3_195..3_205)
        assertTrue("750 Hz energy preserved from 44.1 kHz", rms(out44, 200) > 4_800.0)

        // 8 kHz mono -> 2x upsample to 16 kHz.
        val s8 = sineShorts(8_000, 500.0, 8_000.0, durationMs = 200)
        val out8 = EmaSpeechAudioProcessor.resampleToMono16k(s8, 8_000, channels = 1)
        assertEquals(3_200, out8.size)

        // 16 kHz stereo -> averages left and right cleanly.
        val stereo = ShortArray(200) { i -> if (i % 2 == 0) 6_000 else 2_000 }
        val mono = EmaSpeechAudioProcessor.resampleToMono16k(stereo, 16_000, channels = 2)
        assertEquals(100, mono.size)
        assertEquals(4_000, mono[10].toInt())
    }

    @Test
    fun wavDecoderParsesPcm16StereoAndFloatWithExtraChunks() {
        val src = sineShorts(48_000, 600.0, 10_000.0, durationMs = 250)
        // Build stereo 48 kHz PCM16 WAV with a JUNK metadata chunk before data.
        val stereoBytes = ByteArray(src.size * 4)
        val bb = ByteBuffer.wrap(stereoBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in src) {
            bb.putShort(s)
            bb.putShort(s)
        }
        val wavBytes = buildWav(format = 1, channels = 2, sampleRate = 48_000, bitsPerSample = 16, data = stereoBytes, withJunk = true)
        assertTrue(EmaSpeechAudioProcessor.hasWavHeader(wavBytes))
        val decoded = EmaSpeechAudioProcessor.decodeWav(ByteArrayInputStream(wavBytes))
        assertEquals(48_000, decoded.originalSampleRate)
        assertEquals(2, decoded.originalChannels)
        assertEquals(4_000, decoded.pcm16kMono.size)
        assertEquals(250L, decoded.durationMs)
        assertTrue(rms(decoded.pcm16kMono, 200) > 5_500.0)

        // IEEE 32-bit float mono 16 kHz WAV.
        val floatBytes = ByteBuffer.allocate(1_600 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until 1_600) putFloat((0.5 * sin(2.0 * PI * 440.0 * i / 16_000)).toFloat())
        }.array()
        val floatWav = buildWav(format = 3, channels = 1, sampleRate = 16_000, bitsPerSample = 32, data = floatBytes, withJunk = false)
        val floatDecoded = EmaSpeechAudioProcessor.decodeWav(ByteArrayInputStream(floatWav))
        assertEquals(1_600, floatDecoded.pcm16kMono.size)
        assertTrue(rms(floatDecoded.pcm16kMono, 100) > 10_000.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wavDecoderRejectsNonWavInput() {
        EmaSpeechAudioProcessor.decodeWav(ByteArrayInputStream("NOT_A_WAV_FILE_AT_ALL".toByteArray()))
    }

    @Test
    fun pcmByteConversionRoundTripsExactly() {
        val original = shortArrayOf(Short.MIN_VALUE, -12345, -1, 0, 1, 12345, Short.MAX_VALUE)
        val bytes = EmaSpeechAudioProcessor.shortsToBytes(original)
        val restored = EmaSpeechAudioProcessor.bytesToShorts(bytes)
        assertArrayEquals(original, restored)
        assertNotEquals(0, bytes.size)
    }

    private fun buildWav(
        format: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        data: ByteArray,
        withJunk: Boolean
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun i32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun i16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        val junkSize = if (withJunk) 8 + 10 else 0
        val riffPayload = 4 + (8 + 16) + junkSize + (8 + data.size)
        out.write("RIFF".toByteArray())
        i32(riffPayload)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        i32(16)
        i16(format)
        i16(channels)
        i32(sampleRate)
        val blockAlign = channels * (bitsPerSample / 8)
        i32(sampleRate * blockAlign)
        i16(blockAlign)
        i16(bitsPerSample)
        if (withJunk) {
            out.write("JUNK".toByteArray())
            i32(9) // odd size to verify RIFF word-alignment pad skipping
            out.write(ByteArray(10))
        }
        out.write("data".toByteArray())
        i32(data.size)
        out.write(data)
        return out.toByteArray()
    }
}
