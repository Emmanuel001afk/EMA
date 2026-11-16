package com.ema.assistant

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

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
                // Softmax across 5 close phrasing variants ("youtube" vs "you tube")
                // splits probability mass; lift relative to top mass when word conf is strong.
                val wordConf = meanWordConfidence(alt.text, alt.words, observedWordConf)
                val blended = if (wordConf != null) {
                    val relativeToTop = (weights[i] / weights[0]).toFloat()
                    (0.55f * softmaxProb + 0.45f * (wordConf * (0.45f + 0.55f * relativeToTop)))
                } else {
                    val relativeToTop = (weights[i] / weights[0]).toFloat()
                    (0.60f * softmaxProb + 0.40f * (0.80f * relativeToTop))
                }
                val unkPenalty = (alt.unkCount * 0.10f).coerceAtMost(0.35f)
                (blended - unkPenalty).coerceIn(0.05f, 0.99f)
            }
        } else {
            // Rank-decay fallback when no numeric score was present.
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
            // Blend mean with the weakest word so one garbled word lowers confidence honestly.
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
 * thinking pauses) into a single complete utterance with ranked N-best
 * alternatives and continuous live partial previews.
 */
class EmaUtteranceAssembler(
    private val maxAlternatives: Int = 6
) {
    data class Assembled(
        val hypotheses: List<EmaVoskJsonParser.Hypothesis>,
        val segmentCount: Int
    )

    private val committedSegments = ArrayList<List<EmaVoskJsonParser.Hypothesis>>()
    private val currentSegmentWordConf = LinkedHashMap<String, Float>()
    private var livePartialText = ""
    private var livePartialConf = 0f

    /** Feed a streaming partial JSON frame; returns the full utterance preview so far. */
    fun onPartial(partialJson: String): EmaVoskJsonParser.PartialDecode {
        val parsed = EmaVoskJsonParser.parsePartial(partialJson)
        for (w in parsed.words) {
            if (w.confidence in 0f..1f) {
                val key = w.word.lowercase()
                val prev = currentSegmentWordConf[key]
                // Track latest MBR word confidence, gently smoothed if repeated.
                currentSegmentWordConf[key] = if (prev == null) w.confidence else (0.4f * prev + 0.6f * w.confidence)
            }
        }
        livePartialText = parsed.text
        livePartialConf = parsed.confidence
        val prefix = committedBestText()
        val combined = when {
            prefix.isEmpty() -> parsed.text
            parsed.text.isEmpty() -> prefix
            else -> "$prefix ${parsed.text}"
        }
        val conf = when {
            parsed.text.isNotEmpty() -> parsed.confidence
            committedSegments.isNotEmpty() -> committedSegments.last().first().confidence
            else -> 0f
        }
        return EmaVoskJsonParser.PartialDecode(combined, conf, parsed.words)
    }

    /**
     * Feed a finalized intermediate segment JSON (`acceptWaveForm == true`).
     * Returns the full accumulated primary transcript so far.
     */
    fun onSegment(resultJson: String): String {
        val parsed = EmaVoskJsonParser.parseResult(
            json = resultJson,
            observedWordConf = currentSegmentWordConf,
            maxAlternatives = maxAlternatives
        )
        currentSegmentWordConf.clear()
        livePartialText = ""
        livePartialConf = 0f
        if (parsed.isNotEmpty()) {
            committedSegments += parsed
        }
        return committedBestText()
    }

    /** Current best text combining all committed segments and any live partial. */
    fun currentPreview(): String {
        val prefix = committedBestText()
        return when {
            prefix.isEmpty() -> livePartialText
            livePartialText.isEmpty() -> prefix
            else -> "$prefix $livePartialText"
        }
    }

    /** True once any non-blank word has been committed or seen in the current partial. */
    fun hasAnyText(): Boolean = committedSegments.isNotEmpty() || livePartialText.isNotBlank()

    /**
     * Commit the decoder's `finalResult` JSON and return beam-merged N-best
     * hypotheses across the entire utterance.
     */
    fun finish(finalJson: String): Assembled {
        val tail = EmaVoskJsonParser.parseResult(
            json = finalJson,
            observedWordConf = currentSegmentWordConf,
            maxAlternatives = maxAlternatives
        )
        val allSegments = ArrayList<List<EmaVoskJsonParser.Hypothesis>>(committedSegments.size + 1)
        allSegments.addAll(committedSegments)
        if (tail.isNotEmpty()) {
            allSegments += tail
        } else if (livePartialText.isNotBlank()) {
            // Rare edge case: stopListening interrupted before final lattice flush;
            // salvage the last stable partial rather than losing spoken words.
            allSegments += listOf(
                EmaVoskJsonParser.Hypothesis(
                    text = livePartialText,
                    confidence = max(0.35f, livePartialConf * 0.85f),
                    rawScore = livePartialConf.toDouble()
                )
            )
        }
        currentSegmentWordConf.clear()
        livePartialText = ""
        livePartialConf = 0f

        if (allSegments.isEmpty()) return Assembled(emptyList(), 0)
        if (allSegments.size == 1) {
            return Assembled(allSegments[0].take(maxAlternatives), 1)
        }

        // Beam-combine segments cleanly with length-weighted confidence.
        val beamLimit = max(maxAlternatives, 6)
        var beam = allSegments[0].take(beamLimit)
        for (segIdx in 1 until allSegments.size) {
            val nextSeg = allSegments[segIdx].take(min(4, beamLimit))
            val merged = LinkedHashMap<String, EmaVoskJsonParser.Hypothesis>()
            for (prefix in beam) {
                val pWords = max(1, prefix.text.count { it == ' ' } + 1)
                for (suffix in nextSeg) {
                    val sWords = max(1, suffix.text.count { it == ' ' } + 1)
                    val joinedText = joinSegmentText(prefix.text, suffix.text)
                    val totalWords = pWords + sWords
                    val weightedConf = ((prefix.confidence * pWords + suffix.confidence * sWords) / totalWords)
                        .coerceIn(0.05f, 0.99f)
                    val candidate = EmaVoskJsonParser.Hypothesis(
                        text = joinedText,
                        confidence = weightedConf,
                        rawScore = prefix.rawScore + suffix.rawScore,
                        words = prefix.words + suffix.words,
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
        return Assembled(beam.take(maxAlternatives), allSegments.size)
    }

    fun reset() {
        committedSegments.clear()
        currentSegmentWordConf.clear()
        livePartialText = ""
        livePartialConf = 0f
    }

    private fun committedBestText(): String {
        if (committedSegments.isEmpty()) return ""
        var acc = committedSegments[0].first().text
        for (i in 1 until committedSegments.size) {
            acc = joinSegmentText(acc, committedSegments[i].first().text)
        }
        return acc
    }

    private fun joinSegmentText(left: String, right: String): String {
        val a = left.trim()
        val b = right.trim()
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        return "$a $b"
    }
}
