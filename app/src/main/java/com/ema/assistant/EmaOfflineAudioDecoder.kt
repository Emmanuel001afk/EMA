package com.ema.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.math.min

/**
 * Universal on-device offline audio container and codec decoder for EMA's
 * speech-to-text engine.
 *
 * Streams local audio recordings of any standard Android format — `.wav`,
 * `.m4a` (AAC/ALAC), `.mp3`, `.ogg`, `.opus`, `.flac`, `.aac`, `.amr`, `.3gp`,
 * `.3gpp`, `.webm`, `.mp4` and `.mkv` (voice tracks) — into bounded 16 kHz
 * mono PCM16 chunks using Android's built-in [MediaExtractor] and [MediaCodec]
 * (with a direct zero-overhead fast path for RIFF/WAVE via
 * [EmaSpeechAudioProcessor.decodeWav]).
 *
 * Constant O(1) memory: multi-minute voice memos and meetings decode and feed
 * the recognizer incrementally without buffering the whole file in RAM. No
 * network, no external libraries, no temporary files on disk.
 */
object EmaOfflineAudioDecoder {

    /** Maximum audio duration decoded in a single offline transcription (20 minutes). */
    const val MAX_DURATION_MS = 20L * 60L * 1_000L
    private const val MAX_OUTPUT_SAMPLES = (EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE * MAX_DURATION_MS / 1_000L)
    private const val CODEC_TIMEOUT_US = 12_000L

    val SUPPORTED_EXTENSIONS: Set<String> = setOf(
        "wav", "wave", "m4a", "mp3", "ogg", "opus", "oga",
        "flac", "aac", "amr", "awb", "3gp", "3gpp", "webm", "mp4", "m4b", "mkv"
    )

    data class StreamMetadata(
        val mimeType: String,
        val originalSampleRate: Int,
        val originalChannels: Int,
        val durationMs: Long
    )

    /** True when [fileName] carries a supported offline audio extension. */
    fun isSupportedAudioFileName(fileName: String): Boolean {
        val clean = fileName.trim().lowercase(Locale.ROOT)
        val dot = clean.lastIndexOf('.')
        if (dot < 0 || dot == clean.lastIndex) return false
        return clean.substring(dot + 1) in SUPPORTED_EXTENSIONS
    }

    /** Decode a local [File] into streamed 16 kHz mono PCM16 chunks. */
    fun decodeFile(
        file: File,
        isCancelled: () -> Boolean = { false },
        onChunk: (samples16kMono: ShortArray, progress: Float) -> Unit
    ): StreamMetadata {
        require(file.isFile && file.canRead()) { "Audio file cannot be read" }
        require(file.length() > 16L) { "Audio file is empty" }

        // Fast direct path for bounded RIFF/WAVE files (supports PCM 8/16/24/32, float, A-law, μ-law).
        if (file.length() <= EmaSpeechAudioProcessor.MAX_BATCH_BYTES && startsWithWavHeader(file)) {
            val wav = file.inputStream().buffered().use { EmaSpeechAudioProcessor.decodeWav(it) }
            streamShortArray(wav.pcm16kMono, isCancelled, onChunk)
            return StreamMetadata(
                mimeType = "audio/wav",
                originalSampleRate = wav.originalSampleRate,
                originalChannels = wav.originalChannels,
                durationMs = wav.durationMs
            )
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return decodeExtractor(extractor, isCancelled, onChunk)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Decode a `content://` or `file://` [Uri] (Storage Access Framework file
     * picker, shared voice notes, MediaStore recordings) offline into streamed
     * 16 kHz mono PCM16 chunks.
     */
    fun decodeUri(
        context: Context,
        uri: Uri,
        isCancelled: () -> Boolean = { false },
        onChunk: (samples16kMono: ShortArray, progress: Float) -> Unit
    ): StreamMetadata {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "content" || scheme == "file") {
            "Only local on-device audio URIs are accepted for offline transcription"
        }

        // Probe first 12 bytes to catch uncompressed WAV recordings cleanly.
        val isWav = runCatching {
            context.contentResolver.openInputStream(uri)?.use { probeWavHeader(it) } ?: false
        }.getOrDefault(false)

        if (isWav) {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Could not open selected audio recording")
            val wav = input.buffered().use { EmaSpeechAudioProcessor.decodeWav(it) }
            streamShortArray(wav.pcm16kMono, isCancelled, onChunk)
            return StreamMetadata(
                mimeType = "audio/wav",
                originalSampleRate = wav.originalSampleRate,
                originalChannels = wav.originalChannels,
                durationMs = wav.durationMs
            )
        }

        val extractor = MediaExtractor()
        var pfd: ParcelFileDescriptor? = null
        try {
            val openedViaDescriptor = runCatching {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                val fd = pfd?.fileDescriptor ?: return@runCatching false
                extractor.setDataSource(fd)
                true
            }.getOrDefault(false)
            if (!openedViaDescriptor) {
                extractor.setDataSource(context, uri, null)
            }
            return decodeExtractor(extractor, isCancelled, onChunk)
        } finally {
            runCatching { extractor.release() }
            runCatching { pfd?.close() }
        }
    }

    private fun decodeExtractor(
        extractor: MediaExtractor,
        isCancelled: () -> Boolean,
        onChunk: (ShortArray, Float) -> Unit
    ): StreamMetadata {
        var audioTrack = -1
        var trackFormat: MediaFormat? = null
        var mime = ""
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val m = if (fmt.containsKey(MediaFormat.KEY_MIME)) fmt.getString(MediaFormat.KEY_MIME).orEmpty() else ""
            if (m.startsWith("audio/")) {
                audioTrack = i
                trackFormat = fmt
                mime = m
                break
            }
        }
        val format = trackFormat
            ?: throw IllegalArgumentException("No supported audio track found in file")
        extractor.selectTrack(audioTrack)

        var sampleRate = format.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE)
            .coerceIn(4_000, 192_000)
        var channels = format.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceIn(1, 8)
        var pcmEncoding = format.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        val declaredDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L).coerceAtLeast(0L)
        } else {
            0L
        }

        val codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrElse {
            throw IllegalArgumentException("This phone has no local decoder for $mime")
        }

        var produced16kSamples = 0L
        try {
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleSpins = 0
            val staging = ShortArray(4_000) // 250 ms coalesced 16 kHz mono blocks
            var staged = 0
            var lastProgress = 0f

            while (!outputDone && !isCancelled() && produced16kSamples < MAX_OUTPUT_SAMPLES) {
                var progressed = false

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        progressed = true
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf == null) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            inBuf.clear()
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime.coerceAtLeast(0L)
                                if (pts > MAX_DURATION_MS * 1_000L) {
                                    codec.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progressed = true
                        val newFmt = codec.outputFormat
                        sampleRate = newFmt.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate).coerceIn(4_000, 192_000)
                        channels = newFmt.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceIn(1, 8)
                        pcmEncoding = newFmt.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    outIdx >= 0 -> {
                        progressed = true
                        try {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val raw = ByteArray(bufferInfo.size)
                                outBuf.get(raw)
                                val (bits, isFloat) = encodingSpec(pcmEncoding)
                                val nativeShorts = EmaSpeechAudioProcessor.decodeRawPcmToShorts(raw, raw.size, bits, isFloat)
                                if (nativeShorts.isNotEmpty()) {
                                    val mono16k = EmaSpeechAudioProcessor.resampleToMono16k(nativeShorts, sampleRate, channels)
                                    if (declaredDurationUs > 0L && bufferInfo.presentationTimeUs > 0L) {
                                        lastProgress = (bufferInfo.presentationTimeUs.toDouble() / declaredDurationUs.toDouble())
                                            .toFloat().coerceIn(0f, 0.99f)
                                    }
                                    var p = 0
                                    while (p < mono16k.size && produced16kSamples < MAX_OUTPUT_SAMPLES) {
                                        val take = min(staging.size - staged, mono16k.size - p)
                                        System.arraycopy(mono16k, p, staging, staged, take)
                                        staged += take
                                        p += take
                                        produced16kSamples += take
                                        if (staged == staging.size) {
                                            onChunk(staging.copyOf(), lastProgress)
                                            staged = 0
                                        }
                                    }
                                }
                            }
                        } finally {
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }

                if (progressed) {
                    idleSpins = 0
                } else if (++idleSpins > 250 && inputDone) {
                    // Protect against stalled vendor codecs at end-of-stream.
                    break
                }
            }

            if (staged > 0 && !isCancelled()) {
                onChunk(staging.copyOf(staged), 1.0f)
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        val actualDurationMs = produced16kSamples * 1_000L / EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE
        return StreamMetadata(
            mimeType = mime,
            originalSampleRate = sampleRate,
            originalChannels = channels,
            durationMs = if (actualDurationMs > 0L) actualDurationMs else declaredDurationUs / 1_000L
        )
    }

    private fun streamShortArray(
        samples: ShortArray,
        isCancelled: () -> Boolean,
        onChunk: (ShortArray, Float) -> Unit
    ) {
        if (samples.isEmpty()) return
        val limit = min(samples.size.toLong(), MAX_OUTPUT_SAMPLES).toInt()
        val block = 4_000 // 250 ms at 16 kHz
        var offset = 0
        while (offset < limit && !isCancelled()) {
            val end = min(offset + block, limit)
            val progress = end.toFloat() / limit.toFloat()
            onChunk(samples.copyOfRange(offset, end), progress)
            offset = end
        }
    }

    private fun encodingSpec(pcmEncoding: Int): Pair<Int, Boolean> {
        if (pcmEncoding == AudioFormat.ENCODING_PCM_8BIT) return 8 to false
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) return 32 to true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (pcmEncoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) return 24 to false
            if (pcmEncoding == AudioFormat.ENCODING_PCM_32BIT) return 32 to false
        }
        return 16 to false
    }

    private fun MediaFormat.intOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    private fun startsWithWavHeader(file: File): Boolean = runCatching {
        file.inputStream().use { probeWavHeader(it) }
    }.getOrDefault(false)

    private fun probeWavHeader(stream: InputStream): Boolean {
        val header = ByteArray(12)
        var read = 0
        while (read < header.size) {
            val r = stream.read(header, read, header.size - read)
            if (r <= 0) break
            read += r
        }
        return EmaSpeechAudioProcessor.hasWavHeader(header, read)
    }
}
