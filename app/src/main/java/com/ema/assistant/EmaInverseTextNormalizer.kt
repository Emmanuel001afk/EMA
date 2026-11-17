package com.ema.assistant

import java.util.Locale

/**
 * Private, strictly on-device vocabulary snapshot used to normalize and rescore
 * spoken hypotheses against what actually exists on the owner's phone and
 * current screen.
 *
 * Kept in memory only; never written to logs or sent over the network.
 */
data class EmaSpeechContext(
    val shortcuts: List<String> = emptyList(),
    val ownerAliases: List<String> = emptyList(),
    val learnedPhrases: List<String> = emptyList(),
    val installedApps: List<String> = emptyList(),
    val contactNames: List<String> = emptyList(),
    /**
     * Unambiguous owner-confirmed (or >=2x repeated) mishearing/correction
     * mappings (`normalizedSpoken -> intendedMeaning`) from [EmaLearningModel].
     */
    val learnedReplacements: Map<String, String> = emptyMap(),
    /**
     * Currently visible interactive labels on screen (from `AssistStructure`
     * or accessibility). Kept strictly on-device; never forwarded to external
     * speech services.
     */
    val screenLabels: List<String> = emptyList()
) {
    /**
     * Flat hint list (shortcuts + apps only) suitable for platform biasing APIs.
     * Contact names, learned corrections and screen labels are deliberately
     * excluded so private personal data never leaves EMA.
     */
    fun publicBiasingHints(limit: Int = 200): ArrayList<String> {
        val out = LinkedHashSet<String>()
        shortcuts.forEach { if (it.isNotBlank()) out += it.trim() }
        ownerAliases.forEach { if (it.isNotBlank()) out += it.trim() }
        installedApps.forEach { if (it.isNotBlank()) out += it.trim() }
        return ArrayList(out.take(limit))
    }

    companion object {
        val EMPTY = EmaSpeechContext()
    }
}

/**
 * Inverse Text Normalizer (ITN), disfluency cleaner, slot grounder and
 * dictation formatter for EMA's speech-to-text stack.
 *
 * Converts raw lowercase acoustic word streams into natural, executable text:
 *  - Spoken clock times: `"six thirty a m"` -> `"6:30 am"`, `"seven oh five p m"` -> `"7:05 pm"`
 *  - Compound/decimal/negative numbers & percentages: `"negative fifteen"` -> `"-15"`,
 *    `"seventy five percent"` -> `"75%"`, `"two thousand four hundred"` -> `"2400"`
 *  - Spoken phone numbers & digit runs: `"call plus two three four eight zero three double four five six seven eight nine"`
 *    -> `"call +2348034456789"`, `"call daniel ending four two one zero"` -> `"call Daniel ending 4210"`
 *  - Compound ordinals: `"march twenty first"` -> `"March 21st"` (while keeping `"the first one"` intact)
 *  - Spoken emails & web domains: `"alex dot okafor at gmail dot com"` -> `"alex.okafor@gmail.com"`
 *  - Disfluency, stutter & self-repair cleanup: `"open open uh you tube"` -> `"open YouTube"`,
 *    `"open facebook scratch that open instagram"` -> `"open Instagram"`
 *  - Phonetic grounding of app, contact & visible screen-control slots
 *  - Rich voice dictation: spoken punctuation, `"scratch that"` clause editing,
 *    paragraph preservation and proper-noun capitalization
 *
 * Message bodies, search queries and reminder tails are protected so command
 * normalizations never alter the owner's actual content, and ambiguous app or
 * contact matches are never guessed.
 *
 * Pure Kotlin, zero Android runtime dependencies, exhaustively unit tested.
 */
object EmaInverseTextNormalizer {

    private val ONES = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L,
        "five" to 5L, "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L,
        "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L,
        "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L,
        "eighteen" to 18L, "nineteen" to 19L
    )

    private val TENS = mapOf(
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
        "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L
    )

    private val ORDINAL_UNITS = mapOf(
        "first" to (1 to "st"),
        "second" to (2 to "nd"),
        "third" to (3 to "rd"),
        "fourth" to (4 to "th"),
        "fifth" to (5 to "th"),
        "sixth" to (6 to "th"),
        "seventh" to (7 to "th"),
        "eighth" to (8 to "th"),
        "ninth" to (9 to "th")
    )

    private val SCALES = mapOf(
        "hundred" to 100L,
        "thousand" to 1_000L,
        "million" to 1_000_000L,
        "billion" to 1_000_000_000L
    )

    private val SINGLE_DIGITS = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0", "nought" to "0",
        "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9"
    )

    private val DIGIT_SEQUENCE_LEADERS = setOf(
        "call", "dial", "phone", "ring", "text", "message", "ending",
        "code", "otp", "pin", "extension", "ext", "number"
    )

    private val PRONOUN_ONE_PREV = setOf(
        "the", "this", "that", "which", "another", "other", "first", "second", "third",
        "fourth", "fifth", "last", "next", "previous", "longest", "shortest", "newest",
        "oldest", "best", "top", "no", "any", "some", "every", "each", "only", "same",
        "wrong", "right", "new", "old", "big", "small", "good", "nice"
    )

    private val PRONOUN_ONE_NEXT = setOf(
        "instead", "please", "now", "too", "also", "again", "action", "step", "thing",
        "by", "of", "that", "which", "who", "is", "was", "more", "else", "on", "in"
    )

    private val NUMERIC_FOLLOWERS = setOf(
        "percent", "%", "second", "seconds", "sec", "secs", "minute", "minutes", "min", "mins",
        "hour", "hours", "hr", "hrs", "day", "days", "week", "weeks", "month", "months", "year", "years",
        "mm", "millimeter", "millimeters", "millimetre", "millimetres",
        "cm", "centimeter", "centimeters", "centimetre", "centimetres",
        "m", "meter", "meters", "metre", "metres",
        "km", "kilometer", "kilometers", "kilometre", "kilometres", "kms",
        "inch", "inches", "foot", "feet", "ft", "yard", "yards", "yd", "mile", "miles", "mi",
        "mg", "milligram", "milligrams", "g", "gram", "grams",
        "kg", "kilo", "kilos", "kilogram", "kilograms",
        "lb", "lbs", "pound", "pounds", "oz", "ounce", "ounces", "stone", "stones",
        "ml", "milliliter", "milliliters", "millilitre", "millilitres",
        "l", "liter", "liters", "litre", "litres", "gallon", "gallons", "cup", "cups",
        "kb", "kilobyte", "kilobytes", "mb", "megabyte", "megabytes",
        "gb", "gigabyte", "gigabytes", "tb", "terabyte", "terabytes",
        "kmh", "kph", "mph", "degree", "degrees", "celsius", "centigrade", "fahrenheit", "kelvin",
        "plus", "minus", "times", "multiplied", "divided", "over", "squared", "cubed",
        "power", "raised", "factorial", "modulo", "mod",
        "am", "pm", "o'clock",
        "naira", "kobo", "dollar", "dollars", "cent", "cents", "euro", "euros", "cedis", "shillings", "rand", "yen", "rupees"
    )

    private val NUMERIC_LEADERS = setOf(
        "at", "for", "in", "after", "within", "around", "until", "till", "by", "to", "of",
        "plus", "add", "minus", "subtract", "less", "times", "into", "over", "mod", "modulo",
        "is", "what's", "calculate", "compute", "solve", "evaluate", "convert",
        "increase", "decrease", "reduce", "set", "sim", "alarm", "timer", "brightness", "volume",
        "number", "level", "channel", "page", "step", "ending", "option", "choice", "item"
    )

    private val NEGATIVE_MATH_LEADERS = setOf(
        "is", "what's", "calculate", "compute", "solve", "evaluate", "convert",
        "plus", "minus", "times", "by", "over", "from", "to", "equals", "of"
    )

    private val VERB_AM_FOLLOWERS = setOf(
        "going", "coming", "running", "leaving", "heading", "driving", "walking", "working",
        "waiting", "calling", "texting", "sending", "trying", "doing", "having", "getting",
        "being", "feeling", "looking", "ready", "busy", "here", "there", "home", "outside",
        "inside", "late", "early", "free", "back", "done", "fine", "good", "okay", "ok",
        "sorry", "not", "so", "very", "almost", "still", "just", "already", "about", "in", "at", "on", "a", "an", "the"
    )

    private val LEADING_HESITATIONS = setOf("uh", "uhh", "um", "umm", "erm", "er", "hmm", "hm")
    private val INLINE_HESITATIONS = setOf("uh", "uhh", "um", "umm", "erm")

    /** Words that legitimately appear twice in a row and must never be de-stuttered. */
    private val LEGITIMATE_WORD_DOUBLES = setOf(
        "no", "yes", "yeah", "bye", "very", "so", "too", "had", "that", "really",
        "wait", "go", "okay", "ok", "ha", "oh", "dot", "knock", "beep", "fifty", "twenty"
    )

    private val COMMAND_REPAIR_VERBS = setOf(
        "open", "launch", "start", "call", "dial", "phone", "ring", "message", "text",
        "send", "whatsapp", "set", "turn", "switch", "enable", "disable", "play", "pause",
        "stop", "resume", "next", "previous", "search", "find", "convert", "calculate",
        "what", "what's", "remind", "take", "read", "transcribe", "mute", "unmute", "tap", "click"
    )

    private val KNOWN_EMAIL_TLDS = setOf(
        "com", "org", "net", "edu", "gov", "io", "co", "ai", "app", "dev", "me",
        "ng", "uk", "ca", "au", "in", "za", "gh", "ke", "de", "fr", "us", "info", "biz"
    )

    private val KNOWN_WEB_TLDS = setOf(
        "com", "org", "net", "edu", "gov", "io", "ai", "app", "dev", "ng"
    )

    private val PROPER_NOUN_CASING = mapOf(
        "monday" to "Monday", "tuesday" to "Tuesday", "wednesday" to "Wednesday",
        "thursday" to "Thursday", "friday" to "Friday", "saturday" to "Saturday", "sunday" to "Sunday",
        "january" to "January", "february" to "February", "march" to "March", "april" to "April",
        "june" to "June", "july" to "July", "august" to "August", "september" to "September",
        "october" to "October", "november" to "November", "december" to "December",
        "ema" to "EMA", "android" to "Android", "google" to "Google", "gmail" to "Gmail",
        "whatsapp" to "WhatsApp", "youtube" to "YouTube", "spotify" to "Spotify",
        "instagram" to "Instagram", "telegram" to "Telegram", "tiktok" to "TikTok",
        "facebook" to "Facebook", "linkedin" to "LinkedIn", "snapchat" to "Snapchat",
        "github" to "GitHub", "chatgpt" to "ChatGPT", "netflix" to "Netflix",
        "opay" to "OPay", "palmpay" to "PalmPay", "moniepoint" to "Moniepoint",
        "paystack" to "Paystack", "flutterwave" to "Flutterwave", "audiomack" to "Audiomack",
        "boomplay" to "Boomplay", "lagos" to "Lagos", "abuja" to "Abuja", "nigeria" to "Nigeria",
        "london" to "London", "accra" to "Accra", "nairobi" to "Nairobi"
    )

    /** Ambiguous English words that happen to be names — never auto-capitalize mid-dictation without context. */
    private val AMBIGUOUS_NAME_WORDS = setOf(
        "may", "will", "mark", "grace", "hope", "joy", "faith", "mercy", "peace",
        "blessing", "precious", "gift", "love", "rose", "lily", "iris", "jade",
        "amber", "april", "august", "dawn", "art", "bill", "bob", "pat", "rob",
        "sue", "don", "gene", "grant", "dean", "frank", "rich", "max", "best",
        "bright", "wisdom", "favour", "favor", "patience", "comfort", "goodness",
        "sunday", "monday", "friday", "home", "work", "office", "mum", "mom", "dad",
        "boss", "doctor", "honey", "baby", "bro", "sis"
    )

    private val OFFENSIVE_WORDS = setOf(
        "fuck", "fucking", "fucked", "fucker", "motherfucker",
        "shit", "shitty", "bullshit", "bitch", "asshole", "bastard"
    )

    /** Well-known split words and common Vosk mishearings safe inside the command portion. */
    private val COMMAND_PHRASE_FIXES: List<Pair<Regex, String>> = listOf(
        // Wake & assistant name spellings
        Regex("(?i)^(?:hey|hay)\\s+(?:emma|ema|emmah|amma|e\\s+m\\s+a)\\b") to "Hey EMA",
        Regex("(?i)^(?:hi)\\s+(?:emma|ema|emmah|e\\s+m\\s+a)\\b") to "Hi EMA",
        Regex("(?i)^(?:okay|ok)\\s+(?:emma|ema|emmah|e\\s+m\\s+a)\\b") to "Okay EMA",
        Regex("(?i)\\bteach\\s+(?:emma|amma|e\\s+m\\s+a)\\s+my\\s+voice\\b") to "teach EMA my voice",
        Regex("(?i)\\be\\s+m\\s+a\\b") to "EMA",

        // SIM slot homophones ("with seem two", "with sim too", "using sim one")
        Regex("(?i)\\b(with|using|on|via)\\s+(?:sim|seem|sin)\\s+(?:one|1|won)\\b") to "$1 SIM 1",
        Regex("(?i)\\b(with|using|on|via)\\s+(?:sim|seem|sin)\\s+(?:two|2|too|to)\\b") to "$1 SIM 2",
        Regex("(?i)^(?:sim|seem|sin)\\s+(?:one|1|won)$") to "SIM 1",
        Regex("(?i)^(?:sim|seem|sin)\\s+(?:two|2|too|to)$") to "SIM 2",

        // Device-setting compounds & frequent offline mishearings
        Regex("(?i)\\b(?:wi\\s+fi|why\\s+fi|why\\s+five|wife\\s+i|wifi)\\b") to "Wi-Fi",
        Regex("(?i)\\bblue\\s+tooth\\b") to "Bluetooth",
        Regex("(?i)\\b(?:flash\\s+light|torch\\s+light)\\b") to "flashlight",
        Regex("(?i)\\bscreen\\s+shot\\b") to "screenshot",
        Regex("(?i)\\bhot\\s+spot\\b") to "hotspot",
        Regex("(?i)\\b(?:air\\s+plane|aero\\s+plane)\\b") to "airplane",
        Regex("(?i)\\bdo\\s+not\\s+disturb\\b") to "Do Not Disturb",
        Regex("(?i)\\bd\\s+n\\s+d\\b") to "Do Not Disturb",

        // Global & regional compound app names in command slots
        Regex("(?i)\\byou\\s+tube\\b") to "YouTube",
        Regex("(?i)\\byoutube\\b") to "YouTube",
        Regex("(?i)\\b(?:whats\\s+app|what's\\s+app|what\\s+sup|watts\\s+app|whatsapp)\\b") to "WhatsApp",
        Regex("(?i)\\b(open|launch|start|on|in|using|via|through)\\s+what's\\s+up\\b") to "$1 WhatsApp",
        Regex("(?i)\\b(?:spots?\\s+if\\s+(?:i|eye)|spot\\s+a\\s+(?:fi|five|fly)|spot\\s+defy|spot\\s+ify|spotify)\\b") to "Spotify",
        Regex("(?i)\\b(?:tick\\s+tock|tik\\s+tok|tic\\s+tac|tiktok)\\b") to "TikTok",
        Regex("(?i)\\bface\\s+book\\b") to "Facebook",
        Regex("(?i)\\binsta\\s+gram\\b") to "Instagram",
        Regex("(?i)\\btele\\s+gram\\b") to "Telegram",
        Regex("(?i)\\bsnap\\s+chat\\b") to "Snapchat",
        Regex("(?i)\\blinked\\s+in\\b") to "LinkedIn",
        Regex("(?i)\\bnet\\s+flix\\b") to "Netflix",
        Regex("(?i)\\bsound\\s+cloud\\b") to "SoundCloud",
        Regex("(?i)\\b(?:audio\\s+mack|audio\\s+mac|audiomack)\\b") to "Audiomack",
        Regex("(?i)\\bboom\\s+play\\b") to "Boomplay",
        Regex("(?i)\\b(open|launch|start|on|in|using|via|through)\\s+(?:oh\\s+pay|o\\s+pay|opay)\\b") to "$1 OPay",
        Regex("(?i)\\b(?:palm\\s+pay|palmpay)\\b") to "PalmPay",
        Regex("(?i)\\b(?:money\\s+point|monie\\s+point|moniepoint)\\b") to "Moniepoint",
        Regex("(?i)\\b(?:piggy\\s+vest|piggyvest)\\b") to "PiggyVest",
        Regex("(?i)\\b(?:pay\\s+stack|paystack)\\b") to "Paystack",
        Regex("(?i)\\b(?:flutter\\s+wave|flutterwave)\\b") to "Flutterwave",
        Regex("(?i)\\b(?:true\\s+caller|truecaller)\\b") to "Truecaller",
        Regex("(?i)\\b(?:duo\\s+lingo|duolingo)\\b") to "Duolingo",
        Regex("(?i)\\b(?:g\\s+mail|gee\\s+mail|gmail)\\b") to "Gmail",
        Regex("(?i)\\bplay\\s+store\\b") to "Play Store",
        Regex("(?i)\\b(?:chat\\s+g\\s+p\\s+t|chat\\s+gee\\s+pee\\s+tee|chatgpt)\\b") to "ChatGPT",
        Regex("(?i)\\bcap\\s+cut\\b") to "CapCut",
        Regex("(?i)\\bone\\s+drive\\b") to "OneDrive",
        Regex("(?i)\\bpower\\s+point\\b") to "PowerPoint",
        Regex("(?i)\\bgoogle\\s+maps\\b") to "Google Maps",
        Regex("(?i)\\bgoogle\\s+chrome\\b") to "Google Chrome",
        Regex("(?i)\\bgoogle\\s+drive\\b") to "Google Drive",
        Regex("(?i)\\bgoogle\\s+photos\\b") to "Google Photos",
        Regex("(?i)\\bgoogle\\s+meet\\b") to "Google Meet",

        // Preposition homophone rescues where grammar is unambiguous
        Regex("(?i)\\b(alarm|timer)\\s+four\\s+(?=(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|twenty|thirty|forty|fifty|sixty|ninety|half|quarter|\\d)\\b)") to "$1 for ",
        Regex("(?i)\\b(brightness|volume)\\s+(?:two|too)\\s+(?=(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|max|maximum|min|minimum|full|half|quarter|\\d)\\b)") to "$1 to ",
        Regex("(?i)\\b(celsius|fahrenheit|kelvin|meters|metres|kilometers|kilometres|centimeters|miles|feet|inches|grams|kilograms|pounds|ounces|liters|litres|gallons|dollars|euros|naira)\\s+(?:two|too)\\s+(?=[a-z]{3,}\\b)") to "$1 to "
    )

    private val EXPLICIT_BODY_MARKER = Regex(
        "(?i)\\b(?:that\\s+says|which\\s+says|that\\s+reads|to\\s+say|saying|with\\s+the\\s+message|with\\s+the\\s+text|with\\s+a\\s+message)\\b"
    )

    private val MESSAGE_THAT_MARKER = Regex(
        "(?i)^(?:(?:hey|hi|okay|ok)\\s+ema[\\s,]+)?(?:please\\s+|could\\s+you\\s+|can\\s+you\\s+|i\\s+need\\s+to\\s+|i\\s+want\\s+to\\s+)?" +
            "(?:(?:on|in|using)\\s+[\\w'\\- ]+?\\s+)?(?:message|text|tell|inform|let|whatsapp|send)\\b[^,;]*?\\bthat\\b"
    )

    private val PACKAGE_SPELLING = Regex("(?i)\\b(package\\s+spelling\\s+)([a-z0-9 ]+)$")

    private val NON_APP_PHRASES = setOf(
        "the morning", "the afternoon", "the evening", "night", "my way", "my phone",
        "this phone", "the screen", "screen", "the background", "weekdays", "weekends",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "today", "tomorrow", "tonight", "time", "full", "half", "sim 1", "sim 2",
        "wifi", "wi-fi", "bluetooth", "airplane mode", "do not disturb", "flashlight", "hotspot"
    )

    /**
     * Normalize a raw decoded transcript into clean, natural written text.
     */
    fun normalize(
        raw: String,
        profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND,
        context: EmaSpeechContext = EmaSpeechContext.EMPTY,
        enableFormatting: Boolean = true,
        maskOffensiveWords: Boolean = false
    ): String {
        if (raw.isBlank()) return ""

        if (!enableFormatting) {
            val (plain, _) = EmaVoskJsonParser.cleanTokens(raw)
            return if (maskOffensiveWords) applyOffensiveWordMask(plain) else plain
        }

        // Preserve paragraph breaks inserted by pause-aware dictation/file assembly.
        if (profile == EmaRecognitionProfile.DICTATION && raw.contains('\n')) {
            val paragraphs = raw.split(Regex("\\n{2,}"))
                .map { p ->
                    val (cleanP, _) = EmaVoskJsonParser.cleanTokens(p.replace('\n', ' '))
                    normalizeDictation(cleanP, context)
                }
                .filter { it.isNotBlank() }
            val joined = paragraphs.joinToString("\n\n")
            return if (maskOffensiveWords) applyOffensiveWordMask(joined) else joined
        }

        val (stripped, _) = EmaVoskJsonParser.cleanTokens(raw)
        if (stripped.isEmpty()) return ""

        val result = when (profile) {
            EmaRecognitionProfile.DICTATION -> normalizeDictation(stripped, context)
            EmaRecognitionProfile.CONFIRMATION -> normalizeConfirmation(stripped, context)
            EmaRecognitionProfile.WEB_SEARCH -> normalizeWebSearch(stripped)
            EmaRecognitionProfile.COMMAND -> normalizeCommand(stripped, context)
        }
        return if (maskOffensiveWords) applyOffensiveWordMask(result) else result
    }

    /** Lightweight normalization for live streaming partials so they read naturally. */
    fun normalizePartial(raw: String, profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND): String {
        val (stripped, _) = EmaVoskJsonParser.cleanTokens(raw)
        if (stripped.isEmpty()) return ""
        return normalize(stripped, profile, EmaSpeechContext.EMPTY)
    }

    private fun normalizeCommand(stripped: String, context: EmaSpeechContext): String {
        // 1. Exact owner shortcut trigger wins verbatim before any rewriting.
        val normalizedWhole = EmaAppResolver.normalize(stripped)
        if (normalizedWhole.isNotEmpty()) {
            context.shortcuts.firstOrNull { EmaAppResolver.normalize(it) == normalizedWhole }
                ?.let { return it.trim() }
        }

        // 2. Preserve "package spelling P H O R M I" verbatim at the end of shortcut teaching.
        val pkgMatch = PACKAGE_SPELLING.find(stripped)
        if (pkgMatch != null) {
            val head = stripped.substring(0, pkgMatch.range.first).trimEnd()
            val prefixLabel = pkgMatch.groupValues[1].trim()
            val letters = pkgMatch.groupValues[2].trim().split(Regex("\\s+"))
                .joinToString(" ") { it.uppercase(Locale.ROOT) }
            val normalizedHead = normalizePronouns(head)
            return if (normalizedHead.isEmpty()) "$prefixLabel $letters" else "$normalizedHead $prefixLabel $letters"
        }

        // 3. Remove leading hesitation fillers ("um open youtube" -> "open youtube").
        val dehesitated = stripLeadingHesitations(stripped)
        if (dehesitated.isEmpty()) return ""

        // Re-check shortcut after stripping a leading "um"/"uh".
        val normAfterFiller = EmaAppResolver.normalize(dehesitated)
        context.shortcuts.firstOrNull { EmaAppResolver.normalize(it) == normAfterFiller }
            ?.let { return it.trim() }

        // 4. Split command portion from any protected free-text message/search/reminder tail.
        val (rawHead, protectedBody) = splitCommandAndBody(dehesitated)

        // 5. Clean mid-command self-repair ("open facebook scratch that open instagram"),
        //    hesitation fillers and accidental word stutters on the command head only.
        var head = applyCommandSelfRepair(rawHead)
        head = stripInlineHesitationsAndStutters(head)

        // Re-check shortcut if self-repair or stutter cleanup landed on an exact trigger.
        if (protectedBody.isEmpty()) {
            val cleanedKey = EmaAppResolver.normalize(head)
            context.shortcuts.firstOrNull { EmaAppResolver.normalize(it) == cleanedKey }
                ?.let { return it.trim() }
        }

        for ((pattern, replacement) in COMMAND_PHRASE_FIXES) {
            head = pattern.replace(head, replacement)
        }
        head = normalizeEmailsAndDomains(head, allowDomains = true)
        head = normalizeTimesAndNumbers(head, inBody = false)
        head = fixUnitConversionPreposition(head)
        head = groundSlots(head, context)
        head = normalizePronouns(head)

        if (protectedBody.isEmpty()) {
            return head.replace(Regex("\\s+"), " ").trim()
        }

        // 6. Protected content body: preserve owner wording; apply only safe email,
        //    pronoun, explicit multi-word punctuation and clock/number formatting.
        var body = protectedBody
        body = normalizeEmailsAndDomains(body, allowDomains = false)
        body = normalizeTimesAndNumbers(body, inBody = true)
        body = applyExplicitPunctuation(body, fullDictation = false)
        body = normalizePronouns(body)

        return (head.trimEnd() + " " + body.trimStart())
            .replace(Regex("\\s+([?!.,;:])"), "$1")
            .replace(Regex(" {2,}"), " ")
            .trim()
    }

    private fun normalizeConfirmation(stripped: String, context: EmaSpeechContext): String {
        var out = stripLeadingHesitations(stripped)
        if (out.isEmpty()) return ""
        out = stripInlineHesitationsAndStutters(out)
        val low = out.lowercase()
        when (low) {
            "yap", "yup", "yep" -> return "yes"
            "nah", "nope" -> return "no"
        }
        for ((pattern, replacement) in COMMAND_PHRASE_FIXES) {
            out = pattern.replace(out, replacement)
        }
        out = normalizeTimesAndNumbers(out, inBody = false)
        if (context.contactNames.isNotEmpty() || context.installedApps.isNotEmpty()) {
            out = groundSlots(out, context)
        }
        return normalizePronouns(out).replace(Regex("\\s+"), " ").trim()
    }

    private fun normalizeWebSearch(stripped: String): String {
        var out = stripLeadingHesitations(stripped)
        if (out.isEmpty()) return ""
        out = collapseStutters(out)
        out = normalizeEmailsAndDomains(out, allowDomains = true)
        out = normalizeTimesAndNumbers(out, inBody = false)
        out = applyProperNounCasing(out)
        return normalizePronouns(out).replace(Regex("\\s+"), " ").trim()
    }

    private fun normalizeDictation(text: String, context: EmaSpeechContext): String {
        if (text.isBlank()) return ""
        var out = stripLeadingHesitations(text)
        if (out.isEmpty()) return ""
        out = applyDictationScratchThat(out)
        if (out.isBlank()) return ""
        out = collapseStutters(out)
        out = normalizeEmailsAndDomains(out, allowDomains = true)
        out = normalizeTimesAndNumbers(out, inBody = true)
        out = normalizeDictationCurrencies(out)
        out = applyExplicitPunctuation(out, fullDictation = true)
        val safeOwnerNames = collectSafeCapitalizedNames(context)
        out = applyProperNounCasing(out, safeOwnerNames)
        out = normalizePronouns(out)
        out = out
            .replace(Regex("[ \\t]+([?!.,;:\\)])"), "$1")
            .replace(Regex("(\\()[ \\t]+"), "$1")
            .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
        return capitalizeSentences(out)
    }

    private fun stripLeadingHesitations(text: String): String {
        val words = text.split(Regex("\\s+"))
        var start = 0
        while (start < words.size && words[start].lowercase().trim(',', '.', '!') in LEADING_HESITATIONS) {
            start++
        }
        return if (start >= words.size) "" else words.subList(start, words.size).joinToString(" ")
    }

    private fun stripInlineHesitationsAndStutters(text: String): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return ""
        val filtered = ArrayList<String>(words.size)
        for (w in words) {
            val clean = w.lowercase().trim(',', '.')
            if (clean in INLINE_HESITATIONS) continue
            val prev = filtered.lastOrNull()?.lowercase()
            if (prev != null && prev == clean && clean !in LEGITIMATE_WORD_DOUBLES && clean !in SINGLE_DIGITS) {
                continue
            }
            filtered += w
        }
        return filtered.joinToString(" ")
    }

    private fun collapseStutters(text: String): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 2) return text
        val out = ArrayList<String>(words.size)
        for (w in words) {
            val clean = w.lowercase()
            val prev = out.lastOrNull()?.lowercase()
            if (prev != null && prev == clean && clean !in LEGITIMATE_WORD_DOUBLES &&
                clean !in SINGLE_DIGITS && clean.all { it.isLetter() }
            ) {
                continue
            }
            out += w
        }
        return out.joinToString(" ")
    }

    /**
     * Handle explicit mid-utterance command restart:
     * `"open facebook scratch that open instagram"` -> `"open instagram"`.
     */
    private fun applyCommandSelfRepair(head: String): String {
        val marker = Regex("(?i)\\b(?:scratch\\s+that|cancel\\s+that|never\\s+mind\\s+that)[,\\s]+(.+)$")
            .find(head) ?: return head
        val replacement = stripLeadingHesitations(marker.groupValues[1].trim())
        val firstWord = replacement.split(Regex("\\s+")).firstOrNull()?.lowercase().orEmpty()
        return if (firstWord in COMMAND_REPAIR_VERBS) replacement else head
    }

    /**
     * Voice-typing edit command `"scratch that"` / `"delete last sentence"`:
     * deletes the clause immediately preceding the command while keeping any
     * earlier completed sentences and anything dictated afterwards.
     */
    private fun applyDictationScratchThat(input: String): String {
        val scratchRegex = Regex("(?i)\\b(?:scratch\\s+that|delete\\s+last\\s+sentence)\\b")
        if (!scratchRegex.containsMatchIn(input)) return input

        // Temporarily turn sentence-boundary words into markers so "scratch that" stops at them.
        var working = input
        while (true) {
            val m = scratchRegex.find(working) ?: break
            val before = working.substring(0, m.range.first).trimEnd()
            val after = working.substring(m.range.last + 1).trimStart()
            val keptBefore = keepBeforeLastClause(before)
            working = when {
                keptBefore.isEmpty() -> after
                after.isEmpty() -> keptBefore
                else -> "$keptBefore $after"
            }
        }
        return working.trim()
    }

    private fun keepBeforeLastClause(before: String): String {
        if (before.isEmpty()) return ""
        // Find latest explicit sentence boundary ('.', '!', '?', '\n', or spoken 'full stop' / 'question mark' / 'exclamation mark' / 'new line' / 'new paragraph').
        val boundaryRegex = Regex("(?i)(?:[.!?\\n]|\\b(?:full\\s+stop|question\\s+mark|exclamation\\s+(?:mark|point)|new\\s+(?:line|paragraph))\\b)")
        val lastBoundary = boundaryRegex.findAll(before).lastOrNull() ?: return ""
        return before.substring(0, lastBoundary.range.last + 1).trimEnd()
    }

    /**
     * Convert unambiguous spoken email addresses and web domains:
     *  - `"alex dot okafor at gmail dot com"` -> `"alex.okafor@gmail.com"`
     *  - `"github dot com"`                   -> `"github.com"`
     *
     * Never fires on ordinary English uses of `"at"` (`"meet me at five"`) or
     * `"dot"` without a recognized domain TLD.
     */
    fun normalizeEmailsAndDomains(text: String, allowDomains: Boolean = true): String {
        if (!text.contains("dot", ignoreCase = true)) return text
        var out = text

        // 1. Spoken email addresses: "<local (with optional dot/underscore/dash)> at <domain> dot <tld> [dot <tld2>]"
        val emailRegex = Regex(
            "(?i)\\b([a-z0-9]+(?:\\s+(?:dot|underscore|dash|hyphen)\\s+[a-z0-9]+)*)\\s+at\\s+" +
                "([a-z0-9]+(?:\\s+(?:dash|hyphen)\\s+[a-z0-9]+)?)\\s+dot\\s+([a-z]{2,6})(?:\\s+dot\\s+([a-z]{2,4}))?\\b"
        )
        out = emailRegex.replace(out) { m ->
            val tld1 = m.groupValues[3].lowercase()
            val tld2 = m.groupValues[4].lowercase()
            if (tld1 !in KNOWN_EMAIL_TLDS) {
                m.value
            } else if (tld2.isNotEmpty() && tld2 !in KNOWN_EMAIL_TLDS) {
                val local = collapseEmailPart(m.groupValues[1])
                val domain = collapseEmailPart(m.groupValues[2])
                "$local@$domain.$tld1 dot ${m.groupValues[4]}"
            } else {
                val local = collapseEmailPart(m.groupValues[1])
                val domain = collapseEmailPart(m.groupValues[2])
                val suffix = if (tld2.isNotEmpty()) "$tld1.$tld2" else tld1
                "$local@$domain.$suffix"
            }
        }

        // 2. Spoken web domains: "www dot example dot com", "github dot com", "ema dot com dot ng"
        if (allowDomains && out.contains("dot", ignoreCase = true)) {
            val domainRegex = Regex(
                "(?i)(?<![@\\w])(?:(w\\s+w\\s+w|www)\\s+dot\\s+)?([a-z0-9]{2,28})\\s+dot\\s+([a-z]{2,4})(?:\\s+dot\\s+([a-z]{2,3}))?\\b"
            )
            out = domainRegex.replace(out) { m ->
                val www = m.groupValues[1]
                val host = m.groupValues[2].lowercase()
                val tld1 = m.groupValues[3].lowercase()
                val tld2 = m.groupValues[4].lowercase()
                if (host in setOf("dot", "point", "one", "two", "three")) {
                    m.value
                } else {
                    val validTwoPart = (tld1 == "co" || tld1 == "com" || tld1 == "org" || tld1 == "gov" || tld1 == "edu") &&
                        tld2 in setOf("uk", "ng", "za", "au", "in", "ke", "gh", "ca")
                    val prefix = if (www.isNotEmpty()) "www." else ""
                    when {
                        validTwoPart -> "$prefix$host.$tld1.$tld2"
                        tld1 in KNOWN_WEB_TLDS -> {
                            val base = "$prefix$host.$tld1"
                            if (tld2.isNotEmpty()) "$base dot ${m.groupValues[4]}" else base
                        }
                        else -> m.value
                    }
                }
            }
        }
        return out
    }

    private fun collapseEmailPart(part: String): String = part.lowercase()
        .replace(Regex("\\s+dot\\s+"), ".")
        .replace(Regex("\\s+underscore\\s+"), "_")
        .replace(Regex("\\s+(?:dash|hyphen)\\s+"), "-")
        .replace(Regex("\\s+"), "")

    private fun normalizeDictationCurrencies(text: String): String {
        // "$25.50" from "25 dollars and 50 cents"
        return Regex("(?i)\\b(\\d+)\\s+dollars?\\s+(?:and\\s+)?(\\d{1,2})\\s+cents?\\b")
            .replace(text) { m ->
                val dollars = m.groupValues[1]
                val cents = m.groupValues[2].padStart(2, '0')
                "$$dollars.$cents"
            }
    }

    private fun splitCommandAndBody(text: String): Pair<String, String> {
        EXPLICIT_BODY_MARKER.find(text)?.let { m ->
            val end = m.range.last + 1
            return text.substring(0, end) to text.substring(end)
        }
        MESSAGE_THAT_MARKER.find(text)?.let { m ->
            val end = m.range.last + 1
            if (end < text.length) return text.substring(0, end) to text.substring(end)
        }
        Regex("(?i)^(?:(?:open|launch)\\s+[\\w'\\- ]+?\\s+(?:and\\s+|then\\s+)?)?(?:search|look\\s+up|find)(?:\\s+[\\w'\\- ]+?)?\\s+for\\b")
            .find(text)?.let { m ->
                val end = m.range.last + 1
                if (end < text.length) return text.substring(0, end) to text.substring(end)
            }
        Regex("(?i)^(?:(?:hey|hi|okay|ok)\\s+(?:ema|emma)[\\s,]+)?(?:please\\s+)?type\\b").find(text)?.let { m ->
            val end = m.range.last + 1
            if (end < text.length) return text.substring(0, end) to text.substring(end)
        }
        return text to ""
    }

    /**
     * Convert spoken clock expressions, phone/digit sequences, compound
     * ordinals, signed decimals, compound numbers and percentages into digits
     * without touching English idioms or pronoun "one".
     */
    fun normalizeTimesAndNumbers(input: String, inBody: Boolean = false): String {
        val rawTokens = input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (rawTokens.isEmpty()) return input

        // Collapse split "o clock" -> "o'clock", "per cent" -> "percent", and
        // standalone single-letter "a m" / "p m" -> "am" / "pm".
        val tokens = ArrayList<String>(rawTokens.size)
        var idx = 0
        while (idx < rawTokens.size) {
            val cur = rawTokens[idx]
            val low = cur.lowercase()
            val nextLow = rawTokens.getOrNull(idx + 1)?.lowercase()
            when {
                (low == "a" || low == "a.") && (nextLow == "m" || nextLow == "m.") -> {
                    tokens += "am"
                    idx += 2
                }
                (low == "p" || low == "p.") && (nextLow == "m" || nextLow == "m.") -> {
                    tokens += "pm"
                    idx += 2
                }
                low == "per" && nextLow == "cent" -> {
                    tokens += "percent"
                    idx += 2
                }
                low == "o" && nextLow == "clock" -> {
                    tokens += "o'clock"
                    idx += 2
                }
                else -> {
                    tokens += cur
                    idx++
                }
            }
        }

        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val low = tokens[i].lowercase()
            val prevLow = out.lastOrNull()?.lowercase()

            // Preserve "half past six" / "quarter past seven" / "quarter to eight" intact.
            if ((low == "half" || low == "quarter") &&
                tokens.getOrNull(i + 1)?.lowercase() in setOf("past", "after", "to", "before")
            ) {
                out += tokens[i]
                out += tokens[i + 1]
                if (i + 2 < tokens.size) {
                    out += tokens[i + 2]
                    i += 3
                } else {
                    i += 2
                }
                continue
            }

            // 1. Spoken phone numbers & explicit digit-by-digit sequences
            //    ("plus two three four eight zero three double four five six seven eight nine",
            //     "zero eight zero three one two three four five six seven",
            //     "ending four two one zero").
            val digitSeq = matchSpokenDigitSequence(tokens, i, prevLow)
            if (digitSeq != null) {
                out += digitSeq.formatted
                i = digitSeq.nextIndex
                continue
            }

            // 2. Spoken clock time ("7:05 pm", "6:30 am", "7:00").
            val clockMatch = matchSpokenClock(tokens, i, prevLow)
            if (clockMatch != null) {
                out += clockMatch.formatted
                i = clockMatch.nextIndex
                continue
            }

            // 3. Compound ordinals ("twenty first" -> "21st", "thirty second" -> "32nd").
            val tenVal = TENS[low]
            val ordNext = tokens.getOrNull(i + 1)?.lowercase()?.let { ORDINAL_UNITS[it] }
            if (tenVal != null && ordNext != null) {
                out += "${tenVal + ordNext.first}${ordNext.second}"
                i += 2
                continue
            }

            // 4. Signed negative numbers in math/temperature contexts ("negative fifteen" -> "-15").
            if (low == "negative" && (prevLow == null || prevLow in NEGATIVE_MATH_LEADERS || inBody)) {
                val negNum = matchSpokenNumber(tokens, i + 1, "minus", inBody = false)
                if (negNum != null) {
                    val afterLow = tokens.getOrNull(negNum.nextIndex)?.lowercase()
                    if (afterLow == "percent") {
                        out += "-${negNum.formatted}%"
                        i = negNum.nextIndex + 1
                    } else {
                        out += "-${negNum.formatted}"
                        i = negNum.nextIndex
                    }
                    continue
                }
            }

            // 5. Spoken cardinal / compound / decimal number span + optional percent.
            val numMatch = matchSpokenNumber(tokens, i, prevLow, inBody)
            if (numMatch != null) {
                val nextLow = tokens.getOrNull(numMatch.nextIndex)?.lowercase()
                if (nextLow == "percent") {
                    out += "${numMatch.formatted}%"
                    i = numMatch.nextIndex + 1
                } else if (nextLow == "o'clock") {
                    out += "${numMatch.formatted}:00"
                    i = numMatch.nextIndex + 1
                } else {
                    out += numMatch.formatted
                    i = numMatch.nextIndex
                }
                continue
            }

            // 6. Already-numeric token followed by "percent" -> "75%".
            if (low.matches(Regex("-?\\d+(?:\\.\\d+)?")) && tokens.getOrNull(i + 1)?.lowercase() == "percent") {
                out += "${tokens[i]}%"
                i += 2
                continue
            }

            out += tokens[i]
            i++
        }

        return out.joinToString(" ")
    }

    private data class SpanResult(val formatted: String, val nextIndex: Int)

    /**
     * Match a digit-by-digit spoken sequence such as a local/international phone
     * number, trailing contact disambiguator ("ending 4210") or verification
     * code, including `"double <digit>"`, `"triple <digit>"`, `"oh"` -> `"0"`
     * and leading `"plus"` -> `"+"`.
     */
    private fun matchSpokenDigitSequence(tokens: List<String>, start: Int, prevLow: String?): SpanResult? {
        var i = start
        var hasPlus = false
        if (tokens[i].lowercase() == "plus") {
            // Only treat "plus" as international dial prefix when not right after a number ("5 plus 2").
            if (prevLow != null && (prevLow.last().isDigit() || prevLow in ONES || prevLow in TENS)) {
                return null
            }
            hasPlus = true
            i++
        }

        val sb = StringBuilder()
        while (i < tokens.size) {
            val w = tokens[i].lowercase()
            // Never swallow scale words ("one hundred") or decimal points ("three point five").
            val next = tokens.getOrNull(i + 1)?.lowercase()
            if (next in SCALES || next == "point" || next == "percent" || next == "am" || next == "pm" || next == "o'clock") {
                if (next == "am" || next == "pm" || next == "o'clock") {
                    if (sb.length <= 3) return null
                    break
                }
                if (sb.length < 2) return null
                break
            }
            if ((w == "double" || w == "triple") && next != null) {
                val repDigit = SINGLE_DIGITS[next] ?: break
                val times = if (w == "double") 2 else 3
                repeat(times) { sb.append(repDigit) }
                i += 2
                continue
            }
            val d = SINGLE_DIGITS[w]
            if (d != null) {
                sb.append(d)
                i++
                continue
            }
            if (w.length in 1..4 && w.all { it.isDigit() }) {
                sb.append(w)
                i++
                continue
            }
            break
        }

        val minDigits = when {
            hasPlus -> 6
            prevLow in DIGIT_SEQUENCE_LEADERS -> 3
            else -> 4
        }
        if (sb.length < minDigits) return null
        val prefix = if (hasPlus) "+" else ""
        return SpanResult("$prefix$sb", i)
    }

    private fun matchSpokenClock(tokens: List<String>, start: Int, prevLow: String?): SpanResult? {
        val hour = parseSmallHour(tokens[start].lowercase()) ?: return null
        val second = tokens.getOrNull(start + 1)?.lowercase() ?: return null

        // Pattern A: "seven oh five [pm]" -> "7:05 [pm]"
        if (hour in 1..23 && (second == "oh" || second == "o" || second == "zero")) {
            val unitWord = tokens.getOrNull(start + 2)?.lowercase()
            val minuteUnit = unitWord?.let { ONES[it] }?.toInt()
            if (minuteUnit != null && minuteUnit in 1..9) {
                val mer = matchMeridiem(tokens, start + 3)
                val base = "$hour:0$minuteUnit"
                return if (mer != null) SpanResult("$base ${mer.first}", mer.second)
                else SpanResult(base, start + 3)
            }
        }

        // Pattern B: "seven o'clock [am|pm]" -> "7:00 [am|pm]"
        if (hour in 1..12 && second == "o'clock") {
            val mer = matchMeridiem(tokens, start + 2)
            return if (mer != null) SpanResult("$hour:00 ${mer.first}", mer.second)
            else SpanResult("$hour:00", start + 2)
        }

        // Pattern C: "six thirty [am|pm]" / "eleven forty five [pm]"
        if (hour in 0..23) {
            val minuteMatch = parseClockMinute(tokens, start + 1)
            if (minuteMatch != null) {
                val (minute, afterMin) = minuteMatch
                val mer = matchMeridiem(tokens, afterMin)
                val timeContext = prevLow in setOf("at", "for", "by", "until", "till", "around", "before", "after", "and", "or", "to", "from", "alarm", "wake")
                val inThePart = tokens.getOrNull(afterMin)?.lowercase() == "in" &&
                    tokens.getOrNull(afterMin + 1)?.lowercase() == "the" &&
                    tokens.getOrNull(afterMin + 2)?.lowercase() in setOf("morning", "afternoon", "evening")
                val atNight = tokens.getOrNull(afterMin)?.lowercase() == "at" &&
                    tokens.getOrNull(afterMin + 1)?.lowercase() == "night"
                val afterWord = tokens.getOrNull(afterMin)?.lowercase()
                val followedByNonTimeUnit = afterWord in NUMERIC_FOLLOWERS && afterWord != "am" && afterWord != "pm"
                if (!followedByNonTimeUnit && (mer != null || inThePart || atNight || timeContext)) {
                    val mm = minute.toString().padStart(2, '0')
                    return if (mer != null) SpanResult("$hour:$mm ${mer.first}", mer.second)
                    else SpanResult("$hour:$mm", afterMin)
                }
            }
        }

        // Pattern D: "seven am" / "nine pm"
        if (hour in 1..12) {
            val mer = matchMeridiem(tokens, start + 1)
            if (mer != null) {
                return SpanResult("$hour ${mer.first}", mer.second)
            }
        }

        return null
    }

    private fun parseSmallHour(word: String): Int? {
        word.toIntOrNull()?.takeIf { it in 0..23 }?.let { return it }
        ONES[word]?.toInt()?.let { return it }
        if (word == "twenty") return 20
        return null
    }

    private fun parseClockMinute(tokens: List<String>, start: Int): Pair<Int, Int>? {
        val w1 = tokens.getOrNull(start)?.lowercase() ?: return null
        val teen = ONES[w1]?.toInt()
        if (teen != null && teen in 10..19) return teen to (start + 1)
        val ten = TENS[w1]?.toInt()
        if (ten != null && ten in 20..50) {
            val w2 = tokens.getOrNull(start + 1)?.lowercase()
            val unit = w2?.let { ONES[it]?.toInt() }
            return if (unit != null && unit in 1..9) (ten + unit) to (start + 2)
            else ten to (start + 1)
        }
        return null
    }

    private fun matchMeridiem(tokens: List<String>, index: Int): Pair<String, Int>? {
        val w = tokens.getOrNull(index)?.lowercase()?.trimEnd('.', ',', '!', '?') ?: return null
        if (w == "pm") return "pm" to (index + 1)
        if (w == "am") {
            val next = tokens.getOrNull(index + 1)?.lowercase()?.trimEnd('.', ',', '!', '?')
            if (next != null && next in VERB_AM_FOLLOWERS) return null
            return "am" to (index + 1)
        }
        return null
    }

    private fun matchSpokenNumber(
        tokens: List<String>,
        start: Int,
        prevLow: String?,
        inBody: Boolean
    ): SpanResult? {
        if (start >= tokens.size) return null
        val first = tokens[start].lowercase()
        val isNumStart = first in ONES || first in TENS ||
            ((first == "a" || first == "an") && tokens.getOrNull(start + 1)?.lowercase() in SCALES)
        if (!isNumStart) return null

        var total = 0L
        var current = 0L
        var i = start
        var consumedWords = 0
        var lastCategory = ""

        while (i < tokens.size) {
            val w = tokens[i].lowercase()

            if (w == "and" && (lastCategory == "hundred" || lastCategory == "bigscale")) {
                val next = tokens.getOrNull(i + 1)?.lowercase()
                val next2 = tokens.getOrNull(i + 2)?.lowercase()
                val followedByClock = next != null && parseSmallHour(next) != null &&
                    (parseClockMinute(tokens, i + 2) != null || next2 in setOf("am", "pm", "o'clock", "oh"))
                // Do not swallow "and 50 cents" inside "100 dollars and 50 cents" (already blocked because "dollars" ended number).
                if (!followedByClock && next != null && (next in ONES || next in TENS)) {
                    i++
                    continue
                }
                break
            }

            if ((w == "a" || w == "an") && i == start && tokens.getOrNull(i + 1)?.lowercase() in SCALES) {
                current = 1L
                lastCategory = "article"
                i++
                consumedWords++
                continue
            }

            val oneVal = ONES[w]
            if (oneVal != null) {
                val isTeen = oneVal >= 10L
                val canFollow = when (lastCategory) {
                    "" -> true
                    "ten" -> oneVal in 1L..9L
                    "hundred", "bigscale" -> true
                    else -> false
                }
                if (!canFollow) break
                current += oneVal
                lastCategory = if (isTeen) "teen" else "one"
                i++
                consumedWords++
                continue
            }

            val tenVal = TENS[w]
            if (tenVal != null) {
                val canFollow = lastCategory == "" || lastCategory == "hundred" || lastCategory == "bigscale"
                if (!canFollow) break
                current += tenVal
                lastCategory = "ten"
                i++
                consumedWords++
                continue
            }

            if (w == "hundred") {
                if (current <= 0L || current >= 100L) break
                current *= 100L
                lastCategory = "hundred"
                i++
                consumedWords++
                continue
            }

            val bigScale = SCALES[w]?.takeIf { it >= 1_000L }
            if (bigScale != null) {
                if (current <= 0L) break
                if (total > 0L && bigScale >= highestScaleIn(total)) break
                total += current * bigScale
                current = 0L
                lastCategory = "bigscale"
                i++
                consumedWords++
                continue
            }

            break
        }

        if (consumedWords == 0 || lastCategory == "article") return null
        val integerValue = total + current

        var endIndex = i
        var decimalDigits = ""
        if (tokens.getOrNull(endIndex)?.lowercase() == "point") {
            val digits = StringBuilder()
            var d = endIndex + 1
            while (d < tokens.size) {
                val dw = tokens[d].lowercase()
                val single = SINGLE_DIGITS[dw]
                if (single != null) {
                    digits.append(single)
                    d++
                } else if (digits.isEmpty() && (dw in ONES || dw in TENS)) {
                    val v = ONES[dw] ?: TENS[dw]!!
                    val nextUnit = if (dw in TENS) tokens.getOrNull(d + 1)?.lowercase()?.let { ONES[it] }?.takeIf { it in 1..9 } else null
                    if (nextUnit != null) {
                        digits.append(v + nextUnit)
                        d += 2
                    } else {
                        digits.append(v)
                        d++
                    }
                    break
                } else {
                    break
                }
            }
            if (digits.isNotEmpty()) {
                decimalDigits = digits.toString()
                endIndex = d
            }
        }

        val multiWord = (endIndex - start) >= 2
        val nextLow = tokens.getOrNull(endIndex)?.lowercase()?.trimEnd('.', ',', '!', '?')

        if (!multiWord && first == "one") {
            val explicitlyNumeric = nextLow in NUMERIC_FOLLOWERS || prevLow in setOf(
                "plus", "minus", "times", "over", "mod", "modulo", "sim", "number", "level", "channel", "convert", "option", "choice"
            )
            if (!explicitlyNumeric) return null
            if (prevLow in PRONOUN_ONE_PREV || nextLow in PRONOUN_ONE_NEXT) return null
        }

        if (inBody && !multiWord) {
            val anchored = nextLow in NUMERIC_FOLLOWERS || prevLow in setOf("in", "at", "around", "by", "sim", "plus", "minus", "times")
            if (!anchored) return null
        }

        if (!inBody && !multiWord) {
            val anchored = nextLow in NUMERIC_FOLLOWERS ||
                prevLow in NUMERIC_LEADERS ||
                (nextLow == "and" && tokens.getOrNull(endIndex + 1)?.lowercase()?.let { it in ONES || it in TENS || it.firstOrNull()?.isDigit() == true } == true) ||
                prevLow?.lastOrNull()?.isDigit() == true
            if (!anchored) return null
        }

        val formatted = if (decimalDigits.isEmpty()) integerValue.toString() else "$integerValue.$decimalDigits"
        return SpanResult(formatted, endIndex)
    }

    private fun highestScaleIn(value: Long): Long = when {
        value >= 1_000_000_000L -> 1_000_000_000L
        value >= 1_000_000L -> 1_000_000L
        else -> 1_000L
    }

    private fun fixUnitConversionPreposition(text: String): String = Regex(
        "(?i)\\b(convert\\s+-?[\\d.]+\\s+(?:degrees\\s+)?[a-z]+)\\s+(?:2|two|too)\\s+(?=(?:degrees\\s+)?[a-z]+\\b)"
    ).replace(text, "$1 to ")

    /**
     * Slot-scoped phonetic grounding against installed app labels, contact
     * names and visible screen controls. Runs only on isolated slot spans,
     * never on free text, and leaves ambiguous matches untouched.
     */
    private fun groundSlots(head: String, context: EmaSpeechContext): String {
        var out = head
        val shortcutSkip = context.shortcuts.mapTo(HashSet()) { it.lowercase() }

        if (context.installedApps.isNotEmpty()) {
            val appSkip = NON_APP_PHRASES + shortcutSkip
            out = replaceSlot(
                text = out,
                pattern = Regex("(?i)\\b(open|launch|start)\\s+(?:the\\s+)?([a-z0-9' ]{2,34}?)(?:\\s+app)?(?=$|\\s+(?:and|then|to|search|find|look|play|message|text|send|go)\\b|[,;])"),
                minScore = 0.88,
                minMargin = 0.09,
                skipSet = appSkip,
                resolver = { slot ->
                    EmaPhoneticMatcher.bestUnambiguousMatch(slot, context.installedApps, minScore = 0.88, minMargin = 0.09)
                }
            )
            out = replaceSlot(
                text = out,
                pattern = Regex("(?i)\\b(on|in|using|via|through)\\s+([a-z0-9' ]{2,34}?)(?=$|\\s+(?:and|then|message|text|send|tell|inform|let|saying|that|to|for|search|find|play)\\b|[,;])"),
                minScore = 0.89,
                minMargin = 0.09,
                skipSet = appSkip,
                resolver = { slot ->
                    EmaPhoneticMatcher.bestUnambiguousMatch(slot, context.installedApps, minScore = 0.89, minMargin = 0.09)
                }
            )
        }

        // Ground on-screen UI action targets ("tap Paystack Checkout", "click Sign In").
        if (context.screenLabels.isNotEmpty()) {
            out = replaceSlot(
                text = out,
                pattern = Regex("(?i)\\b(tap|click|press|select|choose)\\s+(?:on\\s+)?(?:the\\s+)?([a-z0-9' ]{2,40}?)(?:\\s+(?:button|tab|link|icon|option))?(?=$|\\s+(?:and|then|please|now)\\b|[,;])"),
                minScore = 0.89,
                minMargin = 0.09,
                skipSet = setOf("it", "this", "that", "first", "second", "third", "back", "home") + shortcutSkip,
                resolver = { slot ->
                    EmaPhoneticMatcher.bestUnambiguousMatch(slot, context.screenLabels, minScore = 0.89, minMargin = 0.09)
                }
            )
        }

        val people = if (context.ownerAliases.isEmpty()) context.contactNames
        else context.contactNames + context.ownerAliases
        if (people.isNotEmpty()) {
            val contactResolver: (String) -> EmaPhoneticMatcher.Match? = { slot ->
                EmaPhoneticMatcher.bestContactSlotMatch(slot, people, minScore = 0.89, minMargin = 0.10)
            }
            out = replaceSlot(
                text = out,
                pattern = Regex("(?i)\\b(call|dial|phone|ring)\\s+([a-z' ]{2,34}?)(?=$|\\s+(?:with|using|on|via|ending|and|then|now|please)\\b|[,;])"),
                minScore = 0.89,
                minMargin = 0.10,
                skipSet = setOf("me", "him", "her", "them", "back", "my wife", "my husband", "my mum", "my dad"),
                resolver = contactResolver
            )
            out = replaceSlot(
                text = out,
                pattern = Regex("(?i)\\b(message|text|tell|inform|whatsapp)\\s+([a-z' ]{2,34}?)(?=\\s+(?:on|in|using|via|through|saying|that|to\\s+say|with\\s+the)\\b)"),
                minScore = 0.89,
                minMargin = 0.10,
                skipSet = setOf("me", "him", "her", "them", "my wife", "my husband"),
                resolver = contactResolver
            )
            out = replaceSlot(
                text = out,
                pattern = Regex("(?i)\\b(send\\s+(?:a\\s+(?:\\w+\\s+)?message\\s+to|to))\\s+([a-z' ]{2,34}?)(?=\\s+(?:on|in|using|via|saying|that|to\\s+say)\\b|$)"),
                minScore = 0.89,
                minMargin = 0.10,
                skipSet = setOf("me", "him", "her", "them"),
                resolver = contactResolver
            )
        }
        return out
    }

    private fun replaceSlot(
        text: String,
        pattern: Regex,
        @Suppress("UNUSED_PARAMETER") minScore: Double,
        @Suppress("UNUSED_PARAMETER") minMargin: Double,
        skipSet: Set<String>,
        resolver: (String) -> EmaPhoneticMatcher.Match?
    ): String = pattern.replace(text) { match ->
        val lead = match.groupValues[1]
        val slot = match.groupValues[2].trim()
        val slotLow = slot.lowercase()
        if (slotLow in skipSet || slot.any { it.isDigit() }) {
            match.value
        } else {
            val resolved = resolver(slot)
            if (resolved != null) "$lead ${resolved.canonical}" else match.value
        }
    }

    private fun applyExplicitPunctuation(text: String, fullDictation: Boolean): String {
        var out = text
            .replace(Regex("(?i)\\bnew\\s+paragraph\\b"), "\n\n")
            .replace(Regex("(?i)\\bnew\\s+line\\b"), "\n")
            .replace(Regex("(?i)\\bquestion\\s+mark\\b"), "?")
            .replace(Regex("(?i)\\bexclamation\\s+(?:mark|point)\\b"), "!")
            .replace(Regex("(?i)\\bfull\\s+stop\\b"), ".")
            .replace(Regex("(?i)\\bdot\\s+dot\\s+dot\\b"), "...")
        if (fullDictation) {
            val periodNounModifiers = setOf(
                "a", "the", "this", "that", "time", "grace", "waiting",
                "trial", "billing", "notice", "short", "long", "brief", "extended", "pay"
            )
            out = Regex("(?i)(?:(\\p{L}+)\\s+)?\\bperiod\\b(?=\\s|$)").replace(out) { m ->
                val prev = m.groupValues[1]
                when {
                    prev.isEmpty() -> "."
                    prev.lowercase() in periodNounModifiers -> m.value
                    else -> "$prev."
                }
            }
            out = out
                .replace(Regex("(?i)\\bellipsis\\b"), "...")
                .replace(Regex("(?i)\\bopen\\s+(?:quote|quotes|quotation)\\b"), "\"")
                .replace(Regex("(?i)\\b(?:close|end)\\s+(?:quote|quotes|quotation)\\b"), "\"")
                .replace(Regex("(?i)\\bopen\\s+(?:parenthesis|bracket)\\b"), "(")
                .replace(Regex("(?i)\\b(?:close|end)\\s+(?:parenthesis|bracket)\\b"), ")")
                .replace(Regex("(?i)\\b(?:semicolon|semi\\s+colon)\\b"), ";")
                .replace(Regex("(?i)\\bcomma\\b"), ",")
                .replace(Regex("(?i)\\bcolon\\b"), ":")
                .replace(Regex("(?i)\\bem\\s+dash\\b"), " — ")
                .replace(Regex("(?i)\\bat\\s+sign\\b"), "@")
                .replace(Regex("(?i)\\b(?:hashtag|hash\\s+tag)\\s+([a-z0-9]+)\\b"), "#$1")
                .replace(Regex("(?i)([a-z0-9])\\s+hyphen\\s+([a-z0-9])"), "$1-$2")
                .replace(Regex("(?i)([a-z0-9._-]+\\.[a-z]{2,4})\\s+(?:forward\\s+)?slash\\s+([a-z0-9_-]+)"), "$1/$2")
        }
        return out
    }

    private fun collectSafeCapitalizedNames(context: EmaSpeechContext): Map<String, String> {
        if (context.contactNames.isEmpty() && context.ownerAliases.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (raw in context.contactNames + context.ownerAliases) {
            for (part in raw.trim().split(Regex("\\s+"))) {
                if (part.length >= 3 && part[0].isUpperCase() && part.all { it.isLetter() || it == '\'' }) {
                    val low = part.lowercase()
                    if (low !in AMBIGUOUS_NAME_WORDS && low !in PROPER_NOUN_CASING) {
                        map.putIfAbsent(low, part)
                    }
                }
            }
        }
        return map
    }

    private fun applyProperNounCasing(text: String, ownerNames: Map<String, String> = emptyMap()): String {
        if (text.isEmpty()) return text
        // Protect email addresses and web domains from internal word capitalization.
        return Regex("(?<![@./\\p{L}'])([a-zA-Z']+)(?![@\\p{L}])").replace(text) { m ->
            val word = m.groupValues[1]
            // Skip tokens that are immediately before a domain TLD (e.g. "github.com").
            val afterIdx = m.range.last + 1
            if (afterIdx < text.length && text[afterIdx] == '.' &&
                afterIdx + 1 < text.length && text[afterIdx + 1].isLetter()
            ) {
                word
            } else {
                val low = word.lowercase()
                PROPER_NOUN_CASING[low] ?: ownerNames[low] ?: word
            }
        }
    }

    private fun applyOffensiveWordMask(text: String): String =
        Regex("\\b([a-zA-Z]+)\\b").replace(text) { m ->
            val word = m.groupValues[1]
            if (word.lowercase() in OFFENSIVE_WORDS && word.length >= 2) {
                word[0] + "*".repeat(word.length - 1)
            } else {
                word
            }
        }

    private fun normalizePronouns(text: String): String = text
        .replace(Regex("(?<![\\p{L}'@./])i(?![\\p{L}'@])"), "I")
        .replace(Regex("(?<![\\p{L}'])i'm(?![\\p{L}])"), "I'm")
        .replace(Regex("(?<![\\p{L}'])i'll(?![\\p{L}])"), "I'll")
        .replace(Regex("(?<![\\p{L}'])i've(?![\\p{L}])"), "I've")
        .replace(Regex("(?<![\\p{L}'])i'd(?![\\p{L}])"), "I'd")

    private fun capitalizeSentences(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var capNext = true
        var idx = 0
        while (idx < text.length) {
            val ch = text[idx]
            if (capNext && ch.isLetter()) {
                // Do not uppercase the start of an email address or URL.
                val rest = text.substring(idx).takeWhile { !it.isWhitespace() }
                val isEmailOrUrl = '@' in rest || rest.startsWith("www.") ||
                    Regex("^[a-z0-9-]+\\.(?:com|org|net|edu|gov|io|ai|app|dev|ng|co)\\b").containsMatchIn(rest)
                sb.append(if (isEmailOrUrl) ch else ch.uppercaseChar())
                capNext = false
            } else {
                sb.append(ch)
                if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                    // Do not trigger sentence capitalization on dots inside "alex.okafor@gmail.com".
                    val nextChar = text.getOrNull(idx + 1)
                    if (ch != '.' || nextChar == null || nextChar.isWhitespace()) {
                        capNext = true
                    }
                } else if (!ch.isWhitespace() && ch != '"' && ch != '(') {
                    capNext = false
                }
            }
            idx++
        }
        return sb.toString()
    }
}
