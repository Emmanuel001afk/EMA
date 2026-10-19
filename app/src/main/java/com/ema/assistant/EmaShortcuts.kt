package com.ema.assistant

/**
 * User-taught shortcuts: a spoken trigger mapped either to an installed app
 * (package name + label) or to a whole command.
 *
 * Everything here is pure Kotlin so the parsing, resolution and confirmation
 * rules are unit tested on the JVM. Installed applications are passed in; no
 * vendor's app is named anywhere.
 *
 * Invariants:
 *  - A trigger is an exact, normalised phrase. It is never substituted inside
 *    free text (message bodies, search queries, reminder text).
 *  - Spelled-out package names ("P H O R M I") resolve against the real
 *    package names of installed applications.
 *  - When more than one installed app matches, EMA asks instead of guessing.
 */
object EmaShortcuts {

    sealed class Target {
        /** An installed application, identified by its package name. */
        data class App(val packageName: String, val label: String) : Target()
        /** A stored command, run through the normal pipeline (and its PLAN). */
        data class Command(val text: String) : Target()
    }

    data class Definition(
        val triggers: List<String>,
        val meaning: String,
        val packageSpelling: String? = null
    )

    sealed class Resolution {
        data class App(val app: EmaAppResolver.App) : Resolution()
        data class Ambiguous(val apps: List<EmaAppResolver.App>) : Resolution()
        data class Command(val text: String) : Resolution()
        /** Not an app or a command: keep the old contact-name alias behaviour. */
        data class Alias(val meaning: String) : Resolution()
        object Unresolved : Resolution()
    }

    /** Words that start a device command rather than name an application. */
    private val COMMAND_VERBS = setOf(
        "open", "launch", "start", "run", "play", "pause", "stop", "resume", "call", "dial",
        "send", "message", "text", "whatsapp", "search", "find", "google", "turn", "enable",
        "disable", "set", "increase", "decrease", "raise", "lower", "remind", "navigate",
        "go", "take", "read", "show", "scroll", "tap", "press", "type", "schedule", "lock",
        "mute", "unmute", "screenshot", "back", "home"
    )

    /** App-name decoration stripped before matching: "open the Phormi app". */
    private val LEADING_VERB = Regex("(?i)^(?:open|launch|start|run|play|show|go\\s+to)\\s+(?:the\\s+|my\\s+|a\\s+|an\\s+)?")

    /**
     * Canonical trigger form: lower case, punctuation removed, wake words and
     * politeness stripped. "Hey EMA, for me please" -> "for me".
     */
    fun normalizeTrigger(raw: String): String {
        var text = raw.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        var guard = 0
        while (guard++ < 6) {
            val stripped = text
                .replace(Regex("^(?:(?:hey|ok|okay|hi|hello)\\s+)?ema\\b[\\s,]*"), "")
                .replace(Regex("^(?:please|kindly)\\s+"), "")
                .replace(Regex("\\s+(?:please)$"), "")
                .replace(Regex("^[,;:\\s]+"), "")
                .trim()
            if (stripped == text) break
            text = stripped
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /** Whole-utterance trigger lookup. Ignoring wake words, fillers and "please". */
    fun trigger(utterance: String): String? {
        val key = normalizeTrigger(utterance)
        return key.ifBlank { null }
    }

    /** Join spelled letters: "P H O R M I" -> "phormi", "P.H.O.R.M.I" -> "phormi". */
    fun spelledPackage(raw: String): String? {
        val text = raw.trim().trimEnd('.', ',', ';')
        if (text.isBlank()) return null
        // A literal package name was dictated: "com.phormi.app".
        if (text.contains('.') && !Regex("^[A-Za-z](\\s*[.\\-]\\s*[A-Za-z])+$").matches(text)) return text.lowercase()
        val tokens = text.replace(Regex("[.\\-]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return if (tokens.all { it.length == 1 && it[0].isLetterOrDigit() }) {
            tokens.joinToString("").lowercase()
        } else {
            null
        }
    }

    /**
     * Remove a trailing "package spelling ..." clause from a definition.
     *
     * The clause arrives in many shapes - "package spelling: P H O R M I",
     * "(package spelling: P H O R M I)", "and the package name is com.phormi" -
     * so brackets, colons and commas are all accepted. The rest of the
     * meaning is kept as it was, because that is what says *what* the shortcut
     * does ("open the Phormi app").
     */
    fun splitPackageClause(meaning: String): Pair<String, String?> {
        val match = Regex(
            "(?i)(?:[,;]\\s*|\\s+|[(\\[{]\\s*)?(?:and\\s+)?(?:the\\s+)?\\bpackage\\s*(?:name)?\\s*" +
                "(?:spelling|spelt|spelled|is|called)?\\s*[:\\-]?\\s*(.+?)\\s*[)\\]}]?\\s*$"
        ).find(meaning) ?: return meaning.trim() to null
        val prefix = meaning.substring(0, match.range.first).trim().trimEnd(',', ';', ':', '(', '[', '{')
        val spelling = spelledPackage(match.groupValues[1].trim())
        return prefix to spelling
    }

    private fun splitTriggers(raw: String): List<String> {
        val cleaned = raw.trim().trim('"', '\'', ',').trim()
        if (cleaned.isBlank()) return emptyList()
        return cleaned
            .split(Regex("(?i)\\s+or\\s+|\\s*[,;]\\s*"))
            .map { it.trim().trim('"', '\'', ',', ';', ':').trim() }
            .filter { normalizeTrigger(it).isNotBlank() }
            .distinctBy { normalizeTrigger(it) }
    }

    /** Detect and parse a spoken definition, with or without quote marks. */
    fun parseDefinition(text: String): Definition? {
        val cleaned = text.trim().trimEnd('.', '!', '?').trim()

        // A quoted definition is still accepted, with or without "remember".
        Regex("^[\"']([^\"']+)[\"']\\s+means\\s+(.+)$").find(cleaned)?.let { m ->
            return definition(m.groupValues[1], m.groupValues[2])
        }

        // "remember (that) whenever/when/if I say X (or Y) it means / I mean ..."
        Regex(
            "(?i)^(?:please\\s+)?remember\\s+(?:that\\s+)?(?:whenever|when|if)\\s+i\\s+say\\s+(.+?)\\s*,?\\s*(?:it\\s+means|that\\s+means|i\\s+mean|means)\\s+(.+)$"
        ).find(cleaned)?.let { m -> return definition(m.groupValues[1], m.groupValues[2]) }

        // "remember (that) whenever/when I say X, open Z"
        Regex(
            "(?i)^(?:please\\s+)?remember\\s+(?:that\\s+)?(?:whenever|when|if)\\s+i\\s+say\\s+(.+?)\\s*,?\\s*(open|launch|start|run|play)\\s+(.+)$"
        ).find(cleaned)?.let { m -> return definition(m.groupValues[1], "${m.groupValues[2]} ${m.groupValues[3]}") }

        // "remember (that) X means Y" (quotes optional)
        Regex("(?i)^(?:please\\s+)?remember\\s+(?:that\\s+)?(.+?)\\s+means\\s+(.+)$").find(cleaned)?.let { m ->
            return definition(m.groupValues[1], m.groupValues[2])
        }

        // "when I say X I mean Y" / "when I say X, open Z" (explicit teaching, no "remember")
        Regex("(?i)^(?:whenever|when|if)\\s+i\\s+say\\s+(.+?)\\s*,?\\s*(?:it\\s+means|that\\s+means|i\\s+mean|means)\\s+(.+)$")
            .find(cleaned)?.let { m -> return definition(m.groupValues[1], m.groupValues[2]) }
        Regex("(?i)^(?:whenever|when|if)\\s+i\\s+say\\s+(.+?)\\s*,?\\s*(open|launch|start|run|play)\\s+(.+)$")
            .find(cleaned)?.let { m -> return definition(m.groupValues[1], "${m.groupValues[2]} ${m.groupValues[3]}") }
        return null
    }

    private fun definition(triggerRaw: String, meaningRaw: String): Definition? {
        val triggers = splitTriggers(triggerRaw)
        if (triggers.isEmpty()) return null
        val (meaning, spelling) = splitPackageClause(meaningRaw.trim().trim('"', '\'', ' '))
        if (meaning.isBlank()) return null
        return Definition(triggers, meaning, spelling)
    }

    private fun stripDecorations(meaning: String): String = meaning
        .trim()
        .trimEnd('.', '!', ',', ';')
        .replace(LEADING_VERB, "")
        .replace(Regex("(?i)\\s+(?:app|application)$"), "")
        .replace(Regex("(?i)^(?:the|my|a|an)\\s+"), "")
        .trim()

    /** True when the meaning names a whole command rather than a single app. */
    fun looksLikeCommand(meaning: String): Boolean {
        val text = meaning.trim()
        val first = text.substringBefore(' ').lowercase()
        if (first !in COMMAND_VERBS) return false
        // A bare verb + app name is an app shortcut, not a command.
        val withoutVerb = text.replaceFirst(Regex("(?i)^(?:open|launch|start|run|show)\\s+"), "").trim()
        if (withoutVerb.isBlank()) return true
        val clauses = Regex("(?i)\\b(?:and\\s+then|then|and)\\b").split(text).size
        if (clauses > 1) return true
        return first !in setOf("open", "launch", "start", "run", "show")
    }

    /** Resolve a definition against the real installed applications. */
    fun resolve(
        meaningRaw: String,
        packageSpelling: String?,
        installed: List<EmaAppResolver.App>
    ): Resolution {
        val meaning = meaningRaw.trim().trim('"', '\'', ' ')
        if (meaning.isBlank()) return Resolution.Unresolved

        // 1. A dictated package spelling is the strongest identity evidence.
        // More specific matches win: an exact package segment or an exact label
        // ("Phormi") beats a longer package/label that merely contains it
        // ("Phormi Browser"), so "package spelling P H O R M I" is not
        // ambiguous just because a second app shares the prefix.
        if (!packageSpelling.isNullOrBlank() && installed.isNotEmpty()) {
            val scored = installed.map { it to packageScore(it, packageSpelling) }.filter { it.second > 0 }
            val best = scored.maxOfOrNull { it.second } ?: 0
            val matches = scored.filter { it.second == best }.map { it.first }
            when {
                matches.size == 1 -> return Resolution.App(matches[0])
                matches.size > 1 -> return Resolution.Ambiguous(matches)
            }
        }

        // 2. "open the Phormi app" / "the Phormi app" / "Phormi" against installed apps.
        val candidate = stripDecorations(meaning)
        if (candidate.isNotBlank()) {
            when (val grounding = EmaAppResolver.ground(candidate, installed)) {
                is EmaAppResolver.Grounding.One -> return Resolution.App(grounding.app)
                is EmaAppResolver.Grounding.Ambiguous -> return Resolution.Ambiguous(grounding.apps)
                else -> Unit
            }
            // A package leaf is not always equal to the label: "phormi".
            val scored = installed.map { it to packageScore(it, candidate) }.filter { it.second > 0 }
            val best = scored.maxOfOrNull { it.second } ?: 0
            val packageMatches = scored.filter { it.second == best }.map { it.first }
            if (packageMatches.size == 1) return Resolution.App(packageMatches[0])
            if (packageMatches.size > 1) return Resolution.Ambiguous(packageMatches)
        }

        // 3. Anything that describes an action is a stored command.
        if (looksLikeCommand(meaning)) return Resolution.Command(meaning)

        // 4. Otherwise preserve the original alias behaviour (a person's name).
        return Resolution.Alias(meaning)
    }

    /** How strongly an installed app's identity matches a spoken token. */
    private fun packageScore(app: EmaAppResolver.App, token: String): Int {
        val wanted = token.lowercase().replace(Regex("[\\s._\\-]"), "")
        if (wanted.isBlank()) return 0
        val pkg = app.packageName.lowercase()
        val segments = pkg.split('.').filter { it.isNotBlank() }
            .map { it.replace(Regex("[_\\-]"), "") }
        val label = EmaAppResolver.normalize(app.label).replace(" ", "")
        return when {
            pkg == token.lowercase() || label == wanted -> 4
            segments.isNotEmpty() && segments.last() == wanted -> 3
            segments.any { it == wanted } -> 2
            pkg.replace(Regex("[._\\-]"), "").contains(wanted) -> 1
            else -> 0
        }
    }

    /** Human-readable confirmation of exactly what was stored. */
    fun confirmation(triggers: List<String>, target: Target): String {
        val quoted = when (triggers.size) {
            1 -> "'${triggers[0]}'"
            2 -> "'${triggers[0]}' and '${triggers[1]}'"
            else -> triggers.dropLast(1).joinToString(", ") { "'$it'" } + " and '${triggers.last()}'"
        }
        return when (target) {
            is Target.App -> "OK — $quoted will open ${target.label}."
            is Target.Command -> "OK — $quoted will run: ${target.text}."
        }
    }
}
