package com.ema.assistant

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin parser, confidence calibrator, hallucination guard and
 * multi-segment utterance assembler for EMA's built-in Vosk acoustic decoder.
 *
 * Built on [EmaJson] rather than `org.json` so every code path runs identically
 * on Android and in plain JVM unit tests.
 */
object EmaVoskJsonParser {

    data class WordTiming(
        val word: String,
        val startSec: Float,
        val endSec: Float,
        /** Calibrated posterior in [0, 1], or -1f when the decoder omitted it. */
        val confidence: Float
    )

    data class Hypothesis(
        val text: String,
        /** Calibrated posterior estimate in [0.05, 0.99]. */
        val confidence: Float,
        /** Raw decoder score (lattice score or word-conf mean) before calibration. */
        val rawScore: Double,
        val words: List<WordTiming> = emptyList(),
        val unknownCount: Int = 0
    )

    data class PartialDecode(
        val text: String,
        val confidence: Float,
        val words: List<WordTiming> = emptyList()
    )

    private val UNK_TOKENS = setOf("[unk]", "<unk>", "<eps>", "[noise]", "[laughter]")

    /** Pure non-lexical breath/cough tokens that Vosk emits on ambient bursts. */
    private val PURE_NOISE_FILLERS = setOf(
        "huh", "uh", "uhh", "um", "umm", "erm", "er", "ah", "ahh", "eh", "hmm", "hm", "mm"
    )

    /** Bare function words that Kaldi occasionally emits on short mic clicks. */
    private val TRANSIENT_ARTICLES = setOf("the", "a", "an")

    private const val LATTICE_SOFTMAX_TEMP = 0.35

    /**
     * Parse either an N-best result (`{"alternatives": [...]}`) or a
     * single-best result (`{"text": "...", "result": [...]}`).
     *
     * [observedWordConf] supplies per-word posteriors collected from preceding
     * partial decodes, because Vosk's `alternatives` mode reports a path-level
     * lattice score rather than individual word `conf` entries.
     */
    fun parseResult(
        json: String,
        observedWordConf: Map<String, Float> = emptyMap(),
        maxAlternatives: Int = 6
    ): List<Hypothesis> {
        if (json.isBlank()) return emptyList()
        val obj = EmaJson.readObject(json) ?: return emptyList()

        val alternatives = obj["alternatives"] as? List<*>
        if (alternatives != null) {
            return parseAlternatives(alternatives, observedWordConf, maxAlternatives)
        }

        val rawText = EmaJson.string(obj, "text")
        val words = parseWords(obj["result"], observedWordConf)
        val (cleaned, unkCount) = cleanTokens(rawText.ifBlank { words.joinToString(" ") { it.word } })
        if (cleaned.isEmpty()) return emptyList()

        val wordConf = meanWordConfidence(cleaned, words, observedWordConf)
        val unkPenalty = (unkCount * 0.10f).coerceAtMost(0.35f)
        val calibrated = ((wordConf ?: 0.82f) - unkPenalty).coerceIn(0.05f, 0.99f)
        return listOf(
            Hypothesis(
                text = cleaned,
                confidence = calibrated,
                rawScore = calibrated.toDouble(),
                words = words,
                unknownCount = unkCount
            )
        )
    }

    /** Parse a streaming partial result (`{"partial": "...", "partial_result": [...]}`). */
    fun parsePartial(json: String): PartialDecode {
        if (json.isBlank()) return PartialDecode("", 0f)
        val obj = EmaJson.readObject(json) ?: return PartialDecode("", 0f)
        val words = parseWords(obj["partial_result"], emptyMap())
        val raw = EmaJson.string(obj, "partial").ifBlank { words.joinToString(" ") { it.word } }
        val (cleaned, unkCount) = cleanTokens(raw)
        if (cleaned.isEmpty()) return PartialDecode("", 0f)
        val base = meanWordConfidence(cleaned, words, emptyMap()) ?: 0.75f
        val conf = (base - (unkCount * 0.08f).coerceAtMost(0.30f)).coerceIn(0.05f, 0.99f)
        return PartialDecode(cleaned, conf, words)
    }

    /**
     * True when the decoded output is almost certainly a breath, cough or tap
     * rather than intentional speech. Real single-word commands ("stop",
     * "pause", "yes", "no", "home", "back", "help", "mute") always pass.
     */
    fun isNoiseHallucination(hypotheses: List<Hypothesis>, voicedDurationMs: Long): Boolean {
        val top = hypotheses.firstOrNull() ?: return true
        val tokens = top.text.lowercase().split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        if (tokens.size <= 3 && tokens.all { it in PURE_NOISE_FILLERS }) return true
        if (tokens.size == 1 && tokens[0] in TRANSIENT_ARTICLES &&
            voicedDurationMs < 420L && top.confidence < 0.72f
        ) {
            return true
        }
        return false
    }

    /** Estimate speaking rate in words-per-minute from word timings when available. */
    fun estimateWordsPerMinute(words: List<WordTiming>): Int {
        if (words.size < 2) return 0
        val start = words.first().startSec
        val end = words.last().endSec
        val spanSec = end - start
        if (!spanSec.isFinite() || spanSec < 0.35f) return 0
        return ((words.size / spanSec) * 60f).roundToInt().coerceIn(20, 400)
    }

    private fun parseAlternatives(
        rawList: List<*>,
        observedWordConf: Map<String, Float>,
        maxAlternatives: Int
    ): List<Hypothesis> {
        data class RawAlt(
            val text: String,
            val score: Double,
            val hasScore: Boolean,
            val words: List<WordTiming>,
            val unkCount: Int
        )

        val dedup = LinkedHashMap<String, RawAlt>()
        for (item in rawList) {
            val entry = item as? Map<*, *> ?: continue
            val rawText = (entry["text"] as? String).orEmpty()
            val words = parseWords(entry["result"], observedWordConf)
            val (cleaned, unkCount) = cleanTokens(rawText.ifBlank { words.joinToString(" ") { it.word } })
            if (cleaned.isEmpty()) continue
            val num = entry["confidence"] as? Number
            val score = num?.toDouble()?.takeIf { it.isFinite() } ?: 0.0
            val key = cleaned.lowercase()
            val existing = dedup[key]
            if (existing == null || (num != null && (!existing.hasScore || score > existing.score))) {
                dedup[key] = RawAlt(cleaned, score, num != null, words, unkCount)
            }
        }
        if (dedup.isEmpty()) return emptyList()

        val candidates = dedup.values.toList()
        val alreadyUnitProbabilities = candidates.all { it.hasScore && it.score in 0.0..1.0 } &&
            candidates.any { it.score > 0.0 }

        val posteriors: FloatArray = if (alreadyUnitProbabilities) {
            FloatArray(candidates.size) { i -> candidates[i].score.toFloat().coerceIn(0.05f, 0.99f) }
        } else if (candidates.size == 1) {
            val only = candidates[0]
            val wordConf = meanWordConfidence(only.text, only.words, observedWordConf) ?: 0.84f
            val penalized = (wordConf - (only.unkCount * 0.10f).coerceAtMost(0.35f)).coerceIn(0.05f, 0.99f)
            floatArrayOf(penalized)
        } else if (candidates.any { it.hasScore }) {
            // Calibrate Vosk lattice path scores via temperature-scaled softmax,
            // anchored so the leading hypothesis reflects both margin over its
            // runner-up and word-level acoustic confidence.
            val maxScore = candidates.filter { it.hasScore }.maxOf { it.score }
            val weights = DoubleArray(candidates.size) { i ->
                val s = if (candidates[i].hasScore) candidates[i].score else maxScore - 8.0
                val delta = ((s - maxScore) * LATTICE_SOFTMAX_TEMP).coerceIn(-30.0, 0.0)
                exp(delta)
            }
            val sum = weights.sum().coerceAtLeast(1e-9)
            FloatArray(candidates.size) { i ->
                val alt = candidates[i]
                val softmaxProb = (weights[i] / sum).toFloat()
                val wordConf = meanWordConfidence(alt.text, alt.words, observedWordConf)
                val relativeToTop = (weights[i] / weights[0]).toFloat()
                val blended = if (wordConf != null) {
                    0.55f * softmaxProb + 0.45f * (wordConf * (0.45f + 0.55f * relativeToTop))
                } else {
                    0.60f * softmaxProb + 0.40f * (0.80f * relativeToTop)
                }
                val unkPenalty = (alt.unkCount * 0.10f).coerceAtMost(0.35f)
                (blended - unkPenalty).coerceIn(0.05f, 0.99f)
            }
        } else {
            FloatArray(candidates.size) { i ->
                val base = (0.82f - i * 0.11f).coerceAtLeast(0.15f)
                val unkPenalty = (candidates[i].unkCount * 0.10f).coerceAtMost(0.35f)
                (base - unkPenalty).coerceIn(0.05f, 0.99f)
            }
        }

        return candidates.indices
            .map { i ->
                val alt = candidates[i]
                Hypothesis(
                    text = alt.text,
                    confidence = posteriors[i],
                    rawScore = if (alt.hasScore) alt.score else posteriors[i].toDouble(),
                    words = alt.words,
                    unknownCount = alt.unkCount
                )
            }
            .sortedWith(compareByDescending<Hypothesis> { it.confidence }.thenByDescending { it.rawScore })
            .take(maxAlternatives.coerceAtLeast(1))
    }

    private fun parseWords(raw: Any?, fallbackConf: Map<String, Float>): List<WordTiming> {
        val list = raw as? List<*> ?: return emptyList()
        val out = ArrayList<WordTiming>(list.size)
        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            val token = (map["word"] as? String)?.trim().orEmpty()
            if (token.isEmpty() || token.lowercase() in UNK_TOKENS) continue
            val start = (map["start"] as? Number)?.toFloat()?.takeIf { it.isFinite() && it >= 0f } ?: 0f
            val end = (map["end"] as? Number)?.toFloat()?.takeIf { it.isFinite() && it >= start } ?: start
            val explicitConf = (map["conf"] as? Number)?.toFloat()?.takeIf { it.isFinite() && it in 0f..1f }
            val conf = explicitConf ?: fallbackConf[token.lowercase()] ?: -1f
            out += WordTiming(token, start, end, conf)
        }
        return out
    }

    private fun meanWordConfidence(
        cleanedText: String,
        words: List<WordTiming>,
        observedWordConf: Map<String, Float>
    ): Float? {
        val knownFromWords = words.mapNotNull { it.confidence.takeIf { c -> c in 0f..1f } }
        if (knownFromWords.isNotEmpty()) {
            val mean = knownFromWords.sum() / knownFromWords.size
            val weakest = knownFromWords.min()
            return (0.75f * mean + 0.25f * weakest).coerceIn(0.05f, 0.99f)
        }
        if (observedWordConf.isEmpty()) return null
        val matched = cleanedText.lowercase().split(' ')
            .mapNotNull { observedWordConf[it] }
        if (matched.isEmpty()) return null
        return (matched.sum() / matched.size).coerceIn(0.05f, 0.99f)
    }

    /** Remove `[unk]`/`<unk>` placeholders and normalize whitespace, returning `(text, unknownCount)`. */
    fun cleanTokens(raw: String): Pair<String, Int> {
        if (raw.isBlank()) return "" to 0
        var unkCount = 0
        val kept = ArrayList<String>()
        for (piece in raw.trim().split(Regex("\\s+"))) {
            val lower = piece.lowercase()
            if (lower in UNK_TOKENS) {
                unkCount++
            } else if (piece.isNotBlank()) {
                kept += piece
            }
        }
        return kept.joinToString(" ") to unkCount
    }
}

/**
 * Joins one or more intermediate Vosk segments (fired on brief breathing or
 * thinking pauses, or across a multi-minute offline audio recording) into a
 * single complete utterance with ranked N-best alternatives, pause-aware
 * sentence/paragraph punctuation, boundary-echo deduplication and continuous
 * live partial previews.
 */
class EmaUtteranceAssembler(
    private val maxAlternatives: Int = 6,
    private val formatPausePunctuation: Boolean = false
) {
    data class Assembled(
        val hypotheses: List<EmaVoskJsonParser.Hypothesis>,
        val segmentCount: Int,
        val wordCount: Int = 0,
        val estimatedWpm: Int = 0
    )

    private data class SegmentEntry(
        val hypotheses: List<EmaVoskJsonParser.Hypothesis>,
        val pauseBeforeMs: Long
    )

    // For very long recordings (20 minutes = dozens of segments), older segments
    // fold into frozenPrefix so memory and beam-merge latency remain strictly O(1).
    private var frozenPrefix: EmaVoskJsonParser.Hypothesis? = null
    private var frozenSegmentCount = 0
    private val activeSegments = ArrayList<SegmentEntry>()
    private val currentSegmentWordConf = LinkedHashMap<String, Float>()
    private var livePartialText = ""
    private var livePartialConf = 0f
    private var lastSegmentEndSec = -1f

    /** Feed a streaming partial JSON frame; returns the full utterance preview so far. */
    fun onPartial(partialJson: String): EmaVoskJsonParser.PartialDecode {
        val parsed = EmaVoskJsonParser.parsePartial(partialJson)
        for (w in parsed.words) {
            if (w.confidence in 0f..1f) {
                val key = w.word.lowercase()
                val prev = currentSegmentWordConf[key]
                currentSegmentWordConf[key] = if (prev == null) w.confidence else (0.4f * prev + 0.6f * w.confidence)
            }
        }
        livePartialText = parsed.text
        livePartialConf = parsed.confidence
        val prefix = committedBestText()
        val combined = when {
            prefix.isEmpty() -> parsed.text
            parsed.text.isEmpty() -> prefix
            else -> joinSegmentText(prefix, parsed.text, pauseMs = 0L, punctuate = false)
        }
        val conf = when {
            parsed.text.isNotEmpty() -> parsed.confidence
            activeSegments.isNotEmpty() -> activeSegments.last().hypotheses.first().confidence
            frozenPrefix != null -> frozenPrefix!!.confidence
            else -> 0f
        }
        return EmaVoskJsonParser.PartialDecode(combined, conf, parsed.words)
    }

    /**
     * Feed a finalized intermediate segment JSON (`acceptWaveForm == true`).
     * Returns both the newly committed segment hypotheses and the full preview.
     */
    fun onSegmentWithHypotheses(resultJson: String, explicitPauseMs: Long = 0L): List<EmaVoskJsonParser.Hypothesis> {
        val parsed = EmaVoskJsonParser.parseResult(
            json = resultJson,
            observedWordConf = currentSegmentWordConf,
            maxAlternatives = maxAlternatives
        )
        currentSegmentWordConf.clear()
        livePartialText = ""
        livePartialConf = 0f
        if (parsed.isEmpty()) return emptyList()

        val topWords = parsed.first().words
        val inferredPauseMs = if (explicitPauseMs > 0L) {
            explicitPauseMs
        } else if (lastSegmentEndSec >= 0f && topWords.isNotEmpty() && topWords.first().startSec > lastSegmentEndSec) {
            ((topWords.first().startSec - lastSegmentEndSec) * 1_000f).toLong()
        } else {
            0L
        }
        if (topWords.isNotEmpty() && topWords.last().endSec > 0f) {
            lastSegmentEndSec = topWords.last().endSec
        }

        activeSegments += SegmentEntry(parsed, inferredPauseMs)
        compactOldSegmentsIfNeeded()
        return parsed
    }

    /**
     * Feed a finalized intermediate segment JSON (`acceptWaveForm == true`).
     * Returns the full accumulated primary transcript so far.
     */
    fun onSegment(resultJson: String, explicitPauseMs: Long = 0L): String {
        onSegmentWithHypotheses(resultJson, explicitPauseMs)
        return committedBestText()
    }

    /** Current best text combining all committed segments and any live partial. */
    fun currentPreview(): String {
        val prefix = committedBestText()
        return when {
            prefix.isEmpty() -> livePartialText
            livePartialText.isEmpty() -> prefix
            else -> joinSegmentText(prefix, livePartialText, pauseMs = 0L, punctuate = false)
        }
    }

    /** True once any non-blank word has been committed or seen in the current partial. */
    fun hasAnyText(): Boolean =
        frozenPrefix != null || activeSegments.isNotEmpty() || livePartialText.isNotBlank()

    /** Total number of committed segments so far. */
    fun committedSegmentCount(): Int = frozenSegmentCount + activeSegments.size

    /**
     * Commit the decoder's `finalResult` JSON and return beam-merged N-best
     * hypotheses across the entire utterance.
     */
    fun finish(finalJson: String, explicitPauseMs: Long = 0L): Assembled {
        val tail = EmaVoskJsonParser.parseResult(
            json = finalJson,
            observedWordConf = currentSegmentWordConf,
            maxAlternatives = maxAlternatives
        )
        val entries = ArrayList<SegmentEntry>(activeSegments.size + 2)
        frozenPrefix?.let { entries += SegmentEntry(listOf(it), 0L) }
        entries.addAll(activeSegments)
        val totalSegCount: Int
        if (tail.isNotEmpty()) {
            val tailWords = tail.first().words
            val pause = if (explicitPauseMs > 0L) {
                explicitPauseMs
            } else if (lastSegmentEndSec >= 0f && tailWords.isNotEmpty() && tailWords.first().startSec > lastSegmentEndSec) {
                ((tailWords.first().startSec - lastSegmentEndSec) * 1_000f).toLong()
            } else {
                0L
            }
            entries += SegmentEntry(tail, pause)
            totalSegCount = frozenSegmentCount + activeSegments.size + 1
        } else if (livePartialText.isNotBlank()) {
            entries += SegmentEntry(
                listOf(
                    EmaVoskJsonParser.Hypothesis(
                        text = livePartialText,
                        confidence = max(0.35f, livePartialConf * 0.85f),
                        rawScore = livePartialConf.toDouble()
                    )
                ),
                explicitPauseMs
            )
            totalSegCount = frozenSegmentCount + activeSegments.size + 1
        } else {
            totalSegCount = frozenSegmentCount + activeSegments.size
        }

        currentSegmentWordConf.clear()
        livePartialText = ""
        livePartialConf = 0f

        if (entries.isEmpty()) return Assembled(emptyList(), 0, 0, 0)
        if (entries.size == 1) {
            val top = entries[0].hypotheses.take(maxAlternatives)
            val primary = top.firstOrNull()
            val wc = primary?.let { countWords(it.text) } ?: 0
            val wpm = primary?.let { EmaVoskJsonParser.estimateWordsPerMinute(it.words) } ?: 0
            return Assembled(top, max(1, totalSegCount), wc, wpm)
        }

        val beamLimit = max(maxAlternatives, 6)
        var beam = entries[0].hypotheses.take(beamLimit)
        for (segIdx in 1 until entries.size) {
            val entry = entries[segIdx]
            val nextSeg = entry.hypotheses.take(min(4, beamLimit))
            val merged = LinkedHashMap<String, EmaVoskJsonParser.Hypothesis>()
            for (prefix in beam) {
                val pWords = max(1, countWords(prefix.text))
                for (suffix in nextSeg) {
                    val sWords = max(1, countWords(suffix.text))
                    val joinedText = joinSegmentText(
                        left = prefix.text,
                        right = suffix.text,
                        pauseMs = entry.pauseBeforeMs,
                        punctuate = formatPausePunctuation
                    )
                    val totalWords = pWords + sWords
                    val weightedConf = ((prefix.confidence * pWords + suffix.confidence * sWords) / totalWords)
                        .coerceIn(0.05f, 0.99f)
                    // Cap retained word-timing array on huge multi-minute transcripts.
                    val mergedWords = if (prefix.words.size + suffix.words.size <= 600) {
                        prefix.words + suffix.words
                    } else {
                        suffix.words
                    }
                    val candidate = EmaVoskJsonParser.Hypothesis(
                        text = joinedText,
                        confidence = weightedConf,
                        rawScore = prefix.rawScore + suffix.rawScore,
                        words = mergedWords,
                        unknownCount = prefix.unknownCount + suffix.unknownCount
                    )
                    val key = joinedText.lowercase()
                    val existing = merged[key]
                    if (existing == null || candidate.confidence > existing.confidence) {
                        merged[key] = candidate
                    }
                }
            }
            beam = merged.values
                .sortedWith(
                    compareByDescending<EmaVoskJsonParser.Hypothesis> { it.confidence }
                        .thenByDescending { it.rawScore }
                )
                .take(beamLimit)
        }

        val top = beam.take(maxAlternatives)
        val primary = top.firstOrNull()
        val wc = primary?.let { countWords(it.text) } ?: 0
        val wpm = primary?.let { EmaVoskJsonParser.estimateWordsPerMinute(it.words) } ?: 0
        return Assembled(top, totalSegCount, wc, wpm)
    }

    fun reset() {
        frozenPrefix = null
        frozenSegmentCount = 0
        activeSegments.clear()
        currentSegmentWordConf.clear()
        livePartialText = ""
        livePartialConf = 0f
        lastSegmentEndSec = -1f
    }

    private fun compactOldSegmentsIfNeeded() {
        while (activeSegments.size > MAX_ACTIVE_BEAM_SEGMENTS) {
            val oldest = activeSegments.removeAt(0)
            val best = oldest.hypotheses.first()
            val cur = frozenPrefix
            frozenPrefix = if (cur == null) {
                best
            } else {
                val pWords = max(1, countWords(cur.text))
                val sWords = max(1, countWords(best.text))
                val joined = joinSegmentText(cur.text, best.text, oldest.pauseBeforeMs, formatPausePunctuation)
                val conf = ((cur.confidence * pWords + best.confidence * sWords) / (pWords + sWords))
                    .coerceIn(0.05f, 0.99f)
                EmaVoskJsonParser.Hypothesis(
                    text = joined,
                    confidence = conf,
                    rawScore = cur.rawScore + best.rawScore,
                    words = emptyList(), // Reclaim per-word list on deeply archived prefix segments.
                    unknownCount = cur.unknownCount + best.unknownCount
                )
            }
            frozenSegmentCount++
        }
    }

    private fun committedBestText(): String {
        var acc = frozenPrefix?.text.orEmpty()
        for (entry in activeSegments) {
            val segText = entry.hypotheses.first().text
            acc = if (acc.isEmpty()) {
                segText
            } else {
                joinSegmentText(acc, segText, entry.pauseBeforeMs, formatPausePunctuation)
            }
        }
        return acc
    }

    companion object {
        private const val MAX_ACTIVE_BEAM_SEGMENTS = 8
        private const val SENTENCE_PAUSE_MS = 950L
        private const val PARAGRAPH_PAUSE_MS = 2_400L

        /** Words that naturally double across a pause ("no, no", "bye bye") and should not be deduplicated. */
        private val ALLOWED_BOUNDARY_DOUBLES = setOf("no", "yes", "bye", "very", "so", "really", "wait")

        /** Words ending a clause that show the speaker is continuing the same sentence across a pause. */
        private val OPEN_CLAUSE_TAILS = setOf(
            "and", "or", "but", "so", "because", "to", "for", "with", "on", "in",
            "at", "from", "by", "of", "the", "a", "an", "my", "our", "your", "that", "which", "who"
        )

        internal fun joinSegmentText(
            left: String,
            right: String,
            pauseMs: Long = 0L,
            punctuate: Boolean = false
        ): String {
            val a = left.trim()
            var b = right.trim()
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a

            // Strip accidental single-word boundary echo when pause was negligible ("open youtube" + "youtube and...").
            if (pauseMs < 250L) {
                val lastSpace = a.lastIndexOf(' ')
                val lastWord = (if (lastSpace >= 0) a.substring(lastSpace + 1) else a)
                    .trimEnd('.', ',', '!', '?', ';', ':').lowercase()
                val firstSpace = b.indexOf(' ')
                val firstWord = (if (firstSpace >= 0) b.substring(0, firstSpace) else b).lowercase()
                if (lastWord.length >= 3 && lastWord == firstWord && lastWord !in ALLOWED_BOUNDARY_DOUBLES) {
                    b = if (firstSpace >= 0) b.substring(firstSpace + 1).trimStart() else ""
                    if (b.isEmpty()) return a
                }
            }

            if (punctuate) {
                val lastChar = a.last()
                val alreadyPunctuated = lastChar == '.' || lastChar == '!' || lastChar == '?' || lastChar == '\n'
                val tailWord = a.substringAfterLast(' ').lowercase()
                if (pauseMs >= PARAGRAPH_PAUSE_MS && tailWord !in OPEN_CLAUSE_TAILS) {
                    return if (alreadyPunctuated) "$a\n\n$b" else "$a.\n\n$b"
                }
                if (pauseMs >= SENTENCE_PAUSE_MS && !alreadyPunctuated && tailWord !in OPEN_CLAUSE_TAILS) {
                    return "$a. $b"
                }
            }
            return "$a $b"
        }

        private fun countWords(text: String): Int {
            var count = 0
            var inWord = false
            for (ch in text) {
                if (ch.isWhitespace()) {
                    inWord = false
                } else if (!inWord) {
                    inWord = true
                    count++
                }
            }
            return count
        }
    }
}
