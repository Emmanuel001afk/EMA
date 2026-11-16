package com.ema.assistant

import kotlin.math.max
import kotlin.math.min

/**
 * Domain-aware contextual N-best hypothesis rescorer for EMA's speech
 * recognition stack.
 *
 * Acoustic models hear phonemes; EMA knows what lives on this phone and what a
 * valid request looks like. Given a ranked N-best list from [EmaVoskJsonParser]
 * (or from an Android platform recognizer), [EmaSpeechRescorer]:
 *  1. Normalizes each candidate through [EmaInverseTextNormalizer].
 *  2. Combines calibrated acoustic confidence with owner-taught shortcuts,
 *     owner aliases, learned links, installed app labels, contact names (in
 *     contact slots) and deterministic command/knowledge parseability.
 *  3. Returns deduplicated, ranked hypotheses with calibrated `[0.05, 0.99]`
 *     confidences.
 *
 * Pure Kotlin, zero Android runtime dependencies, fully unit tested.
 */
object EmaSpeechRescorer {

    data class RankedHypothesis(
        val text: String,
        val rawText: String,
        val confidence: Float,
        val combinedScore: Float
    )

    data class RescoredResult(
        val hypotheses: List<String>,
        val confidenceScores: FloatArray,
        val rawHypotheses: List<String>,
        val ranked: List<RankedHypothesis>
    ) {
        val primary: String? get() = hypotheses.firstOrNull()
        val primaryConfidence: Float get() = confidenceScores.firstOrNull() ?: 0f
    }

    // Small rank preference keeps the acoustic model's own #1 choice on exact ties.
    private const val RANK_DECAY = 0.015f

    // Additive domain evidence weights (bounded so genuine speech always governs).
    private const val EXACT_SHORTCUT_BONUS = 0.60f
    private const val CONTAINED_SHORTCUT_BONUS = 0.30f
    private const val LEARNED_PHRASE_BONUS = 0.32f
    private const val OWNER_ALIAS_BONUS = 0.24f
    private const val INSTALLED_APP_BONUS = 0.28f
    private const val CONTACT_SLOT_BONUS = 0.22f
    private const val PARSEABLE_COMMAND_BONUS = 0.18f
    private const val PARSEABLE_KNOWLEDGE_BONUS = 0.18f
    private const val LEXICON_INTENT_BONUS = 0.07f

    /**
     * Normalize and rescore N-best decoder hypotheses for EMA's offline engine.
     */
    fun rescore(
        decoded: List<EmaVoskJsonParser.Hypothesis>,
        context: EmaSpeechContext = EmaSpeechContext.EMPTY,
        profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND,
        maxResults: Int = 5
    ): RescoredResult {
        if (decoded.isEmpty()) {
            return RescoredResult(emptyList(), FloatArray(0), emptyList(), emptyList())
        }

        val prep = PreparedContext.from(context)
        val byNormalizedKey = LinkedHashMap<String, RankedHypothesis>()
        val rawDistinct = LinkedHashSet<String>()

        decoded.forEachIndexed { rankIndex, hyp ->
            val rawClean = hyp.text.trim()
            if (rawClean.isEmpty()) return@forEachIndexed
            rawDistinct += rawClean

            val normalized = EmaInverseTextNormalizer.normalize(rawClean, profile, context)
            if (normalized.isBlank()) return@forEachIndexed

            val domainBonus = if (profile == EmaRecognitionProfile.DICTATION) {
                dictationQualityAdjustment(normalized)
            } else {
                commandDomainScore(normalized, prep) + fluencyAdjustment(normalized, hyp.unknownCount)
            }

            val acoustic = hyp.confidence.coerceIn(0.05f, 0.99f)
            val rankPrior = -(rankIndex * RANK_DECAY).coerceAtMost(0.12f)
            val combined = acoustic + rankPrior + domainBonus
            val boostedConfidence = (acoustic + (domainBonus * 0.35f).coerceIn(-0.20f, 0.22f))
                .coerceIn(0.05f, 0.99f)

            val candidate = RankedHypothesis(
                text = normalized,
                rawText = rawClean,
                confidence = boostedConfidence,
                combinedScore = combined
            )

            val dedupKey = normalized.lowercase()
            val prev = byNormalizedKey[dedupKey]
            if (prev == null || candidate.combinedScore > prev.combinedScore) {
                byNormalizedKey[dedupKey] = candidate
            }
        }

        if (byNormalizedKey.isEmpty()) {
            return RescoredResult(emptyList(), FloatArray(0), emptyList(), emptyList())
        }

        val limit = maxResults.coerceIn(1, 10)
        val sorted = byNormalizedKey.values
            .sortedWith(
                compareByDescending<RankedHypothesis> { it.combinedScore }
                    .thenByDescending { it.confidence }
            )
            .take(limit)

        val texts = ArrayList<String>(sorted.size + 2)
        val scores = ArrayList<Float>(sorted.size + 2)
        for (item in sorted) {
            texts += item.text
            scores += item.confidence
        }

        // Preserve distinct raw runner-ups when room remains so EmaLearning's
        // mishearing tracker can still inspect un-rewritten alternatives.
        if (texts.size < limit) {
            val seen = texts.mapTo(HashSet()) { it.lowercase() }
            for (raw in rawDistinct) {
                if (texts.size >= limit) break
                if (seen.add(raw.lowercase())) {
                    texts += raw
                    val floor = ((scores.lastOrNull() ?: 0.30f) * 0.75f).coerceIn(0.05f, 0.90f)
                    scores += floor
                }
            }
        }

        return RescoredResult(
            hypotheses = texts,
            confidenceScores = scores.toFloatArray(),
            rawHypotheses = rawDistinct.take(limit),
            ranked = sorted
        )
    }

    /**
     * Normalize and rescore a flat string N-best list (such as Android's
     * `SpeechRecognizer.RESULTS_RECOGNITION`) using the same full pipeline as
     * EMA's offline decoder.
     */
    fun rescoreStrings(
        candidates: List<String>?,
        confidenceScores: FloatArray? = null,
        context: EmaSpeechContext = EmaSpeechContext.EMPTY,
        profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND,
        maxResults: Int = 5
    ): RescoredResult {
        val list = candidates.orEmpty()
        if (list.isEmpty()) {
            return RescoredResult(emptyList(), FloatArray(0), emptyList(), emptyList())
        }
        val hypotheses = ArrayList<EmaVoskJsonParser.Hypothesis>(list.size)
        list.forEachIndexed { idx, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            val supplied = confidenceScores?.getOrNull(idx)?.takeIf { it.isFinite() && it > 0f }
            val conf = supplied?.coerceIn(0.05f, 0.99f) ?: (0.82f - idx * 0.03f).coerceAtLeast(0.35f)
            hypotheses += EmaVoskJsonParser.Hypothesis(
                text = trimmed,
                confidence = conf,
                rawScore = conf.toDouble()
            )
        }
        return rescore(hypotheses, context, profile, maxResults)
    }

    /**
     * Select the best hypothesis from a platform or offline `RESULTS_RECOGNITION`
     * list, combining optional [confidenceScores] with [context].
     */
    fun chooseBest(
        results: List<String>?,
        confidenceScores: FloatArray? = null,
        context: EmaSpeechContext = EmaSpeechContext.EMPTY
    ): String? {
        val cleaned = results.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return null
        if (cleaned.size == 1) return cleaned[0]

        val prep = PreparedContext.from(context)
        val hasAnyContext = prep.hasVocabulary

        // Without context or meaningful acoustic differentiation, keep the recognizer's first choice.
        if (!hasAnyContext && (confidenceScores == null || confidenceScores.isEmpty())) {
            return cleaned[0]
        }

        var bestIndex = 0
        var bestScore = Float.NEGATIVE_INFINITY
        cleaned.forEachIndexed { idx, text ->
            val rawConf = confidenceScores?.getOrNull(idx)?.takeIf { it.isFinite() && it > 0f }
            val acoustic = rawConf?.coerceIn(0.05f, 0.99f) ?: (0.80f - idx * 0.03f).coerceAtLeast(0.40f)
            val norm = EmaAppResolver.normalize(text)
            var bonus = 0f

            // Owner shortcuts outrank partial container sentences (preserves shortcut biasing contract).
            when {
                norm in prep.shortcuts -> bonus += EXACT_SHORTCUT_BONUS
                prep.shortcuts.any { s -> containsWholePhrase(norm, s) } -> bonus += CONTAINED_SHORTCUT_BONUS
            }
            if (norm in prep.learnedPhrases) bonus += LEARNED_PHRASE_BONUS
            if (prep.ownerAliases.any { a -> containsWholePhrase(norm, a) }) bonus += OWNER_ALIAS_BONUS
            if (prep.installedApps.any { a -> containsWholePhrase(norm, a) }) bonus += INSTALLED_APP_BONUS
            if (matchesContactInSlot(norm, prep.contacts)) bonus += CONTACT_SLOT_BONUS

            // When choosing among close acoustic candidates, prefer wording EMA's deterministic grammar understands.
            if (idx == 0 || rawConf != null || hasAnyContext) {
                bonus += understandabilityBonus(text) * 0.55f
            }

            val total = acoustic - (idx * RANK_DECAY) + bonus
            if (total > bestScore) {
                bestScore = total
                bestIndex = idx
            }
        }
        return cleaned[bestIndex]
    }

    private fun commandDomainScore(text: String, prep: PreparedContext): Float {
        val norm = EmaAppResolver.normalize(text)
        if (norm.isEmpty()) return 0f
        var bonus = 0f

        when {
            norm in prep.shortcuts -> bonus += EXACT_SHORTCUT_BONUS
            prep.shortcuts.any { s -> containsWholePhrase(norm, s) } -> bonus += CONTAINED_SHORTCUT_BONUS
        }
        if (norm in prep.learnedPhrases) bonus += LEARNED_PHRASE_BONUS
        if (prep.ownerAliases.any { a -> containsWholePhrase(norm, a) }) bonus += OWNER_ALIAS_BONUS
        if (prep.installedApps.any { a -> containsWholePhrase(norm, a) }) bonus += INSTALLED_APP_BONUS
        if (matchesContactInSlot(norm, prep.contacts)) bonus += CONTACT_SLOT_BONUS

        bonus += understandabilityBonus(text)
        return bonus
    }

    private fun understandabilityBonus(text: String): Float {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0f
        val lower = trimmed.lowercase()

        // 1. Literal device commands & structured device settings ("turn on flashlight", "set brightness to 75%").
        if (runCatching { EmaSystemCommandVocabulary.recognises(lower) }.getOrDefault(false)) {
            return PARSEABLE_COMMAND_BONUS
        }
        if (runCatching { EmaSystemCommandVocabulary.parseDeviceSetting(trimmed) != null }.getOrDefault(false)) {
            return PARSEABLE_COMMAND_BONUS
        }

        // 2. Deterministic offline calculations, conversions & date answers.
        if (runCatching { EmaKnowledge.answer(trimmed) != null }.getOrDefault(false)) {
            return PARSEABLE_KNOWLEDGE_BONUS
        }

        // 3. Deterministic command grammar (alarms, timers, reminders, calls, messages, media, apps, search).
        val parsed = runCatching { EmaCommandGrammar.interpret(trimmed) }.getOrNull()
        if (parsed != null && isWellFormedCommand(parsed)) {
            return PARSEABLE_COMMAND_BONUS
        }

        // 4. Recognized opening intent verb from the lexicon.
        val firstWord = EmaLexicon.stripLeadings(lower).split(' ').firstOrNull { it.isNotBlank() }
        if (firstWord != null && EmaLexicon.intentForVerb(firstWord) != null) {
            return LEXICON_INTENT_BONUS
        }
        return 0f
    }

    /** Ensure time-bearing commands actually carry a parseable time/duration before awarding full bonus. */
    private fun isWellFormedCommand(command: EmaCommand): Boolean = when (command) {
        is EmaCommand.SetAlarm -> EmaAlarmRequest.parse(command.timeText) != null ||
            EmaTimeParser.parse(command.timeText) != null
        is EmaCommand.SetTimer -> EmaTimeParser.parseDuration(command.durationText) != null
        is EmaCommand.SetReminder -> command.text.isNotBlank() &&
            (command.timeText.isBlank() || EmaTimeParser.parse(command.timeText) != null)
        is EmaCommand.CallContact -> command.name.isNotBlank()
        is EmaCommand.SendMessage -> command.recipient.isNotBlank()
        is EmaCommand.OpenApp -> command.app.isNotBlank()
        is EmaCommand.PlayMedia -> command.query.isNotBlank()
        is EmaCommand.SearchInApp -> command.query.isNotBlank()
        else -> true
    }

    private fun fluencyAdjustment(text: String, unknownCount: Int): Float {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return -0.30f
        var adj = -(unknownCount * 0.06f).coerceAtMost(0.24f)

        // Penalize adjacent stuttered identical words ("open open", "the the").
        for (i in 1 until tokens.size) {
            if (tokens[i].equals(tokens[i - 1], ignoreCase = true)) {
                adj -= 0.08f
            }
        }
        // Penalize stray single-character alphabetic tokens other than "I" and "a"
        // (unless part of explicit spelling).
        if (!text.contains("package spelling", ignoreCase = true)) {
            val straySingles = tokens.count { tok ->
                tok.length == 1 && tok[0].isLetter() && tok != "I" && !tok.equals("a", ignoreCase = true)
            }
            adj -= min(0.20f, straySingles * 0.06f)
        }
        return max(-0.30f, adj)
    }

    private fun dictationQualityAdjustment(text: String): Float {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        var adj = 0f
        for (i in 1 until tokens.size) {
            if (tokens[i].equals(tokens[i - 1], ignoreCase = true)) adj -= 0.07f
        }
        return adj
    }

    private fun matchesContactInSlot(normalizedUtterance: String, normalizedContacts: Set<String>): Boolean {
        if (normalizedContacts.isEmpty()) return false
        val isContactUtterance = Regex("\\b(?:call|dial|phone|ring|message|text|tell|inform|whatsapp|send)\\b")
            .containsMatchIn(normalizedUtterance)
        if (!isContactUtterance) return false
        return normalizedContacts.any { c -> c.length >= 3 && containsWholePhrase(normalizedUtterance, c) }
    }

    private fun containsWholePhrase(haystack: String, needle: String): Boolean {
        if (needle.isEmpty() || haystack.length < needle.length) return false
        if (haystack == needle) return true
        var from = 0
        while (true) {
            val idx = haystack.indexOf(needle, from)
            if (idx < 0) return false
            val beforeOk = idx == 0 || haystack[idx - 1] == ' '
            val end = idx + needle.length
            val afterOk = end == haystack.length || haystack[end] == ' '
            if (beforeOk && afterOk) return true
            from = idx + 1
        }
    }

    private class PreparedContext(
        val shortcuts: Set<String>,
        val ownerAliases: Set<String>,
        val learnedPhrases: Set<String>,
        val installedApps: Set<String>,
        val contacts: Set<String>
    ) {
        val hasVocabulary: Boolean =
            shortcuts.isNotEmpty() || ownerAliases.isNotEmpty() ||
                learnedPhrases.isNotEmpty() || installedApps.isNotEmpty() || contacts.isNotEmpty()

        companion object {
            fun from(context: EmaSpeechContext): PreparedContext {
                fun normSet(items: List<String>, minLen: Int = 2): Set<String> {
                    if (items.isEmpty()) return emptySet()
                    val out = LinkedHashSet<String>(items.size)
                    for (item in items) {
                        val n = EmaAppResolver.normalize(item)
                        if (n.length >= minLen) out += n
                    }
                    return out
                }
                return PreparedContext(
                    shortcuts = normSet(context.shortcuts, minLen = 2),
                    ownerAliases = normSet(context.ownerAliases, minLen = 2),
                    learnedPhrases = normSet(context.learnedPhrases, minLen = 2),
                    installedApps = normSet(context.installedApps, minLen = 2),
                    contacts = normSet(context.contactNames, minLen = 3)
                )
            }
        }
    }
}
