package com.ema.assistant

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Deterministic on-device Writing Tools — EMA's zero-network equivalent of
 * Apple Intelligence Writing Tools and Gemini Help-Me-Write.
 *
 * Capabilities (all 100% offline, instant, verifiable and privacy-preserving):
 *  - **Proofread & clean up**: fixes 65+ frequent spelling mistakes, missing
 *    apostrophes, lowercase standalone `I`, repeated words, broken spacing,
 *    sentence capitalization and terminal punctuation.
 *  - **Tone & format transforms**: `PROFESSIONAL`, `POLITE`, `CONCISE`,
 *    `FRIENDLY`, `BULLETS`, `ACTION_ITEMS`, `TITLE_CASE`, `UPPERCASE`, `LOWERCASE`.
 *  - **Text analytics**: exact word/character/sentence/paragraph counts,
 *    estimated silent reading time and spoken presentation duration, plus top
 *    salient keywords.
 *  - **Smart message & email drafts**: instant structured templates (running
 *    late, out-of-office, polite decline, meeting follow-up, thank-you, custom
 *    topic/recipient draft) ready to copy or review — never sent automatically.
 *
 * Pure Kotlin, zero Android dependencies, exhaustively unit tested.
 */
object EmaWritingTools {

    enum class Tone(val label: String) {
        PROOFREAD("proofread"),
        PROFESSIONAL("professional"),
        POLITE("polite"),
        CONCISE("concise"),
        FRIENDLY("friendly"),
        BULLETS("bullet points"),
        ACTION_ITEMS("action items"),
        TITLE_CASE("title case"),
        UPPERCASE("uppercase"),
        LOWERCASE("lowercase")
    }

    data class TextStats(
        val words: Int,
        val characters: Int,
        val charactersNoSpaces: Int,
        val sentences: Int,
        val paragraphs: Int,
        val readingSeconds: Int,
        val speakingSeconds: Int,
        val topKeywords: List<String>
    ) {
        fun describe(sourceLabel: String = "Text"): String {
            if (words == 0) return "$sourceLabel is empty (0 words)."
            val readDesc = formatDuration(readingSeconds)
            val speakDesc = formatDuration(speakingSeconds)
            val kw = if (topKeywords.isEmpty()) "" else " Key terms: ${topKeywords.joinToString(", ")}."
            return "$sourceLabel has $words word${if (words == 1) "" else "s"}, $characters characters and " +
                "$sentences sentence${if (sentences == 1) "" else "s"} — about $readDesc to read ($speakDesc spoken aloud).$kw"
        }

        private fun formatDuration(sec: Int): String = when {
            sec < 60 -> "$sec sec"
            sec % 60 < 10 -> "${sec / 60} min"
            else -> "${sec / 60} min ${sec % 60} sec"
        }
    }

    data class RewriteResult(
        val transformed: String,
        val summary: String
    )

    private val COMMON_TYPOS: Map<String, String> = mapOf(
        "teh" to "the", "hte" to "the", "adn" to "and", "nad" to "and", "taht" to "that",
        "wiht" to "with", "whit" to "with", "fro" to "for", "recieve" to "receive",
        "recieved" to "received", "definately" to "definitely", "definatly" to "definitely",
        "seperate" to "separate", "occured" to "occurred", "occuring" to "occurring",
        "untill" to "until", "accomodate" to "accommodate", "accomodation" to "accommodation",
        "wierd" to "weird", "tommorow" to "tomorrow", "tommorrow" to "tomorrow",
        "calender" to "calendar", "adress" to "address", "enviroment" to "environment",
        "goverment" to "government", "neccessary" to "necessary", "necesary" to "necessary",
        "priviledge" to "privilege", "recomend" to "recommend", "reccommend" to "recommend",
        "succesful" to "successful", "successfull" to "successful", "truely" to "truly",
        "wich" to "which", "becuase" to "because", "beacuse" to "because",
        "freind" to "friend", "freinds" to "friends", "beleive" to "believe",
        "acheive" to "achieve", "acheived" to "achieved", "arguement" to "argument",
        "begining" to "beginning", "buisness" to "business", "commitee" to "committee",
        "concious" to "conscious", "embarass" to "embarrass", "existance" to "existence",
        "foriegn" to "foreign", "garantee" to "guarantee", "immediatly" to "immediately",
        "independant" to "independent", "knowlege" to "knowledge", "maintainance" to "maintenance",
        "noticable" to "noticeable", "occassion" to "occasion", "persistant" to "persistent",
        "posession" to "possession", "publically" to "publicly", "refered" to "referred",
        "relevent" to "relevant", "rythm" to "rhythm", "sieze" to "seize",
        "suprise" to "surprise", "suprised" to "surprised", "tendancy" to "tendency",
        "unfortunatly" to "unfortunately", "alot" to "a lot", "thankyou" to "thank you",
        "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't", "cant" to "can't",
        "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
        "wont" to "won't", "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't",
        "werent" to "weren't", "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
        "im" to "I'm", "ive" to "I've", "youre" to "you're", "theyre" to "they're",
        "thats" to "that's", "whats" to "what's", "theres" to "there's", "heres" to "here's"
    )

    private val PROFESSIONAL_REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\bi'm\\b") to "I am",
        Regex("(?i)\\bi've\\b") to "I have",
        Regex("(?i)\\bi'll\\b") to "I will",
        Regex("(?i)\\bi'd\\b") to "I would",
        Regex("(?i)\\bwe're\\b") to "we are",
        Regex("(?i)\\bwe've\\b") to "we have",
        Regex("(?i)\\bwe'll\\b") to "we will",
        Regex("(?i)\\byou're\\b") to "you are",
        Regex("(?i)\\byou've\\b") to "you have",
        Regex("(?i)\\bthey're\\b") to "they are",
        Regex("(?i)\\bcan't\\b") to "cannot",
        Regex("(?i)\\bwon't\\b") to "will not",
        Regex("(?i)\\bdon't\\b") to "do not",
        Regex("(?i)\\bdoesn't\\b") to "does not",
        Regex("(?i)\\bdidn't\\b") to "did not",
        Regex("(?i)\\bisn't\\b") to "is not",
        Regex("(?i)\\baren't\\b") to "are not",
        Regex("(?i)\\bwasn't\\b") to "was not",
        Regex("(?i)\\bweren't\\b") to "were not",
        Regex("(?i)\\bcouldn't\\b") to "could not",
        Regex("(?i)\\bshouldn't\\b") to "should not",
        Regex("(?i)\\bwouldn't\\b") to "would not",
        Regex("(?i)\\bgonna\\b") to "going to",
        Regex("(?i)\\bwanna\\b") to "want to",
        Regex("(?i)\\bgotta\\b") to "need to",
        Regex("(?i)\\bkinda\\b") to "somewhat",
        Regex("(?i)\\bsorta\\b") to "somewhat",
        Regex("(?i)\\basap\\b") to "as soon as possible",
        Regex("(?i)\\bfyi\\b") to "for your information",
        Regex("(?i)\\bbtw\\b") to "additionally",
        Regex("(?i)\\blmk\\b") to "please let me know",
        Regex("(?i)\\bidk\\b") to "I am not certain",
        Regex("(?i)\\btbh\\b") to "candidly",
        Regex("(?i)\\bimo\\b") to "in my view",
        Regex("(?i)\\b(?:pls|plz)\\b") to "please",
        Regex("(?i)\\b(?:thx|ty|thanks a bunch)\\b") to "thank you",
        Regex("(?i)\\bthanks\\b") to "thank you",
        Regex("(?i)\\bu\\b") to "you",
        Regex("(?i)\\bur\\b") to "your",
        Regex("(?i)\\brn\\b") to "at the moment",
        Regex("(?i)^(?:hey|yo|sup|hiya)\\b[,!]?\\s*") to "Hello, ",
        Regex("(?i)\\bsorry for the late reply\\b") to "thank you for your patience",
        Regex("(?i)\\bget back to me\\b") to "follow up with me",
        Regex("(?i)\\bcheck out\\b") to "review",
        Regex("(?i)\\bfix this\\b") to "resolve this issue",
        Regex("(?i)\\bno problem\\b") to "you are welcome",
        Regex("(?i)\\bmy bad\\b") to "my apologies"
    )

    private val CONCISE_REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\b(?:i\\s+)?just\\s+wanted\\s+to\\s+(?:let\\s+you\\s+know|reach\\s+out\\s+and\\s+say|say)\\s+that\\s+") to "",
        Regex("(?i)\\bi\\s+am\\s+writing\\s+(?:to\\s+you\\s+)?(?:in\\s+order\\s+)?to\\s+let\\s+you\\s+know\\s+that\\s+") to "",
        Regex("(?i)\\bin\\s+order\\s+to\\b") to "to",
        Regex("(?i)\\bdue\\s+to\\s+the\\s+fact\\s+that\\b") to "because",
        Regex("(?i)\\bowing\\s+to\\s+the\\s+fact\\s+that\\b") to "because",
        Regex("(?i)\\bat\\s+this\\s+point\\s+in\\s+time\\b") to "now",
        Regex("(?i)\\bat\\s+the\\s+present\\s+time\\b") to "now",
        Regex("(?i)\\bin\\s+the\\s+event\\s+that\\b") to "if",
        Regex("(?i)\\bin\\s+the\\s+near\\s+future\\b") to "soon",
        Regex("(?i)\\bfor\\s+the\\s+purpose\\s+of\\b") to "for",
        Regex("(?i)\\bwith\\s+regard\\s+to\\b") to "regarding",
        Regex("(?i)\\bin\\s+regards?\\s+to\\b") to "regarding",
        Regex("(?i)\\ba\\s+large\\s+number\\s+of\\b") to "many",
        Regex("(?i)\\bthe\\s+vast\\s+majority\\s+of\\b") to "most",
        Regex("(?i)\\bhas\\s+the\\s+ability\\s+to\\b") to "can",
        Regex("(?i)\\bis\\s+able\\s+to\\b") to "can",
        Regex("(?i)\\bmake\\s+a\\s+decision\\b") to "decide",
        Regex("(?i)\\btake\\s+into\\s+consideration\\b") to "consider",
        Regex("(?i)\\bas\\s+a\\s+matter\\s+of\\s+fact[, ]*") to "",
        Regex("(?i)\\bneedless\\s+to\\s+say[, ]*") to "",
        Regex("(?i)\\bit\\s+is\\s+important\\s+to\\s+note\\s+that\\s+") to "",
        Regex("(?i)\\b(?:basically|actually|literally|honestly|really|very|quite|simply)\\s+") to ""
    )

    private val FRIENDLY_REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\bper my last email\\b") to "following up on my earlier note",
        Regex("(?i)\\bplease be advised that\\b") to "just a quick heads-up that",
        Regex("(?i)\\bdo not hesitate to contact me\\b") to "feel free to reach out anytime",
        Regex("(?i)\\bpursuant to\\b") to "following",
        Regex("(?i)\\battached herewith\\b") to "attached",
        Regex("(?i)^dear sir or madam[,:]?\\s*") to "Hi there, ",
        Regex("(?i)\\bsincerely\\b") to "Warm regards"
    )

    private val SMALL_TITLE_WORDS = setOf(
        "a", "an", "the", "and", "but", "or", "nor", "for", "so", "yet",
        "at", "by", "in", "of", "on", "to", "up", "as", "with", "from"
    )

    private val POLITE_IMPERATIVE_VERBS = setOf(
        "send", "share", "review", "check", "update", "call", "email", "reply",
        "confirm", "submit", "approve", "fix", "prepare", "bring", "let", "give", "tell"
    )

    // --------------------------------------------------------- rewrite

    fun rewrite(rawText: String, tone: Tone): RewriteResult {
        val text = rawText.trim()
        if (text.isEmpty()) return RewriteResult("", "Nothing to rewrite.")

        return when (tone) {
            Tone.PROOFREAD -> proofreadInternal(text)
            Tone.PROFESSIONAL -> {
                var working = proofreadInternal(text).transformed
                for ((pat, rep) in PROFESSIONAL_REPLACEMENTS) working = pat.replace(working, rep)
                working = polishSentences(working)
                RewriteResult(working, "Rewritten in a professional tone:\n$working")
            }
            Tone.POLITE -> {
                var working = proofreadInternal(text).transformed
                working = working
                    .replace(Regex("(?i)\\b(?:pls|plz)\\b"), "please")
                    .replace(Regex("(?i)\\basap\\b"), "when you have a moment")
                val firstWord = working.split(Regex("\\s+")).firstOrNull()
                    ?.lowercase(Locale.ROOT)?.trim(',', '.', '!')
                if (firstWord in POLITE_IMPERATIVE_VERBS && !working.contains("please", ignoreCase = true)) {
                    val lowered = working.first().lowercaseChar() + working.substring(1).trimEnd('.', '!')
                    working = "Could you please $lowered? Thank you."
                } else if (!working.contains("thank", ignoreCase = true) && !working.endsWith('?')) {
                    working = ensureTerminal(working) + " Thank you."
                }
                working = polishSentences(working)
                RewriteResult(working, "Rewritten politely:\n$working")
            }
            Tone.CONCISE -> {
                val origWords = countWords(text)
                var working = proofreadInternal(text).transformed
                for ((pat, rep) in CONCISE_REPLACEMENTS) working = pat.replace(working, rep)
                working = polishSentences(working)
                // For multi-sentence passages, also distill via extractive salience.
                val sentenceCount = working.split(Regex("(?<=[.!?])\\s+")).count { it.isNotBlank() }
                if (sentenceCount >= 5) {
                    val keep = max(2, sentenceCount / 2)
                    working = EmaScreenIntelligence.summarize(working, maxSentences = keep, asBullets = false)
                }
                val newWords = countWords(working)
                val saved = (origWords - newWords).coerceAtLeast(0)
                val note = if (saved > 0) " (trimmed $saved word${if (saved == 1) "" else "s"})" else ""
                RewriteResult(working, "Concise version$note:\n$working")
            }
            Tone.FRIENDLY -> {
                var working = proofreadInternal(text).transformed
                for ((pat, rep) in FRIENDLY_REPLACEMENTS) working = pat.replace(working, rep)
                working = polishSentences(working)
                if (!working.startsWith("Hi", ignoreCase = true) &&
                    !working.startsWith("Hello", ignoreCase = true) &&
                    !working.startsWith("Hey", ignoreCase = true) &&
                    countWords(working) >= 5
                ) {
                    working = "Hi! $working"
                }
                RewriteResult(working, "Friendly version:\n$working")
            }
            Tone.BULLETS -> {
                val bullets = EmaScreenIntelligence.summarize(text, maxSentences = 5, asBullets = true)
                val out = bullets.ifBlank { "• ${polishSentences(text)}" }
                RewriteResult(out, "Key points:\n$out")
            }
            Tone.ACTION_ITEMS -> {
                val items = EmaScreenIntelligence.extractActionItems(text, maxItems = 6)
                if (items.isEmpty()) {
                    RewriteResult("", "I didn't find explicit action items or deadlines in that text.")
                } else {
                    val formatted = items.joinToString("\n") { "• $it" }
                    RewriteResult(formatted, "Action items:\n$formatted")
                }
            }
            Tone.TITLE_CASE -> {
                val words = text.replace(Regex("\\s+"), " ").split(' ')
                val titled = words.mapIndexed { idx, w ->
                    val low = w.lowercase(Locale.ROOT)
                    if (idx != 0 && idx != words.lastIndex && low in SMALL_TITLE_WORDS) low
                    else low.replaceFirstChar { it.titlecase(Locale.ROOT) }
                }.joinToString(" ")
                RewriteResult(titled, titled)
            }
            Tone.UPPERCASE -> {
                val up = text.uppercase(Locale.ROOT)
                RewriteResult(up, up)
            }
            Tone.LOWERCASE -> {
                val low = text.lowercase(Locale.ROOT)
                RewriteResult(low, low)
            }
        }
    }

    private fun proofreadInternal(text: String): RewriteResult {
        var edits = 0
        // Fix common word typos while preserving intentional capitalization and URLs/emails.
        val wordFixed = Regex("(?<![@./\\w])([A-Za-z']+)(?![@\\w])").replace(text) { m ->
            val orig = m.value
            val low = orig.lowercase(Locale.ROOT)
            if (low == "i" && orig != "I") {
                edits++
                "I"
            } else {
                val fix = COMMON_TYPOS[low]
                if (fix == null) {
                    orig
                } else {
                    edits++
                    if (orig[0].isUpperCase() && !fix.startsWith("I")) {
                        fix.replaceFirstChar { it.titlecase(Locale.ROOT) }
                    } else {
                        fix
                    }
                }
            }
        }
        // Remove accidental adjacent duplicate words ("the the", "to to").
        val deduped = Regex("(?i)\\b(the|a|an|to|and|in|is|it|of|for|on|with|at|be|this)\\s+\\1\\b")
            .replace(wordFixed) { edits++; it.groupValues[1] }

        val polished = polishSentences(deduped)
        if (polished != deduped) edits++
        val header = if (edits == 0) "Looks clean already:" else "Proofread ($edits fix${if (edits == 1) "" else "es"}):"
        return RewriteResult(polished, "$header\n$polished")
    }

    // ------------------------------------------------------- analytics

    fun analyze(rawText: String): TextStats {
        val text = rawText.trim()
        if (text.isEmpty()) return TextStats(0, 0, 0, 0, 0, 0, 0, emptyList())
        val wordMatches = Regex("[\\p{L}\\p{N}']+").findAll(text).map { it.value }.toList()
        val words = wordMatches.size
        val chars = text.length
        val noSpace = text.count { !it.isWhitespace() }
        val sentences = max(1, Regex("[.!?]+(?:\\s|$)").findAll(text).count())
        val paragraphs = max(1, text.split(Regex("\\n\\s*\\n")).count { it.isNotBlank() })
        val readingSec = max(1, ceil(words * 60.0 / 220.0).toInt())
        val speakingSec = max(1, ceil(words * 60.0 / 145.0).toInt())

        val stop = setOf(
            "the", "and", "for", "that", "this", "with", "you", "your", "are", "was", "were",
            "have", "has", "had", "not", "but", "from", "they", "will", "would", "there",
            "their", "what", "about", "which", "when", "make", "can", "like", "time", "just",
            "into", "more", "some", "than", "them", "very", "also", "been", "only", "over"
        )
        val freq = HashMap<String, Int>()
        for (w in wordMatches) {
            val low = w.lowercase(Locale.ROOT).trim('\'')
            if (low.length >= 4 && low !in stop && low.any(Char::isLetter)) {
                freq[low] = (freq[low] ?: 0) + 1
            }
        }
        val topKw = freq.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(4)
            .map { it.key }

        return TextStats(words, chars, noSpace, sentences, paragraphs, readingSec, speakingSec, topKw)
    }

    // --------------------------------------------------- smart drafts

    /**
     * Draft a crisp, ready-to-copy message or email from a spoken request such
     * as `"draft an out of office message"`, `"draft a polite decline"`,
     * `"draft a running late text"`, or `"draft a message to Ada saying we will
     * review the proposal on Monday"`.
     */
    fun draft(request: String): RewriteResult? {
        val raw = request.trim()
        val m = Regex(
            "(?i)^(?:draft|compose|write\\s+a\\s+draft)\\s+(?:an?\\s+)?(.+)$"
        ).find(raw) ?: return null
        val spec = m.groupValues[1].trim()
        val low = spec.lowercase(Locale.ROOT)

        // Custom content: "draft a [professional/polite/friendly] (message|email|reply|note) [to X] (saying|about|that) Y"
        Regex(
            "(?i)^(?:(professional|formal|polite|friendly|short|brief|quick)\\s+)?" +
                "(?:message|email|mail|reply|response|text|note)\\s*" +
                "(?:(?:to|for)\\s+([A-Za-z][A-Za-z .'-]{0,28}?)\\s+)?" +
                "(?:saying|that\\s+says|that|about)\\s+(.+)$"
        ).find(spec)?.let { cm ->
            val style = cm.groupValues[1].lowercase(Locale.ROOT)
            val recipient = cm.groupValues[2].trim().takeIf { it.isNotEmpty() }
                ?.split(' ')?.joinToString(" ") { it.replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) } }
            val bodyRaw = cm.groupValues[3].trim()
            val tone = when (style) {
                "polite" -> Tone.POLITE
                "friendly" -> Tone.FRIENDLY
                "short", "brief", "quick" -> Tone.CONCISE
                else -> Tone.PROFESSIONAL
            }
            val polishedBody = rewrite(bodyRaw, tone).transformed.removePrefix("Hi! ").removePrefix("Hello, ")
            val greeting = when {
                recipient != null && tone == Tone.FRIENDLY -> "Hi $recipient,\n\n"
                recipient != null -> "Hello $recipient,\n\n"
                else -> ""
            }
            val signOff = if (low.contains("email") || low.contains("mail")) "\n\nBest regards," else ""
            val full = "$greeting${polishSentences(polishedBody)}$signOff"
            return RewriteResult(full, "Draft ready (not sent):\n$full")
        }

        val preset = when {
            low.contains("out of office") || low.contains("ooo") || low.contains("vacation reply") ->
                "Thank you for your message. I am currently out of the office with limited access to email and will respond as soon as I return. For urgent matters, please flag your message accordingly."
            low.contains("running late") || low.contains("be late") ->
                "Hi — I am running a few minutes behind schedule and will be there shortly. Thank you for waiting."
            low.contains("decline") || low.contains("say no") || low.contains("can't make it") || low.contains("cannot make it") ->
                "Thank you so much for thinking of me. Unfortunately, I won't be able to make it this time, but I hope everything goes well and would love to catch up soon."
            low.contains("follow up") || low.contains("follow-up") ->
                "Hello,\n\nI hope you are doing well. I am following up on our earlier conversation to check on next steps and see if you need anything else from my side.\n\nBest regards,"
            low.contains("thank you") || low.contains("thanks") ->
                "Thank you very much for your time and support — I truly appreciate it. Please let me know if I can ever return the favor."
            low.contains("reschedule") || low.contains("postpone") ->
                "Hello,\n\nSomething unexpected came up on my schedule. Would we be able to move our meeting to another time that works well for you? Thank you for your flexibility."
            low.contains("meeting invite") || low.contains("schedule a meeting") ->
                "Hello,\n\nI would like to schedule a brief meeting to align on next steps. Please share a couple of times that work best for you this week.\n\nBest regards,"
            else -> return null
        }
        return RewriteResult(preset, "Draft ready (not sent):\n$preset")
    }

    // --------------------------------------------------- command parser

    sealed class Command {
        data class TransformInline(val tone: Tone, val text: String) : Command()
        data class TransformTarget(val tone: Tone, val target: Target) : Command()
        data class StatsTarget(val target: Target) : Command()
        data class StatsInline(val text: String) : Command()
        data class Draft(val request: String) : Command()
    }

    enum class Target { CLIPBOARD, SCREEN, LAST_DOCUMENT }

    /** Parse a standalone writing-tools voice/text command, or null when unrelated. */
    fun parseCommand(raw: String): Command? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val lower = text.lowercase(Locale.ROOT).trimEnd('.', '!', '?')

        if (Regex("^(?:draft|compose|write a draft)\\s+").containsMatchIn(lower)) {
            if (draft(text) != null) return Command.Draft(text)
        }

        // Word count & reading time: "count words in my clipboard", "how long to read this screen"
        when {
            Regex("^(?:count words|word count|how many words|how long (?:would it take )?to read|reading time)(?: (?:in|on|of|for))? (?:my |the )?clipboard$").matches(lower) ->
                return Command.StatsTarget(Target.CLIPBOARD)
            Regex("^(?:count words|word count|how many words|how long (?:would it take )?to read|reading time)(?: (?:in|on|of|for))? (?:this |my |the )?(?:screen|page|article)$").matches(lower) ->
                return Command.StatsTarget(Target.SCREEN)
            Regex("^(?:how long (?:would it take )?to read (?:it|that|this)|how many words (?:is (?:it|that)|are in (?:it|that))|word count)$").matches(lower) ->
                return Command.StatsTarget(Target.LAST_DOCUMENT)
        }
        Regex("(?i)^(?:count words in|word count (?:of|for))[:\\s]+(.+)$").find(text)?.let {
            return Command.StatsInline(it.groupValues[1].trim())
        }

        // Target-based writing transforms (clipboard / screen / last document).
        val toneFromTarget: Pair<Tone, Target>? = when {
            Regex("^(?:proofread|clean up|fix grammar in|fix spelling in|fix typos in) (?:my |the )?clipboard$").matches(lower) ->
                Tone.PROOFREAD to Target.CLIPBOARD
            Regex("^(?:make (?:my |the )?clipboard (?:more )?professional|rewrite (?:my |the )?clipboard professionally)$").matches(lower) ->
                Tone.PROFESSIONAL to Target.CLIPBOARD
            Regex("^(?:make (?:my |the )?clipboard (?:more )?polite|rewrite (?:my |the )?clipboard politely)$").matches(lower) ->
                Tone.POLITE to Target.CLIPBOARD
            Regex("^(?:make (?:my |the )?clipboard (?:more )?(?:concise|shorter|brief)|shorten (?:my |the )?clipboard)$").matches(lower) ->
                Tone.CONCISE to Target.CLIPBOARD
            Regex("^(?:make (?:my |the )?clipboard (?:more )?(?:friendly|warmer))$").matches(lower) ->
                Tone.FRIENDLY to Target.CLIPBOARD
            Regex("^(?:bullet points? (?:from|of|for)|turn (?:my |the )?clipboard into bullet points?) ?(?:my |the )?(?:clipboard)?$").matches(lower) &&
                lower.contains("clipboard") -> Tone.BULLETS to Target.CLIPBOARD
            Regex("^(?:extract |find |get |list )?action items (?:from |in |on )(?:my |the )?clipboard$").matches(lower) ->
                Tone.ACTION_ITEMS to Target.CLIPBOARD
            Regex("^(?:extract |find |get |list )?action items (?:from |in |on )(?:this |my |the )?(?:screen|page)$").matches(lower) ->
                Tone.ACTION_ITEMS to Target.SCREEN
            Regex("^(?:extract action items|action items|what are the action items|any action items)$").matches(lower) ->
                Tone.ACTION_ITEMS to Target.LAST_DOCUMENT
            Regex("^(?:make (?:that|it|this) (?:more )?professional|rewrite (?:that|it) professionally)$").matches(lower) ->
                Tone.PROFESSIONAL to Target.LAST_DOCUMENT
            Regex("^(?:make (?:that|it|this) (?:more )?polite|rewrite (?:that|it) politely)$").matches(lower) ->
                Tone.POLITE to Target.LAST_DOCUMENT
            Regex("^(?:make (?:that|it|this) (?:more )?(?:concise|shorter|brief)|shorten (?:that|it))$").matches(lower) ->
                Tone.CONCISE to Target.LAST_DOCUMENT
            Regex("^(?:make (?:that|it|this) (?:more )?friendly)$").matches(lower) ->
                Tone.FRIENDLY to Target.LAST_DOCUMENT
            Regex("^(?:proofread (?:that|it|this)|fix spelling in (?:that|it))$").matches(lower) ->
                Tone.PROOFREAD to Target.LAST_DOCUMENT
            else -> null
        }
        if (toneFromTarget != null) return Command.TransformTarget(toneFromTarget.first, toneFromTarget.second)

        // Inline transforms with explicit colon or separator ("proofread: ...", "make this professional: ...").
        val inlinePatterns = listOf(
            Regex("(?i)^(?:proofread|fix spelling(?: in)?|fix typos(?: in)?|clean up)\\s*:\\s*(.+)$") to Tone.PROOFREAD,
            Regex("(?i)^(?:make (?:this )?(?:more )?professional|rewrite professionally)\\s*:\\s*(.+)$") to Tone.PROFESSIONAL,
            Regex("(?i)^(?:make (?:this )?(?:more )?polite|rewrite politely)\\s*:\\s*(.+)$") to Tone.POLITE,
            Regex("(?i)^(?:make (?:this )?(?:more )?(?:concise|shorter|brief)|shorten)\\s*:\\s*(.+)$") to Tone.CONCISE,
            Regex("(?i)^(?:make (?:this )?(?:more )?friendly|rewrite warmly)\\s*:\\s*(.+)$") to Tone.FRIENDLY,
            Regex("(?i)^(?:bullet points?|key points?)\\s*:\\s*(.+)$") to Tone.BULLETS,
            Regex("(?i)^(?:extract )?action items\\s*:\\s*(.+)$") to Tone.ACTION_ITEMS,
            Regex("(?i)^title case\\s*:\\s*(.+)$") to Tone.TITLE_CASE,
            Regex("(?i)^uppercase\\s*:\\s*(.+)$") to Tone.UPPERCASE,
            Regex("(?i)^lowercase\\s*:\\s*(.+)$") to Tone.LOWERCASE
        )
        for ((pat, tone) in inlinePatterns) {
            pat.find(text)?.let { m ->
                val payload = m.groupValues[1].trim()
                if (payload.isNotEmpty()) return Command.TransformInline(tone, payload)
            }
        }
        return null
    }

    // --------------------------------------------------------- helpers

    private fun countWords(s: String): Int = Regex("[\\p{L}\\p{N}']+").findAll(s).count()

    private fun ensureTerminal(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return t
        return if (t.last() in ".!?") t else "$t."
    }

    private fun polishSentences(input: String): String {
        val collapsed = input
            .replace(Regex("[ \\t]+([.,!?;:])"), "$1")
            .replace(Regex("([.,!?;:])(?=[A-Za-z])"), "$1 ")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
        if (collapsed.isEmpty()) return ""

        val sb = StringBuilder(collapsed.length + 1)
        var capNext = true
        for (ch in collapsed) {
            when {
                capNext && ch.isLetter() -> {
                    sb.append(ch.uppercaseChar())
                    capNext = false
                }
                ch in ".!?" -> {
                    sb.append(ch)
                    capNext = true
                }
                ch == '\n' -> {
                    sb.append(ch)
                    capNext = true
                }
                !ch.isWhitespace() && ch !in "\"'“”(" -> {
                    sb.append(ch)
                    capNext = false
                }
                else -> sb.append(ch)
            }
        }
        val out = sb.toString().trim()
        return if (out.isNotEmpty() && out.last() !in ".!?:,") "$out." else out
    }
}
