package com.ema.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
 * Pure JVM tests for EMA's offline speech front-end: 75 Hz DC/rumble removal,
 * exact-reconstruction 3-band adaptive Wiener noise suppression, AGC + C1 soft
 * limiter, adaptive VAD, pre-roll retention, cadence/hesitation/continuation
 * smart endpointing, segmented reset, AudioQuality diagnostics, streaming O(1)
 * conditioning, Hann-windowed sinc anti-aliased resampling and universal WAV
 * (PCM 8/16/24/32, IEEE float, G.711 A-law/μ-law) decoding.
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
            EmaSpeechAudioProcessor.Config(enableHighPass = true, enableNoiseSuppression = false, enableAgc = false)
        )
        val dc = ShortArray(16_000 / 2) { 9_000 }
        val filteredDc = EmaSpeechAudioProcessor.bytesToShorts(processor.conditionBatch(dc))
        val tailMean = (4_000 until filteredDc.size).map { abs(filteredDc[it].toInt()) }.average()
        assertTrue("DC bias must be suppressed (got $tailMean)", tailMean < 40.0)

        processor.reset()
        val voice = sineShorts(16_000, 800.0, 5_000.0, durationMs = 400, dcOffset = 6_000.0)
        val filteredVoice = EmaSpeechAudioProcessor.bytesToShorts(processor.conditionBatch(voice))
        val outRms = rms(filteredVoice, from = 2_000)
        assertTrue("Voice tone energy must be preserved (got $outRms)", outRms in 3_200.0..3_900.0)
    }

    @Test
    fun wienerNoiseSuppressorAttenuatesStationaryHumWhilePreservingSpeech() {
        val processor = EmaSpeechAudioProcessor(
            EmaSpeechAudioProcessor.Config(
                enableHighPass = true,
                enableNoiseSuppression = true,
                enableAgc = false
            )
        )
        // 500 ms of stationary low-frequency 140 Hz room hum (non-speech).
        val humChunk = sineShorts(16_000, 140.0, amplitude = 160.0, durationMs = 500)
        val humOut = EmaSpeechAudioProcessor.bytesToShorts(processor.conditionStreamChunk(humChunk))
        val inHumRms = rms(humChunk, from = 4_000)
        val outHumRms = rms(humOut, from = 4_000)
        assertTrue(
            "Wiener denoiser should attenuate steady low-band hum ($inHumRms -> $outHumRms)",
            outHumRms < inHumRms * 0.72
        )

        // Follow immediately with clean 900 Hz speech-band voice: formants pass unharmed.
        val voiceChunk = sineShorts(16_000, 900.0, amplitude = 5_200.0, durationMs = 400)
        val voiceOut = EmaSpeechAudioProcessor.bytesToShorts(processor.conditionStreamChunk(voiceChunk))
        val outVoiceRms = rms(voiceOut, from = 1_500)
        assertTrue("Voiced speech band must remain strong (got $outVoiceRms)", outVoiceRms > 3_100.0)
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
        repeat(10) {
            val d = processor.process(silenceBytes(50, amplitude = 40))
            assertFalse(d.isVoiced)
            assertFalse(processor.hasSpeechStarted)
            assertTrue(d.audioToDecode.isEmpty())
            assertEquals(EmaSpeechAudioProcessor.Action.WAIT_FOR_SPEECH, d.action)
        }

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
        assertTrue("Pre-roll must precede onset frame (bytes=$decodedBytesOnStart)", decodedBytesOnStart >= 6_400)
    }

    @Test
    fun smartEndpointerAdaptsAcrossSnappyCompleteIncompleteAndHesitationClauses() {
        fun silenceUntilEndpoint(partialText: String, profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND): Long {
            val p = EmaSpeechAudioProcessor(EmaSpeechAudioProcessor.Config.forProfile(profile))
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

        val snappyWait = silenceUntilEndpoint("stop")
        val completeWait = silenceUntilEndpoint("turn on the flashlight in the bedroom")
        val incompleteWait = silenceUntilEndpoint("send a message to")
        val compoundWait = silenceUntilEndpoint("open whatsapp and")
        val hesitationWait = silenceUntilEndpoint("set an alarm for um")
        val confirmWait = silenceUntilEndpoint("yes", EmaRecognitionProfile.CONFIRMATION)

        assertTrue("Snappy 1-word command ($snappyWait ms) finishes promptly", snappyWait <= completeWait)
        assertTrue("Confirmation profile ($confirmWait ms) is faster than command ($completeWait ms)", confirmWait < completeWait)
        assertTrue(
            "Incomplete slot ($incompleteWait ms) must wait noticeably longer than complete command ($completeWait ms)",
            incompleteWait >= completeWait + 450L
        )
        assertTrue(
            "Trailing conjunction ($compoundWait ms) must wait longer than complete command ($completeWait ms)",
            compoundWait >= completeWait + 450L
        )
        assertTrue(
            "Spoken hesitation ($hesitationWait ms) must hold even longer than complete command ($completeWait ms)",
            hesitationWait >= completeWait + 600L
        )
        assertTrue(EmaSpeechAudioProcessor.isIncompleteClause("set an alarm for"))
        assertTrue(EmaSpeechAudioProcessor.isHesitationTail("call daniel uh"))
        assertTrue(EmaSpeechAudioProcessor.isSnappyShortCommand("stop"))
        assertFalse(EmaSpeechAudioProcessor.isIncompleteClause("set an alarm for six thirty am"))
    }

    @Test
    fun qualityDiagnosticsAndSegmentedSessionResetBehaveAccurately() {
        // 1. Dead/muted microphone detection.
        val muted = EmaSpeechAudioProcessor(EmaSpeechAudioProcessor.Config(noSpeechTimeoutMs = 1_200L))
        repeat(24) { muted.process(ByteArray(1_600)) }
        val mutedQuality = muted.qualitySummary()
        assertEquals(0, mutedQuality.peakAmplitude)
        val muteHint = mutedQuality.diagnosticHint()
        assertNotNull(muteHint)
        assertTrue(muteHint!!.contains("silent", ignoreCase = true))

        // 2. Heavy clipping detection.
        val clipped = EmaSpeechAudioProcessor()
        val fullScale = EmaSpeechAudioProcessor.shortsToBytes(
            ShortArray(800) { i -> if (i % 2 == 0) 32_767 else -32_767 }
        )
        repeat(8) { clipped.process(fullScale) }
        val clipHint = clipped.qualitySummary().diagnosticHint()
        assertNotNull(clipHint)
        assertTrue(clipHint!!.contains("clipping", ignoreCase = true))

        // 3. Segmented session reset allows back-to-back utterances while preserving noise adaptation.
        val segmented = EmaSpeechAudioProcessor(EmaSpeechAudioProcessor.Config.forProfile(EmaRecognitionProfile.CONFIRMATION))
        val voice = EmaSpeechAudioProcessor.shortsToBytes(sineShorts(16_000, 650.0, 5_000.0, durationMs = 50))
        repeat(8) { segmented.process(voice) }
        assertTrue(segmented.hasSpeechStarted)
        segmented.resetForNextSegment()
        assertFalse(segmented.hasSpeechStarted)
        assertEquals(0L, segmented.voicedDurationMs)
        var secondStarted = false
        repeat(4) {
            if (segmented.process(voice).speechJustStarted) secondStarted = true
        }
        assertTrue("Second segment must detect fresh speech onset cleanly", secondStarted)
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
    fun sincResamplerPreservesPassbandAndRejectsUltrasonicAliasing() {
        // 48 kHz mono 1 kHz voice -> flat passband.
        val s48 = sineShorts(48_000, 1_000.0, 8_000.0, durationMs = 200)
        val out48 = EmaSpeechAudioProcessor.resampleToMono16k(s48, 48_000, channels = 1)
        assertEquals(3_200, out48.size)
        assertTrue("1 kHz energy preserved from 48 kHz", rms(out48, 200) > 5_200.0)

        // 48 kHz ultrasonic 14.5 kHz interference -> killed by sinc anti-aliasing filter.
        val ultra = sineShorts(48_000, 14_500.0, 10_000.0, durationMs = 200)
        val ultraOut = EmaSpeechAudioProcessor.resampleToMono16k(ultra, 48_000, channels = 1)
        val aliasRms = rms(ultraOut, 200)
        assertTrue("Out-of-band 14.5 kHz must be strongly rejected (got $aliasRms)", aliasRms < 400.0)

        // 44.1 kHz mono -> ~3200 samples for 200 ms.
        val s44 = sineShorts(44_100, 750.0, 8_000.0, durationMs = 200)
        val out44 = EmaSpeechAudioProcessor.resampleToMono16k(s44, 44_100, channels = 1)
        assertTrue("Expected ~3200 samples, got ${out44.size}", out44.size in 3_195..3_205)
        assertTrue("750 Hz energy preserved from 44.1 kHz", rms(out44, 200) > 5_200.0)

        // 8 kHz telephony mono -> smooth cubic 2x upsample to 16 kHz.
        val s8 = sineShorts(8_000, 500.0, 8_000.0, durationMs = 200)
        val out8 = EmaSpeechAudioProcessor.resampleToMono16k(s8, 8_000, channels = 1)
        assertEquals(3_200, out8.size)
        assertTrue("500 Hz preserved on cubic upsample", rms(out8, 100) > 5_000.0)

        // 16 kHz stereo -> averages left and right cleanly.
        val stereo = ShortArray(200) { i -> if (i % 2 == 0) 6_000 else 2_000 }
        val mono = EmaSpeechAudioProcessor.resampleToMono16k(stereo, 16_000, channels = 2)
        assertEquals(100, mono.size)
        assertEquals(4_000, mono[10].toInt())
    }

    @Test
    fun wavDecoderParsesPcm16Pcm24FloatAndG711TelephonyFormats() {
        // 1. Stereo 48 kHz PCM16 with odd-sized JUNK metadata chunk.
        val src = sineShorts(48_000, 600.0, 10_000.0, durationMs = 250)
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
        assertTrue(rms(decoded.pcm16kMono, 200) > 6_000.0)

        // 2. IEEE 32-bit float mono 16 kHz WAV.
        val floatBytes = ByteBuffer.allocate(1_600 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until 1_600) putFloat((0.5 * sin(2.0 * PI * 440.0 * i / 16_000)).toFloat())
        }.array()
        val floatWav = buildWav(format = 3, channels = 1, sampleRate = 16_000, bitsPerSample = 32, data = floatBytes, withJunk = false)
        val floatDecoded = EmaSpeechAudioProcessor.decodeWav(ByteArrayInputStream(floatWav))
        assertEquals(1_600, floatDecoded.pcm16kMono.size)
        assertTrue(rms(floatDecoded.pcm16kMono, 100) > 10_000.0)

        // 3. ITU-T G.711 μ-law (format 7) & A-law (format 6) 8 kHz telephony WAVs.
        assertEquals(0, EmaSpeechAudioProcessor.ulawToPcm16(0xFF.toByte()).toInt())
        assertTrue(EmaSpeechAudioProcessor.ulawToPcm16(0x80.toByte()) > 30_000)
        assertTrue(abs(EmaSpeechAudioProcessor.alawToPcm16(0xD5.toByte()).toInt()) <= 16)
        assertTrue(EmaSpeechAudioProcessor.alawToPcm16(0xAA.toByte()) > 30_000)

        val ulawWav = buildWav(format = 7, channels = 1, sampleRate = 8_000, bitsPerSample = 8, data = ByteArray(800) { 0xFF.toByte() }, withJunk = false)
        val ulawDecoded = EmaSpeechAudioProcessor.decodeWav(ulawWav)
        assertEquals(1_600, ulawDecoded.pcm16kMono.size)
        assertEquals(100L, ulawDecoded.durationMs)

        val alawWav = buildWav(format = 6, channels = 1, sampleRate = 8_000, bitsPerSample = 8, data = ByteArray(800) { 0xD5.toByte() }, withJunk = false)
        val alawDecoded = EmaSpeechAudioProcessor.decodeWav(alawWav)
        assertEquals(1_600, alawDecoded.pcm16kMono.size)

        // 4. Raw 24-bit signed PCM decoding & multi-format extension table.
        val pcm24 = byteArrayOf(0x00, 0x20, 0x40, 0x00, 0xE0.toByte(), 0xBF.toByte())
        val s24 = EmaSpeechAudioProcessor.decodeRawPcmToShorts(pcm24, pcm24.size, bitsPerSample = 24)
        assertEquals(2, s24.size)
        assertTrue(s24[0] > 16_000 && s24[1] < -16_000)

        assertTrue(EmaOfflineAudioDecoder.isSupportedAudioFileName("meeting.m4a"))
        assertTrue(EmaOfflineAudioDecoder.isSupportedAudioFileName("WhatsApp-Voice.OPUS"))
        assertTrue(EmaOfflineAudioDecoder.isSupportedAudioFileName("lecture.mp3"))
        assertTrue(EmaOfflineAudioDecoder.isSupportedAudioFileName("studio.flac"))
        assertFalse(EmaOfflineAudioDecoder.isSupportedAudioFileName("invoice.pdf"))
        assertFalse(EmaOfflineAudioDecoder.isSupportedAudioFileName("photo.png"))
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
            i32(9)
            out.write(ByteArray(10))
        }
        out.write("data".toByteArray())
        i32(data.size)
        out.write(data)
        return out.toByteArray()
    }
}
