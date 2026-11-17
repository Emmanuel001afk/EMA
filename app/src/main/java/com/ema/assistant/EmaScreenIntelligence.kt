package com.ema.assistant

import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * On-device screen, document, transcript and image-OCR intelligence — EMA's
 * zero-network answer to "Ask about this screen", Live Text entity extraction,
 * article/message summarization, action-item detection and grounded Q&A.
 *
 * Every output is strictly extractive and grounded in the supplied text:
 * EMA NEVER invents facts, numbers, dates or people that do not appear in the
 * source. When a question cannot be answered from the visible content, EMA
 * reports that honestly.
 *
 * Pure Kotlin, zero Android framework dependencies, exhaustively unit tested.
 */
object EmaScreenIntelligence {

    enum class EntityKind(val label: String) {
        OTP_CODE("verification code"),
        PHONE("phone number"),
        EMAIL("email address"),
        URL("link"),
        PRICE("amount"),
        DATE_TIME("date/time"),
        TRACKING_ID("reference/tracking ID"),
        ADDRESS("address")
    }

    data class Entity(
        val kind: EntityKind,
        /** Clean machine-usable value (dialable number, bare URL/email, raw OTP). */
        val value: String,
        /** Short human-readable label. */
        val display: String,
        /** Surrounding snippet where the entity appeared. */
        val context: String = ""
    )

    data class QuickAction(
        /** Compact chip label displayed to the owner. */
        val label: String,
        /** Exact EMA command run when tapped. */
        val command: String
    )

    data class ScreenDigest(
        val summary: String,
        val entities: List<Entity>,
        val actionItems: List<String>,
        val suggestedActions: List<QuickAction>,
        val wordCount: Int
    )

    data class GroundedAnswer(
        val text: String,
        val matched: Boolean,
        val primaryEntity: Entity? = null
    )

    private val STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "then", "else", "when", "at", "by",
        "for", "with", "about", "against", "between", "into", "through", "during", "before",
        "after", "above", "below", "to", "from", "up", "down", "in", "out", "on", "off",
        "over", "under", "again", "further", "once", "here", "there", "where", "why", "how",
        "all", "any", "both", "each", "few", "more", "most", "other", "some", "such", "no",
        "nor", "not", "only", "own", "same", "so", "than", "too", "very", "s", "t", "can",
        "will", "just", "don", "should", "now", "is", "are", "was", "were", "be", "been",
        "being", "have", "has", "had", "do", "does", "did", "of", "it", "its", "this",
        "that", "these", "those", "i", "me", "my", "we", "our", "you", "your", "he", "him",
        "his", "she", "her", "they", "them", "their", "what", "which", "who", "whom",
        "screen", "page", "text", "document", "article", "message", "note", "clipboard",
        "say", "says", "saying", "said", "mention", "mentions", "mentioned", "tell", "show",
        "showing", "does", "find", "look", "please", "ema"
    )

    private val UI_CHROME_NOISE = setOf(
        "back", "navigate up", "more options", "menu", "search", "share", "close", "cancel",
        "ok", "okay", "done", "next", "skip", "home", "profile", "settings", "notifications",
        "like", "comment", "reply", "retweet", "repost", "subscribe", "subscribed", "follow",
        "following", "sponsored", "advertisement", "ad", "open app", "accept all", "reject all",
        "cookie settings", "sign in", "log in", "loading", "refresh", "retry", "send", "edit",
        "delete", "copy", "paste", "select all", "mute", "unmute", "play", "pause"
    )

    private val SALIENCE_CUES = setOf(
        "important", "urgent", "summary", "conclusion", "total", "amount", "due", "deadline",
        "confirmed", "confirmation", "scheduled", "meeting", "appointment", "flight", "gate",
        "departure", "arrival", "delivery", "arriving", "shipped", "approved", "declined",
        "required", "must", "action", "warning", "alert", "note", "key", "result", "decided",
        "agreed", "balance", "invoice", "receipt", "payment", "refund", "expires", "tomorrow",
        "today", "because", "therefore", "however", "overall", "finally"
    )

    // Verification/OTP patterns require explicit authentication context so years/prices never trigger.
    private val OTP_PATTERNS = listOf(
        Regex("(?i)\\b(?:otp|verification|security|login|sign[- ]?in|auth(?:entication)?|confirmation|confirm|one[- ]?time|2fa|passcode|access)\\s*(?:code|pin|number|is|:)?\\s*(?:is\\s*|:|-)?\\s*\\b(\\d{4,8})\\b"),
        Regex("(?i)\\b(\\d{4,8})\\b\\s+is\\s+your\\s+(?:[\\w-]+\\s+){0,3}(?:otp|verification|security|confirmation|login|access|one[- ]?time)\\s*(?:code|pin|number)?\\b"),
        Regex("(?i)\\bcode\\s*[:\\-]\\s*(\\d{4,8})\\b"),
        Regex("(?i)\\buse\\s+(?:code\\s+)?(\\d{5,8})\\s+to\\s+(?:verify|sign\\s+in|log\\s+in|confirm|complete)\\b")
    )

    private val EMAIL_REGEX = Regex("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,10}\\b")
    private val URL_REGEX = Regex(
        "(?i)\\b(?:https?://[^\\s<>\"')\\]]+|www\\.[a-z0-9\\-]+\\.[a-z]{2,}(?:/[^\\s<>\"')\\]]*)?)"
    )
    private val PHONE_REGEX = Regex(
        "(?<![\\w@./])(\\+?\\d{1,3}[\\s.\\-]?)?(?:\\(\\d{2,4}\\)|\\d{2,4})[\\s.\\-]?\\d{3,4}[\\s.\\-]?\\d{3,5}(?!\\d)"
    )
    private val PRICE_REGEX = Regex(
        "(?i)(?:(?:\\$|₦|£|€|¥|₹|USD|NGN|GBP|EUR|CAD|AUD|GHS|KES|ZAR|INR)\\s?\\d[\\d,]*(?:\\.\\d{1,2})?|\\d[\\d,]*(?:\\.\\d{1,2})?\\s?(?:dollars|naira|pounds|euros|cedis|shillings|rand))\\b"
    )
    private val DATE_TIME_REGEX = Regex(
        "(?i)\\b(?:" +
            "(?:mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)(?:,?\\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+\\d{1,2}(?:st|nd|rd|th)?)?(?:\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))?|" +
            "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+\\d{4})?(?:\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))?|" +
            "\\d{1,2}\\s+(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)(?:\\s+\\d{4})?|" +
            "(?:today|tomorrow|tonight)\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)|" +
            "\\b\\d{1,2}:\\d{2}\\s*(?:am|pm)\\b" +
            ")"
    )
    private val TRACKING_REGEX = Regex(
        "(?i)\\b(?:tracking|order|booking|reference|ref|invoice|ticket|flight|pnr)\\s*(?:number|no\\.?|id|#|code)?\\s*[:#\\-]?\\s*([A-Z0-9][A-Z0-9\\-]{4,22})\\b"
    )
    private val ADDRESS_REGEX = Regex(
        "\\b\\d{1,5}\\s+[A-Z][A-Za-z0-9.'\\-]+(?:\\s+[A-Z][A-Za-z0-9.'\\-]+){0,4}\\s+" +
            "(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Drive|Dr|Lane|Ln|Way|Close|Crescent|Court|Ct|Plaza|Highway|Hwy|Expressway)\\b" +
            "(?:,\\s*[A-Z][A-Za-z ]{2,25})?"
    )

    private val ACTION_ITEM_PATTERNS = listOf(
        Regex("(?i)^(?:[-*•]|\\d+[.)]|\\[\\s?])\\s*(?:todo|action|task)?[:\\s]*(.+)$"),
        Regex("(?i)\\b(?:please|kindly)\\s+(?:send|submit|review|confirm|reply|call|email|pay|sign|complete|update|upload|share|check|bring|book|schedule|approve|prepare|finish)\\b"),
        Regex("(?i)\\b(?:need(?:s)?\\s+to|must|have\\s+to|don't\\s+forget\\s+to|remember\\s+to|make\\s+sure\\s+(?:to|you)|action\\s+required|todo|deadline|due\\s+(?:by|on|date)|by\\s+(?:eod|end\\s+of\\s+day|tomorrow|monday|tuesday|wednesday|thursday|friday|\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)))\\b"),
        Regex("(?i)\\b(?:will\\s+send|i'll\\s+send|let's\\s+meet|follow\\s+up\\s+(?:with|on))\\b")
    )

    // ------------------------------------------------- entity extraction

    fun extractEntities(rawText: String, maxPerKind: Int = 4): List<Entity> {
        if (rawText.isBlank()) return emptyList()
        val text = rawText.take(16_000)
        val found = mutableListOf<Entity>()
        val seen = HashSet<String>()

        fun add(kind: EntityKind, value: String, display: String = value, range: IntRange) {
            val key = "${kind.name}:${value.lowercase(Locale.ROOT)}"
            if (seen.add(key) && found.count { it.kind == kind } < maxPerKind) {
                found += Entity(kind, value, display, snippetAround(text, range))
            }
        }

        // 1. OTP / verification codes.
        for (pat in OTP_PATTERNS) {
            for (m in pat.findAll(text)) {
                val code = m.groupValues[1]
                // Skip bare 4-digit calendar years when ambiguous unless explicit "OTP"/"code:" prefix.
                val yearLike = code.length == 4 && (code.startsWith("19") || code.startsWith("20")) &&
                    !Regex("(?i)\\b(?:otp|code|pin|2fa|passcode)\\b").containsMatchIn(m.value)
                if (!yearLike) add(EntityKind.OTP_CODE, code, code, m.range)
            }
        }

        // 2. Emails.
        for (m in EMAIL_REGEX.findAll(text)) {
            val email = m.value.trimEnd('.', ',', ';', ':')
            add(EntityKind.EMAIL, email, email, m.range)
        }

        // 3. URLs.
        for (m in URL_REGEX.findAll(text)) {
            val cleaned = m.value.trimEnd('.', ',', ';', ':', ')', ']', '!', '?')
            val url = if (cleaned.startsWith("www.", ignoreCase = true)) "https://$cleaned" else cleaned
            val shortHost = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
                .take(40)
            add(EntityKind.URL, url, shortHost, m.range)
        }

        // 4. Phone numbers (strictly 10-15 digits, excluding OTPs/tracking numbers/dates).
        val otpValues = found.filter { it.kind == EntityKind.OTP_CODE }.mapTo(HashSet()) { it.value }
        for (m in PHONE_REGEX.findAll(text)) {
            val raw = m.value.trim()
            val hasPlus = raw.startsWith('+')
            val digits = raw.filter(Char::isDigit)
            if (digits.length !in 10..15) continue
            if (digits in otpValues) continue
            // Reject ISO dates or pure decimal identifiers.
            if (Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(raw)) continue
            val dialable = if (hasPlus) "+$digits" else digits
            add(EntityKind.PHONE, dialable, raw, m.range)
        }

        // 5. Prices / totals (prefer lines containing "total" / "due" / "amount" first).
        val priceMatches = PRICE_REGEX.findAll(text).toList().sortedByDescending { m ->
            val ctx = snippetAround(text, m.range).lowercase(Locale.ROOT)
            when {
                ctx.contains("total") || ctx.contains("amount due") || ctx.contains("grand total") -> 2
                ctx.contains("price") || ctx.contains("balance") || ctx.contains("paid") || ctx.contains("fee") -> 1
                else -> 0
            }
        }
        for (m in priceMatches) {
            val v = m.value.trim()
            add(EntityKind.PRICE, v, v, m.range)
        }

        // 6. Dates & meeting times.
        for (m in DATE_TIME_REGEX.findAll(text)) {
            val v = m.value.trim().replace(Regex("\\s+"), " ")
            if (v.length >= 5) add(EntityKind.DATE_TIME, v, v, m.range)
        }

        // 7. Tracking / order / booking references.
        val nonIds = setOf("NUMBER", "STATUS", "DETAILS", "HISTORY", "SUMMARY", "TOTAL", "ONLINE", "CONFIRM", "CONFIRMED")
        for (m in TRACKING_REGEX.findAll(text)) {
            val id = m.groupValues[1].trim('-')
            if (id.length >= 5 && id.any(Char::isDigit) && id.uppercase(Locale.ROOT) !in nonIds) {
                add(EntityKind.TRACKING_ID, id, id, m.range)
            }
        }

        // 8. Street addresses.
        for (m in ADDRESS_REGEX.findAll(text)) {
            val addr = m.value.trim()
            add(EntityKind.ADDRESS, addr, addr, m.range)
        }

        return found
    }

    // --------------------------------------------- extractive summarizer

    /**
     * Produce a faithful, non-redundant extractive summary using TF word
     * centrality + structural lead bonus + cue salience + Maximal Marginal
     * Relevance (MMR), preserving original narrative order.
     */
    fun summarize(
        rawText: String,
        maxSentences: Int = 3,
        asBullets: Boolean = false
    ): String {
        val segments = cleanSubstantiveSegments(rawText)
        if (segments.isEmpty()) return ""

        val totalWords = segments.sumOf { countWords(it) }
        if (segments.size == 1 || totalWords <= 32) {
            val single = segments.joinToString(" ").trim()
            return if (asBullets) "• $single" else ensureTerminalPunctuation(single)
        }

        val targetCount = maxSentences.coerceIn(1, 6).coerceAtMost(segments.size)

        // Build unigram frequency table over informative content terms.
        val tf = HashMap<String, Int>()
        val segTokens = segments.map { contentTokens(it) }
        for (tokens in segTokens) {
            for (tok in tokens) tf[tok] = (tf[tok] ?: 0) + 1
        }
        val maxFreq = (tf.values.maxOrNull() ?: 1).toDouble().coerceAtLeast(1.0)

        // Base salience score per segment.
        val baseScores = DoubleArray(segments.size) { idx ->
            val seg = segments[idx]
            val tokens = segTokens[idx]
            if (tokens.isEmpty()) return@DoubleArray 0.0
            val wordCount = countWords(seg)
            val centrality = tokens.distinct().sumOf { (tf[it] ?: 0) / maxFreq } /
                ln((tokens.size + 2).toDouble())
            val positionBonus = when (idx) {
                0 -> 0.42
                1 -> 0.22
                segments.lastIndex -> 0.12
                else -> 0.14 * (1.0 - idx.toDouble() / segments.size)
            }
            val cueHits = tokens.count { it in SALIENCE_CUES }
            val cueBonus = min(0.45, cueHits * 0.15)
            val numberBonus = if (seg.any(Char::isDigit)) 0.12 else 0.0
            val lengthFactor = when {
                wordCount < 4 -> 0.35
                wordCount in 6..32 -> 1.0
                wordCount <= 55 -> 0.88
                else -> 0.72
            }
            (centrality + positionBonus + cueBonus + numberBonus) * lengthFactor
        }

        // Greedy MMR selection prevents selecting near-duplicate sentences.
        val chosen = mutableListOf<Int>()
        val tokenSets = segTokens.map { it.toSet() }
        while (chosen.size < targetCount) {
            var bestIdx = -1
            var bestMmr = Double.NEGATIVE_INFINITY
            for (idx in segments.indices) {
                if (idx in chosen || baseScores[idx] <= 0.0) continue
                val maxSim = chosen.maxOfOrNull { prev -> jaccard(tokenSets[idx], tokenSets[prev]) } ?: 0.0
                if (maxSim >= 0.72) continue
                val mmr = 0.72 * baseScores[idx] - 0.28 * maxSim * (baseScores[idx] + 1.0)
                if (mmr > bestMmr) {
                    bestMmr = mmr
                    bestIdx = idx
                }
            }
            if (bestIdx < 0) break
            chosen += bestIdx
        }

        if (chosen.isEmpty()) chosen += 0
        chosen.sort()

        val selected = chosen.map { idx ->
            val trimmed = compactSegment(segments[idx], maxChars = 220)
            ensureTerminalPunctuation(trimmed)
        }
        return if (asBullets) {
            selected.joinToString("\n") { "• $it" }
        } else {
            selected.joinToString(" ")
        }
    }

    // ----------------------------------------------- action-item extraction

    fun extractActionItems(rawText: String, maxItems: Int = 6): List<String> {
        val segments = cleanSubstantiveSegments(rawText)
        if (segments.isEmpty()) return emptyList()
        val items = mutableListOf<String>()
        val seen = HashSet<String>()
        for (seg in segments) {
            val cleaned = seg.replace(Regex("^(?:[-*•]|\\d+[.)]|\\[\\s?])\\s*"), "").trim()
            if (cleaned.length < 8 || countWords(cleaned) < 3) continue
            val isAction = ACTION_ITEM_PATTERNS.any { it.containsMatchIn(seg) }
            if (!isAction) continue
            val key = cleaned.lowercase(Locale.ROOT).take(60)
            if (seen.add(key)) {
                items += ensureTerminalPunctuation(compactSegment(cleaned, 160))
                if (items.size >= maxItems) break
            }
        }
        return items
    }

    // --------------------------------------------------- grounded Q&A

    private enum class QuestionFocus {
        OTP, PRICE, TIME_DATE, PHONE, EMAIL, URL, ADDRESS, TRACKING, WHO, REASON, SUMMARY, ACTIONS, GENERAL
    }

    /**
     * Answer a natural-language question strictly from [sourceText].
     */
    fun answerAboutText(
        question: String,
        sourceText: String,
        sourceLabel: String = "this screen"
    ): GroundedAnswer {
        if (sourceText.isBlank()) {
            return GroundedAnswer("There is no readable text on $sourceLabel.", matched = false)
        }
        val qLow = question.lowercase(Locale.ROOT).trim().trimEnd('?', '.', '!')
        val focus = classifyFocus(qLow)
        val entities = extractEntities(sourceText)

        when (focus) {
            QuestionFocus.SUMMARY -> {
                val bullets = qLow.contains("bullet") || qLow.contains("key point")
                val s = summarize(sourceText, maxSentences = 3, asBullets = bullets)
                return if (s.isBlank()) GroundedAnswer("I couldn't find enough text on $sourceLabel to summarize.", false)
                else GroundedAnswer(s, true)
            }
            QuestionFocus.ACTIONS -> {
                val items = extractActionItems(sourceText)
                return if (items.isEmpty()) {
                    GroundedAnswer("I didn't spot any explicit action items or deadlines on $sourceLabel.", false)
                } else {
                    GroundedAnswer("Action items:\n" + items.joinToString("\n") { "• $it" }, true)
                }
            }
            QuestionFocus.OTP -> {
                val otp = entities.firstOrNull { it.kind == EntityKind.OTP_CODE }
                if (otp != null) {
                    return GroundedAnswer("The verification code on $sourceLabel is ${otp.value}.", true, otp)
                }
            }
            QuestionFocus.PHONE -> {
                val phones = entities.filter { it.kind == EntityKind.PHONE }
                if (phones.isNotEmpty()) {
                    val text = if (phones.size == 1) "Phone number on $sourceLabel: ${phones[0].display}."
                    else "Phone numbers on $sourceLabel: ${phones.joinToString(", ") { it.display }}."
                    return GroundedAnswer(text, true, phones.first())
                }
            }
            QuestionFocus.EMAIL -> {
                val emails = entities.filter { it.kind == EntityKind.EMAIL }
                if (emails.isNotEmpty()) {
                    val text = if (emails.size == 1) "Email on $sourceLabel: ${emails[0].value}."
                    else "Emails on $sourceLabel: ${emails.joinToString(", ") { it.value }}."
                    return GroundedAnswer(text, true, emails.first())
                }
            }
            QuestionFocus.URL -> {
                val urls = entities.filter { it.kind == EntityKind.URL }
                if (urls.isNotEmpty()) {
                    val text = if (urls.size == 1) "Link on $sourceLabel: ${urls[0].value}"
                    else "Links on $sourceLabel: ${urls.joinToString(", ") { it.value }}"
                    return GroundedAnswer(text, true, urls.first())
                }
            }
            QuestionFocus.TRACKING -> {
                val id = entities.firstOrNull { it.kind == EntityKind.TRACKING_ID }
                if (id != null) {
                    return GroundedAnswer("Reference ID on $sourceLabel: ${id.value} (${id.context}).", true, id)
                }
            }
            QuestionFocus.ADDRESS -> {
                val addr = entities.firstOrNull { it.kind == EntityKind.ADDRESS }
                if (addr != null) {
                    return GroundedAnswer("Address on $sourceLabel: ${addr.display}.", true, addr)
                }
            }
            else -> Unit
        }

        // BM25 + entity-affinity passage retrieval over substantive segments.
        val segments = cleanSubstantiveSegments(sourceText)
        if (segments.isEmpty()) {
            return GroundedAnswer("I couldn't find readable details on $sourceLabel.", false)
        }

        val qTerms = queryTokens(qLow)
        val segTerms = segments.map { contentTokens(it).map(::stem) }
        val df = HashMap<String, Int>()
        for (terms in segTerms) {
            for (t in terms.toSet()) df[t] = (df[t] ?: 0) + 1
        }
        val avgLen = segTerms.map { it.size }.average().coerceAtLeast(1.0)
        val nDocs = segments.size.toDouble()

        val scored = segments.indices.map { idx ->
            val seg = segments[idx]
            val terms = segTerms[idx]
            var bm25 = 0.0
            var matchedTerms = 0
            for (qt in qTerms) {
                val freq = terms.count { it == qt || (qt.length >= 4 && it.startsWith(qt)) }
                if (freq > 0) {
                    matchedTerms++
                    val docFreq = (df[qt] ?: 1).toDouble()
                    val idf = ln(1.0 + (nDocs - docFreq + 0.5) / (docFreq + 0.5)).coerceAtLeast(0.25)
                    val tfNorm = (freq * 2.2) / (freq + 1.2 * (0.25 + 0.75 * terms.size / avgLen))
                    bm25 += idf * tfNorm
                }
            }
            val affinity = when (focus) {
                QuestionFocus.PRICE -> when {
                    PRICE_REGEX.containsMatchIn(seg) && Regex("(?i)\\b(?:total|due|amount|price|cost|fee|balance)\\b").containsMatchIn(seg) -> 2.4
                    PRICE_REGEX.containsMatchIn(seg) -> 1.6
                    else -> 0.0
                }
                QuestionFocus.TIME_DATE -> if (DATE_TIME_REGEX.containsMatchIn(seg)) 1.8 else 0.0
                QuestionFocus.WHO -> when {
                    Regex("(?i)\\b(?:from|by|sender|author|host|organizer|assigned to|contact)\\b").containsMatchIn(seg) -> 1.4
                    Regex("\\b[A-Z][a-z]+\\s+[A-Z][a-z]+\\b").containsMatchIn(seg) -> 0.9
                    else -> 0.0
                }
                QuestionFocus.REASON -> if (Regex("(?i)\\b(?:because|due to|since|reason|as a result|so that)\\b").containsMatchIn(seg)) 1.2 else 0.0
                QuestionFocus.ADDRESS -> if (ADDRESS_REGEX.containsMatchIn(seg) || Regex("(?i)\\b(?:location|venue|address|at|room|gate|floor)\\b").containsMatchIn(seg)) 1.4 else 0.0
                else -> 0.0
            }
            Triple(idx, bm25 + affinity, matchedTerms)
        }.sortedByDescending { it.second }

        val top = scored.firstOrNull()
        if (top == null || top.second < 0.75 || (qTerms.isNotEmpty() && top.third == 0 && focus == QuestionFocus.GENERAL)) {
            val topic = qTerms.take(3).joinToString(" ").ifBlank { "that" }
            return GroundedAnswer("I checked $sourceLabel and didn't find anything mentioning \"$topic\".", matched = false)
        }

        val picks = mutableListOf(top.first)
        val runner = scored.getOrNull(1)
        if (runner != null && runner.second >= top.second * 0.68 && runner.second >= 1.1) {
            picks += runner.first
        }
        picks.sort()

        val excerpt = picks.joinToString(" ") { ensureTerminalPunctuation(compactSegment(segments[it], 220)) }
        val primaryEntity = when (focus) {
            QuestionFocus.PRICE -> entities.firstOrNull { it.kind == EntityKind.PRICE }
            QuestionFocus.TIME_DATE -> entities.firstOrNull { it.kind == EntityKind.DATE_TIME }
            else -> null
        }
        return GroundedAnswer("From $sourceLabel: $excerpt", matched = true, primaryEntity = primaryEntity)
    }

    // ------------------------------------------ full digest & suggestions

    fun digest(rawText: String, isLiveScreen: Boolean = true): ScreenDigest {
        val entities = extractEntities(rawText)
        val actions = extractActionItems(rawText, maxItems = 5)
        val summary = summarize(rawText, maxSentences = 3, asBullets = false)
        val words = countWords(rawText)
        val chips = suggestQuickActions(rawText, entities, actions, words, isLiveScreen)
        return ScreenDigest(summary, entities, actions, chips, words)
    }

    fun suggestQuickActions(
        rawText: String,
        entities: List<Entity> = extractEntities(rawText),
        actionItems: List<String> = extractActionItems(rawText, maxItems = 3),
        wordCount: Int = countWords(rawText),
        isLiveScreen: Boolean = true
    ): List<QuickAction> {
        val out = mutableListOf<QuickAction>()
        if (wordCount >= 35) {
            out += QuickAction(
                if (isLiveScreen) "Summarize screen" else "Bullet summary",
                if (isLiveScreen) "summarize screen" else "summarize that"
            )
        }
        if (actionItems.isNotEmpty()) {
            out += QuickAction(
                "Action items (${actionItems.size})",
                if (isLiveScreen) "extract action items from screen" else "extract action items"
            )
        }
        if (entities.isNotEmpty() && isLiveScreen) {
            out += QuickAction("Find details", "find details on this screen")
        }
        return out.distinctBy { it.label }.take(4)
    }

    fun formatEntitiesSummary(entities: List<Entity>): String {
        if (entities.isEmpty()) return ""
        val parts = mutableListOf<String>()
        entities.firstOrNull { it.kind == EntityKind.OTP_CODE }?.let { parts += "Code: ${it.value}" }
        val prices = entities.filter { it.kind == EntityKind.PRICE }.take(2)
        if (prices.isNotEmpty()) parts += "Amount: ${prices.joinToString(", ") { it.display }}"
        val dates = entities.filter { it.kind == EntityKind.DATE_TIME }.take(2)
        if (dates.isNotEmpty()) parts += "When: ${dates.joinToString(", ") { it.display }}"
        entities.firstOrNull { it.kind == EntityKind.PHONE }?.let { parts += "Phone: ${it.display}" }
        entities.firstOrNull { it.kind == EntityKind.EMAIL }?.let { parts += "Email: ${it.value}" }
        entities.firstOrNull { it.kind == EntityKind.URL }?.let { parts += "Link: ${it.display}" }
        entities.firstOrNull { it.kind == EntityKind.TRACKING_ID }?.let { parts += "Ref: ${it.value}" }
        entities.firstOrNull { it.kind == EntityKind.ADDRESS }?.let { parts += "Address: ${it.display}" }
        return parts.joinToString(" · ")
    }

    // ----------------------------------------------------------- helpers

    private fun classifyFocus(qLow: String): QuestionFocus = when {
        Regex("\\b(?:otp|verification code|security code|2fa|passcode|login code|one time code)\\b").containsMatchIn(qLow) -> QuestionFocus.OTP
        Regex("\\b(?:summarize|summarise|summary|tldr|tl;dr|key points|main points|gist|overview)\\b").containsMatchIn(qLow) -> QuestionFocus.SUMMARY
        Regex("\\b(?:action items?|next steps?|to[- ]?dos?|tasks? in)\\b").containsMatchIn(qLow) -> QuestionFocus.ACTIONS
        Regex("\\b(?:phone number|telephone|mobile number|contact number)\\b").containsMatchIn(qLow) -> QuestionFocus.PHONE
        Regex("\\b(?:email address|e-mail|email on)\\b").containsMatchIn(qLow) -> QuestionFocus.EMAIL
        Regex("\\b(?:url|website|web link|link on)\\b").containsMatchIn(qLow) -> QuestionFocus.URL
        Regex("\\b(?:tracking|order number|booking reference|invoice number|reference number|flight number)\\b").containsMatchIn(qLow) -> QuestionFocus.TRACKING
        Regex("\\b(?:how much|total|price|cost|amount|fee|balance|charge)\\b").containsMatchIn(qLow) -> QuestionFocus.PRICE
        Regex("\\b(?:when|what time|what date|deadline|due date|schedule|departure|arrival|expires?)\\b").containsMatchIn(qLow) -> QuestionFocus.TIME_DATE
        Regex("\\b(?:where|address|location|venue|street)\\b").containsMatchIn(qLow) -> QuestionFocus.ADDRESS
        Regex("\\b(?:who|sender|author|from whom|sent this|wrote this)\\b").containsMatchIn(qLow) -> QuestionFocus.WHO
        Regex("\\b(?:why|reason)\\b").containsMatchIn(qLow) -> QuestionFocus.REASON
        else -> QuestionFocus.GENERAL
    }

    private fun cleanSubstantiveSegments(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val lines = raw.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        val sentenceSplit = Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"'“])")

        for (rawLine in lines) {
            val line = rawLine.trim().replace(Regex("[ \\t]+"), " ")
            if (line.length < 3) continue
            val low = line.lowercase(Locale.ROOT).trim('.', ':', '·', '|', '-')
            if (low in UI_CHROME_NOISE) continue
            // Skip bare status-bar clocks ("9:41") or bare battery percentages ("84%").
            if (Regex("^\\d{1,2}:\\d{2}(?:\\s*(?:am|pm))?$", RegexOption.IGNORE_CASE).matches(line)) continue
            if (Regex("^\\d{1,3}%$").matches(line)) continue
            if (line.none(Char::isLetter)) continue

            val parts = if (line.length > 90) sentenceSplit.split(line) else listOf(line)
            for (part in parts) {
                val p = part.trim()
                if (p.length < 4) continue
                val pLow = p.lowercase(Locale.ROOT)
                if (pLow in UI_CHROME_NOISE) continue
                // Keep short lines only if they contain an informative fact (price, colon key:value, >= 3 words).
                if (countWords(p) < 3 && !p.contains(':') && !PRICE_REGEX.containsMatchIn(p)) continue
                if (seen.add(pLow)) out += p
            }
        }
        return out
    }

    private fun contentTokens(text: String): List<String> =
        Regex("[a-z0-9]{2,}").findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { it !in STOP_WORDS }
            .toList()

    private fun queryTokens(qLow: String): List<String> =
        contentTokens(qLow).map(::stem).distinct()

    private fun stem(word: String): String = when {
        word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
        word.length > 5 && word.endsWith("ied") -> word.dropLast(3) + "y"
        word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
        word.length > 5 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && word.endsWith("es") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union
    }

    private fun countWords(s: String): Int =
        Regex("[\\p{L}\\p{N}']+").findAll(s).count()

    private fun compactSegment(s: String, maxChars: Int): String {
        val clean = s.trim()
        if (clean.length <= maxChars) return clean
        val cut = clean.lastIndexOf(' ', max(20, maxChars - 1))
        return (if (cut > maxChars / 2) clean.substring(0, cut) else clean.take(maxChars - 1)).trimEnd(',', ';', ':') + "…"
    }

    private fun ensureTerminalPunctuation(s: String): String {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.last() in ".!?…") trimmed else "$trimmed."
    }

    private fun snippetAround(text: String, range: IntRange): String {
        val lineStart = text.lastIndexOf('\n', range.first).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', range.last).let { if (it < 0) text.length else it }
        return text.substring(lineStart, lineEnd).trim().replace(Regex("\\s+"), " ").take(90)
    }
}
