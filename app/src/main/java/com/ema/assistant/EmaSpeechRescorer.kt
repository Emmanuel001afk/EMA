package com.ema.assistant

import kotlin.math.max
import kotlin.math.min

/**
 * Domain-aware contextual and Minimum-Bayes-Risk (MBR/ROVER) N-best hypothesis
 * rescorer for EMA's speech recognition stack.
 *
 * Acoustic models hear phonemes; EMA knows what lives on this phone, what is
 * on screen, what the owner has taught it and what a well-formed request looks
 * like. Given a ranked N-best list from [EmaVoskJsonParser] (or an Android
 * platform recognizer), [EmaSpeechRescorer]:
 *  1. Computes posterior-weighted word-level consensus across the N-best list
 *     (ROVER/MBR smoothing so agreed-upon words outrank single-path flukes).
 *  2. Normalizes each candidate through [EmaInverseTextNormalizer].
 *  3. Injects verified owner-confirmed mishearing corrections when a lattice
 *     candidate matches a known personal correction cleanly.
 *  4. Scores each candidate against shortcuts, owner aliases, learned links,
 *     installed app labels, contact slots, visible screen controls and
 *     profile-specific command/confirmation/search/dictation validity.
 *  5. Returns deduplicated, ranked hypotheses with calibrated `[0.05, 0.99]`
 *     confidences and runner-up margin metadata.
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
        val marginOverRunnerUp: Float
            get() = if (ranked.size >= 2) {
                (ranked[0].combinedScore - ranked[1].combinedScore).coerceAtLeast(0f)
            } else if (ranked.size == 1) {
                ranked[0].confidence
            } else {
                0f
            }
        val isHighConfidence: Boolean
            get() = primaryConfidence >= 0.72f && (ranked.size == 1 || marginOverRunnerUp >= 0.08f)
    }

    // Small rank preference keeps the acoustic model's own #1 choice on exact ties.
    private const val RANK_DECAY = 0.015f

    // Additive domain evidence weights (bounded so genuine speech always governs).
    private const val EXACT_SHORTCUT_BONUS = 0.60f
    private const val CONTAINED_SHORTCUT_BONUS = 0.30f
    private const val LEARNED_REPLACEMENT_BONUS = 0.48f
    private const val LEARNED_PHRASE_BONUS = 0.32f
    private const val CONFIRMATION_REPLY_BONUS = 0.34f
    private const val INSTALLED_APP_BONUS = 0.28f
    private const val SCREEN_LABEL_BONUS = 0.26f
    private const val OWNER_ALIAS_BONUS = 0.24f
    private const val CONTACT_SLOT_BONUS = 0.22f
    private const val PARSEABLE_COMMAND_BONUS = 0.22f
    private const val PARSEABLE_KNOWLEDGE_BONUS = 0.20f
    private const val MAX_MBR_CONSENSUS_BONUS = 0.08f
    private const val LEXICON_INTENT_BONUS = 0.06f

    private val CONFIRMATION_EXACT_REPLIES = setOf(
        "yes", "yeah", "yep", "sure", "okay", "ok", "confirm", "confirmed",
        "yes please", "yes go ahead", "go ahead", "do it", "send it", "call",
        "call now", "proceed", "continue", "that's right", "correct", "right",
        "no", "nope", "cancel", "stop", "don't", "do not", "never mind",
        "cancel it", "stop it", "abort", "not now", "wrong one",
        "sim 1", "sim 2", "first", "second", "third",
        "the first one", "the second one", "the third one",
        "first one", "second one", "third one",
        "option 1", "option 2", "option 3", "1", "2", "3"
    )

    private val CONFIRMATION_LEAD_WORDS = setOf(
        "yes", "yeah", "sure", "confirm", "no", "cancel", "stop", "option", "sim", "use", "pick", "choose"
    )

    /**
     * Normalize and rescore N-best decoder hypotheses for EMA's offline engine.
     */
    fun rescore(
        decoded: List<EmaVoskJsonParser.Hypothesis>,
        context: EmaSpeechContext = EmaSpeechContext.EMPTY,
        profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND,
        maxResults: Int = 5,
        enableFormatting: Boolean = true,
        maskOffensiveWords: Boolean = false
    ): RescoredResult {
        if (decoded.isEmpty()) {
            return RescoredResult(emptyList(), FloatArray(0), emptyList(), emptyList())
        }

        val prep = PreparedContext.from(context)
        val wordPosteriors = computeWordConsensusPosteriors(decoded)
        val byNormalizedKey = LinkedHashMap<String, RankedHypothesis>()
        val rawDistinct = LinkedHashSet<String>()

        fun considerCandidate(candidate: RankedHypothesis) {
            val dedupKey = candidate.text.lowercase()
            val prev = byNormalizedKey[dedupKey]
            if (prev == null || candidate.combinedScore > prev.combinedScore) {
                byNormalizedKey[dedupKey] = candidate
            }
        }

        decoded.forEachIndexed { rankIndex, hyp ->
            val rawClean = hyp.text.trim()
            if (rawClean.isEmpty()) return@forEachIndexed
            rawDistinct += rawClean

            val normalized = EmaInverseTextNormalizer.normalize(
                raw = rawClean,
                profile = profile,
                context = context,
                enableFormatting = enableFormatting,
                maskOffensiveWords = maskOffensiveWords
            )
            if (normalized.isBlank()) return@forEachIndexed

            val consensusBonus = if (decoded.size >= 2) {
                mbrConsensusBonus(rawClean, wordPosteriors)
            } else {
                0f
            }

            val domainBonus = when (profile) {
                EmaRecognitionProfile.DICTATION -> dictationQualityAdjustment(normalized, hyp.unknownCount)
                EmaRecognitionProfile.WEB_SEARCH -> webSearchDomainScore(normalized, prep, hyp.unknownCount)
                EmaRecognitionProfile.CONFIRMATION -> confirmationDomainScore(normalized, prep, hyp.unknownCount)
                EmaRecognitionProfile.COMMAND -> commandDomainScore(normalized, prep) +
                    fluencyAdjustment(normalized, hyp.unknownCount)
            }

            val totalBonus = domainBonus + consensusBonus
            val acoustic = hyp.confidence.coerceIn(0.05f, 0.99f)
            val rankPrior = -(rankIndex * RANK_DECAY).coerceAtMost(0.12f)
            val combined = acoustic + rankPrior + totalBonus
            val boostedConfidence = (acoustic + (totalBonus * 0.35f).coerceIn(-0.20f, 0.22f))
                .coerceIn(0.05f, 0.99f)

            considerCandidate(
                RankedHypothesis(
                    text = normalized,
                    rawText = rawClean,
                    confidence = boostedConfidence,
                    combinedScore = combined
                )
            )

            // Owner-verified personal speech correction ("open for me" -> "open Phormi"):
            // applied only to whole-utterance commands, never inside dictation or message bodies.
            if (profile == EmaRecognitionProfile.COMMAND && prep.learnedReplacements.isNotEmpty()) {
                val matchedMeaning = prep.learnedReplacements[EmaAppResolver.normalize(rawClean)]
                    ?: prep.learnedReplacements[EmaAppResolver.normalize(normalized)]
                if (!matchedMeaning.isNullOrBlank()) {
                    val personalized = if (maskOffensiveWords) {
                        EmaInverseTextNormalizer.normalize(
                            matchedMeaning, profile, EmaSpeechContext.EMPTY,
                            enableFormatting = false, maskOffensiveWords = true
                        )
                    } else {
                        matchedMeaning.trim()
                    }
                    if (personalized.isNotBlank() && !personalized.equals(normalized, ignoreCase = true)) {
                        val personalConf = (acoustic + 0.18f).coerceIn(0.25f, 0.98f)
                        considerCandidate(
                            RankedHypothesis(
                                text = personalized,
                                rawText = rawClean,
                                confidence = personalConf,
                                combinedScore = acoustic + rankPrior + LEARNED_REPLACEMENT_BONUS + consensusBonus
                            )
                        )
                    }
                }
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
        if (texts.size < limit && !maskOffensiveWords) {
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

            when {
                norm in prep.shortcuts -> bonus += EXACT_SHORTCUT_BONUS
                prep.shortcuts.any { s -> containsWholePhrase(norm, s) } -> bonus += CONTAINED_SHORTCUT_BONUS
            }
            if (norm in prep.learnedPhrases || norm in prep.learnedReplacements) bonus += LEARNED_PHRASE_BONUS
            if (prep.ownerAliases.any { a -> containsWholePhrase(norm, a) }) bonus += OWNER_ALIAS_BONUS
            if (prep.installedApps.any { a -> containsWholePhrase(norm, a) }) bonus += INSTALLED_APP_BONUS
            if (matchesScreenLabelInSlot(norm, prep.screenLabels)) bonus += SCREEN_LABEL_BONUS
            if (matchesContactInSlot(norm, prep.contacts)) bonus += CONTACT_SLOT_BONUS

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

    /**
     * Posterior-weighted unigram agreement across the N-best list (bag-of-words
     * Minimum Bayes Risk / ROVER consensus).
     */
    private fun computeWordConsensusPosteriors(
        decoded: List<EmaVoskJsonParser.Hypothesis>
    ): Map<String, Float> {
        if (decoded.size < 2) return emptyMap()
        val mass = HashMap<String, Float>()
        var totalWeight = 0f
        for (hyp in decoded) {
            val w = hyp.confidence.coerceIn(0.05f, 0.99f)
            totalWeight += w
            val uniqueWords = hyp.text.lowercase().split(Regex("\\s+"))
                .filter { it.length >= 2 }
                .toSet()
            for (tok in uniqueWords) {
                mass[tok] = (mass[tok] ?: 0f) + w
            }
        }
        if (totalWeight <= 0f) return emptyMap()
        return mass.mapValues { (_, v) -> (v / totalWeight).coerceIn(0f, 1f) }
    }

    private fun mbrConsensusBonus(rawText: String, wordPosteriors: Map<String, Float>): Float {
        if (wordPosteriors.isEmpty()) return 0f
        val words = rawText.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.isEmpty()) return 0f
        var sum = 0f
        for (w in words) {
            sum += wordPosteriors[w] ?: 0f
        }
        val avgAgreement = sum / words.size
        // Centered around 0.5 so strong multi-path consensus adds up to +0.08f.
        return ((avgAgreement - 0.35f) * 0.12f).coerceIn(0f, MAX_MBR_CONSENSUS_BONUS)
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
        if (matchesScreenLabelInSlot(norm, prep.screenLabels)) bonus += SCREEN_LABEL_BONUS
        if (matchesContactInSlot(norm, prep.contacts)) bonus += CONTACT_SLOT_BONUS

        bonus += understandabilityBonus(text)
        return bonus
    }

    private fun confirmationDomainScore(text: String, prep: PreparedContext, unknownCount: Int): Float {
        val lower = text.trim().lowercase()
        val norm = EmaAppResolver.normalize(text)
        var bonus = fluencyAdjustment(text, unknownCount)
        when {
            lower in CONFIRMATION_EXACT_REPLIES || norm in CONFIRMATION_EXACT_REPLIES ->
                bonus += CONFIRMATION_REPLY_BONUS
            norm.split(' ').firstOrNull() in CONFIRMATION_LEAD_WORDS ->
                bonus += CONFIRMATION_REPLY_BONUS * 0.65f
            prep.contacts.any { c -> norm == c || containsWholePhrase(norm, c) } ->
                bonus += CONTACT_SLOT_BONUS
            prep.installedApps.any { a -> norm == a || containsWholePhrase(norm, a) } ->
                bonus += INSTALLED_APP_BONUS
            else ->
                // Owner may also issue a fresh command instead of answering the question.
                bonus += commandDomainScore(text, prep) * 0.8f
        }
        return bonus
    }

    private fun webSearchDomainScore(text: String, prep: PreparedContext, unknownCount: Int): Float {
        val norm = EmaAppResolver.normalize(text)
        var bonus = fluencyAdjustment(text, unknownCount)
        if (prep.installedApps.any { a -> containsWholePhrase(norm, a) }) bonus += 0.12f
        if (Regex("\\b[a-z0-9-]+\\.(?:com|org|net|edu|gov|io|ai|app|dev|ng)\\b").containsMatchIn(text.lowercase())) {
            bonus += 0.14f
        }
        return bonus
    }

    private fun understandabilityBonus(text: String): Float {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0f
        val lower = trimmed.lowercase()

        if (runCatching { EmaSystemCommandVocabulary.recognises(lower) }.getOrDefault(false)) {
            return PARSEABLE_COMMAND_BONUS
        }
        if (runCatching { EmaSystemCommandVocabulary.parseDeviceSetting(trimmed) != null }.getOrDefault(false)) {
            return PARSEABLE_COMMAND_BONUS
        }
        if (runCatching { EmaKnowledge.answer(trimmed) != null }.getOrDefault(false)) {
            return PARSEABLE_KNOWLEDGE_BONUS
        }
        val parsed = runCatching { EmaCommandGrammar.interpret(trimmed) }.getOrNull()
        if (parsed != null && isWellFormedCommand(parsed)) {
            return PARSEABLE_COMMAND_BONUS
        }
        val firstWord = EmaLexicon.stripLeadings(lower).split(' ').firstOrNull { it.isNotBlank() }
        if (firstWord != null && EmaLexicon.intentForVerb(firstWord) != null) {
            return LEXICON_INTENT_BONUS
        }
        return 0f
    }

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

        for (i in 1 until tokens.size) {
            if (tokens[i].equals(tokens[i - 1], ignoreCase = true)) {
                adj -= 0.08f
            }
        }
        if (!text.contains("package spelling", ignoreCase = true)) {
            val straySingles = tokens.count { tok ->
                tok.length == 1 && tok[0].isLetter() && tok != "I" && !tok.equals("a", ignoreCase = true)
            }
            adj -= min(0.20f, straySingles * 0.06f)
        }
        return max(-0.30f, adj)
    }

    private fun dictationQualityAdjustment(text: String, unknownCount: Int): Float {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        var adj = -(unknownCount * 0.05f).coerceAtMost(0.20f)
        for (i in 1 until tokens.size) {
            if (tokens[i].equals(tokens[i - 1], ignoreCase = true)) adj -= 0.07f
        }
        return adj
    }

    private fun matchesScreenLabelInSlot(normalizedUtterance: String, screenLabels: Set<String>): Boolean {
        if (screenLabels.isEmpty()) return false
        val isScreenAction = Regex("\\b(?:tap|click|press|select|choose|open)\\b")
            .containsMatchIn(normalizedUtterance)
        if (!isScreenAction) return false
        return screenLabels.any { l -> l.length >= 3 && containsWholePhrase(normalizedUtterance, l) }
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
        val contacts: Set<String>,
        val learnedReplacements: Map<String, String>,
        val screenLabels: Set<String>
    ) {
        val hasVocabulary: Boolean =
            shortcuts.isNotEmpty() || ownerAliases.isNotEmpty() ||
                learnedPhrases.isNotEmpty() || installedApps.isNotEmpty() ||
                contacts.isNotEmpty() || learnedReplacements.isNotEmpty() ||
                screenLabels.isNotEmpty()

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
                val replacements = if (context.learnedReplacements.isEmpty()) {
                    emptyMap()
                } else {
                    val m = LinkedHashMap<String, String>(context.learnedReplacements.size)
                    for ((k, v) in context.learnedReplacements) {
                        val nk = EmaAppResolver.normalize(k)
                        val tv = v.trim()
                        if (nk.length >= 2 && tv.isNotEmpty()) m[nk] = tv
                    }
                    m
                }
                val contacts = if (context.contactNames.isEmpty()) {
                    emptySet()
                } else {
                    val out = LinkedHashSet<String>(context.contactNames.size * 2)
                    for (name in context.contactNames) {
                        val full = EmaAppResolver.normalize(name)
                        if (full.length >= 3) out += full
                        // Include first/given name (>= 4 chars) so "call Tunde" gets slot reward
                        // when contact is saved as "Tunde Okafor".
                        val given = full.substringBefore(' ')
                        if (given.length >= 4) out += given
                    }
                    out
                }
                return PreparedContext(
                    shortcuts = normSet(context.shortcuts, minLen = 2),
                    ownerAliases = normSet(context.ownerAliases, minLen = 2),
                    learnedPhrases = normSet(context.learnedPhrases, minLen = 2),
                    installedApps = normSet(context.installedApps, minLen = 2),
                    contacts = contacts,
                    learnedReplacements = replacements,
                    screenLabels = normSet(context.screenLabels, minLen = 3)
                )
            }
        }
    }
}
