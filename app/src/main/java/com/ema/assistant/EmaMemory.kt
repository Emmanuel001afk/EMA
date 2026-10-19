package com.ema.assistant

/**
 * Long-term, user-controlled memory.
 *
 * Three small, separate tables, all visible and erasable in Settings:
 *  - facts     "remember that my gate code is ..." is refused (secrets), but
 *              "remember that my doctor is Dr Okafor" is kept
 *  - aliases   "when I say portal I mean Acme Staff Portal": a bounded,
 *              explicit way to teach EMA the names of private apps and people
 *  - defaults  "use Telegram for messages", "use YouTube for videos"
 *  - shortcuts "when I say for me it means open Phormi": a trigger phrase mapped
 *              either to an installed app (package name + label) or to a whole
 *              command. Shortcuts are applied by every routing layer; they are
 *              never substituted inside message bodies, queries or reminders.
 *
 * This is the only on-device adaptation EMA performs, and it happens only when
 * the user explicitly asks. Short-term state (the active result list) lives in
 * [EmaConversationContext] and expires on its own; message recipients and
 * bodies are never remembered.
 */
data class EmaMemory(
    val facts: Map<String, String> = emptyMap(),
    val aliases: Map<String, String> = emptyMap(),
    val defaults: Map<String, String> = emptyMap(),
    /** Normalised trigger -> target. See [EmaShortcuts]. */
    val shortcuts: Map<String, EmaShortcuts.Target> = emptyMap()
) {
    sealed class Op {
        data class Remember(val key: String, val value: String, val display: String) : Op()
        data class Recall(val key: String) : Op()
        data class Forget(val key: String) : Op()
        object ForgetAll : Op()
        object ListAll : Op()
        data class Alias(val spoken: String, val meaning: String) : Op()
        /** A surface-form equivalence ("X is the same as Y"), stored toward Y as its canonical form. */
        data class Synonym(val first: String, val second: String) : Op()
        /** A spoken definition; resolved against installed apps by [apply]. */
        data class Shortcut(val definition: EmaShortcuts.Definition) : Op()
        data class SetDefault(val kind: String, val app: String) : Op()
        data class Refused(val reason: String) : Op()
    }

    data class Applied(
        val memory: EmaMemory,
        val reply: String,
        /** Set when the definition matched several apps and needs an answer. */
        val pendingShortcut: EmaShortcuts.Definition? = null,
        val candidateApps: List<String> = emptyList()
    )

    fun apply(op: Op, installedApps: List<EmaAppResolver.App> = emptyList()): Applied? = when (op) {
        is Op.Remember -> {
            if (facts.size >= MAX_FACTS && op.key !in facts) Applied(this, "My memory is full ($MAX_FACTS items). Ask me to forget something first.")
            else Applied(copy(facts = facts + (op.key to op.value)), "OK, I'll remember that ${op.display}.")
        }
        is Op.Recall -> facts[op.key]?.let { Applied(this, "${possessive(op.key)} ${if (op.key.endsWith("s") && !op.key.endsWith("ss")) "are" else "is"} $it.") }
        is Op.Forget -> {
            val k = op.key
            when {
                k in facts -> Applied(copy(facts = facts - k), "I've forgotten ${possessive(k).replaceFirstChar { it.lowercase() }}.")
                k in aliases -> Applied(copy(aliases = aliases - k), "I've forgotten what \"$k\" means.")
                k in shortcuts -> {
                    val shortcut = shortcuts[k]
                    val category = EmaLexicon.appKindIn(k)
                    val anotherTrigger = category != null && shortcuts.any { (trigger, target) ->
                        trigger != k && EmaLexicon.appKindIn(trigger) == category && target == shortcut
                    }
                    val removeDefault = shortcut is EmaShortcuts.Target.App && category != null &&
                        defaults[category] == shortcut.label && !anotherTrigger
                    Applied(copy(shortcuts = shortcuts - k, defaults = if (removeDefault) defaults - category!! else defaults), "I've forgotten the shortcut \"$k\".")
                }
                else -> Applied(this, "I don't have anything saved about $k.")
            }
        }
        Op.ForgetAll -> Applied(EmaMemory(), "I've cleared everything you asked me to remember, including shortcuts, names you taught me and default apps.")
        Op.ListAll -> Applied(this, describe())
        is Op.Alias -> {
            if (aliases.size >= MAX_ALIASES && op.spoken !in aliases) Applied(this, "I already know $MAX_ALIASES names. Ask me to forget one first.")
            else Applied(copy(aliases = aliases + (op.spoken to op.meaning)), "OK. When you say \"${op.spoken}\", I'll use ${op.meaning}.")
        }
        is Op.Synonym -> {
            val left = normKey(op.first)
            val right = normKey(op.second)
            if (left.isBlank() || right.isBlank()) Applied(this, "I didn't catch both words to link.")
            else if (SECRET.containsMatchIn(left) || SECRET.containsMatchIn(right)) Applied(this, "I don't store passwords, PINs, codes or card and bank details. Please keep those in a password manager.")
            else if (aliases.size + (if (left !in aliases) 1 else 0) > MAX_ALIASES) Applied(this, "I already know $MAX_ALIASES names. Ask me to forget one first.")
            else Applied(copy(aliases = aliases + (left to right)), "OK. I'll treat \"${op.first.trim()}\" as another way to say \"${op.second.trim()}\".")
        }
        is Op.SetDefault -> Applied(copy(defaults = defaults + (op.kind to op.app)), "OK. I'll use ${op.app} for ${kindName(op.kind)} when you don't name an app.")
        is Op.Shortcut -> applyShortcut(op.definition, installedApps)
        is Op.Refused -> Applied(this, op.reason)
    }

    private fun applyShortcut(
        definition: EmaShortcuts.Definition,
        installedApps: List<EmaAppResolver.App>
    ): Applied {
        val displayTriggers = definition.triggers.filter { it.isNotBlank() }
        if (displayTriggers.isEmpty()) return Applied(this, "I didn't catch the phrase to remember.")
        if (SECRET.containsMatchIn(displayTriggers.joinToString(" ")) || SECRET.containsMatchIn(definition.meaning)) {
            return Applied(this, "I don't store passwords, PINs, codes or card and bank details. Please keep those in a password manager.")
        }
        val triggers = displayTriggers.map { EmaShortcuts.normalizeTrigger(it) }.filter { it.isNotBlank() }.distinct()
        if (triggers.isEmpty()) return Applied(this, "I didn't catch the phrase to remember.")
        val newKeys = triggers.filter { it !in shortcuts }
        if (shortcuts.size + newKeys.size > MAX_SHORTCUTS) {
            return Applied(this, "I already know $MAX_SHORTCUTS shortcuts. Ask me to forget one first.")
        }
        return when (val resolution = EmaShortcuts.resolve(definition.meaning, definition.packageSpelling, installedApps)) {
            is EmaShortcuts.Resolution.Ambiguous -> Applied(
                this,
                "More than one installed app matches \"${definition.meaning}\": " +
                    resolution.apps.joinToString(" or ") { it.label } + ". Which one do you mean?",
                pendingShortcut = definition,
                candidateApps = resolution.apps.map { it.label }
            )
            is EmaShortcuts.Resolution.App -> {
                val applied = withShortcut(
                    displayTriggers,
                    EmaShortcuts.Target.App(resolution.app.packageName, resolution.app.label)
                )
                val category = displayTriggers.firstNotNullOfOrNull(EmaLexicon::appKindIn)
                if (category == null) applied else applied.copy(
                    memory = applied.memory.copy(defaults = applied.memory.defaults + (category to resolution.app.label)),
                    reply = applied.reply + " I will prefer it for $category requests too."
                )
            }
            is EmaShortcuts.Resolution.Command ->
                withShortcut(displayTriggers, EmaShortcuts.Target.Command(resolution.text))
            is EmaShortcuts.Resolution.Alias -> {
                var updated = this
                var reply = ""
                for (trigger in triggers) {
                    val applied = updated.apply(Op.Alias(normKey(trigger), resolution.meaning))
                    if (applied != null) { updated = applied.memory; reply = applied.reply }
                }
                Applied(updated, reply)
            }
            EmaShortcuts.Resolution.Unresolved -> Applied(
                this,
                "I couldn't match \"${definition.meaning}\" to an installed app or a command. " +
                    "Name the installed app or describe the action you want this phrase to mean."
            )
        }
    }

    /** Store an already-resolved target. Used after the user answers an ambiguity. */
    fun withShortcut(triggers: List<String>, target: EmaShortcuts.Target): Applied {
        val display = triggers.filter { it.isNotBlank() }
        val keys = display.map { EmaShortcuts.normalizeTrigger(it) }.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return Applied(this, "I didn't catch the phrase to remember.")
        val added = keys.count { it !in shortcuts }
        if (shortcuts.size + added > MAX_SHORTCUTS) return Applied(this, "I already know $MAX_SHORTCUTS shortcuts. Ask me to forget one first.")
        return Applied(copy(shortcuts = shortcuts + keys.associateWith { target }), EmaShortcuts.confirmation(display, target))
    }

    /** The app shortcut for a spoken phrase, if one is stored. */
    fun appShortcut(phrase: String): EmaShortcuts.Target.App? {
        val key = EmaShortcuts.normalizeTrigger(phrase)
        if (key.isBlank()) return null
        (shortcuts[key] as? EmaShortcuts.Target.App)?.let { return it }
        val stripped = key.replace(Regex("^(?:my|our|the)\\s+"), "")
        (shortcuts[stripped] as? EmaShortcuts.Target.App)?.let { return it }
        // A trigger saved as "my portal" is also reachable by "portal".
        return shortcuts.entries.firstOrNull { (trigger, target) ->
            target is EmaShortcuts.Target.App &&
                trigger.replace(Regex("^(?:my|our|the)\\s+"), "") == stripped
        }?.value as? EmaShortcuts.Target.App
    }

    /** Any shortcut (app or command) for this exact phrase. */
    fun shortcut(phrase: String): EmaShortcuts.Target? {
        val key = EmaShortcuts.normalizeTrigger(phrase)
        if (key.isBlank()) return null
        shortcuts[key]?.let { return it }
        val stripped = key.replace(Regex("^(?:my|our|the)\\s+"), "")
        shortcuts[stripped]?.let { return it }
        return shortcuts.entries.firstOrNull { (trigger, _) ->
            trigger.replace(Regex("^(?:my|our|the)\\s+"), "") == stripped
        }?.value
    }

    fun describe(): String {
        if (facts.isEmpty() && aliases.isEmpty() && defaults.isEmpty() && shortcuts.isEmpty()) return "You haven't asked me to remember anything."
        val parts = mutableListOf<String>()
        if (facts.isNotEmpty()) parts += "Facts: " + facts.entries.joinToString("; ") { "${it.key}: ${it.value}" } + "."
        if (shortcuts.isNotEmpty()) parts += "Shortcuts: " + shortcuts.entries.joinToString("; ") { (trigger, target) ->
            when (target) {
                is EmaShortcuts.Target.App -> "\"$trigger\" opens ${target.label}"
                is EmaShortcuts.Target.Command -> "\"$trigger\" runs ${target.text}"
            }
        } + "."
        if (aliases.isNotEmpty()) parts += "Names: " + aliases.entries.joinToString("; ") { "\"${it.key}\" means ${it.value}" } + "."
        if (defaults.isNotEmpty()) parts += "Default apps: " + defaults.entries.joinToString("; ") { "${kindName(it.key)}: ${it.value}" } + "."
        return parts.joinToString(" ")
    }

    companion object {
        const val MAX_FACTS = 50
        const val MAX_ALIASES = 50
        const val MAX_SHORTCUTS = 50
        /** Encoding version stored with the memory. Older payloads still decode. */
        const val SCHEMA_VERSION = 2

        private val SECRET = Regex("\\b(password|passcode|passwd|pin|otp|one[- ]time|cvv|cvc|card number|credit card|debit card|bank account|account number|seed phrase|recovery phrase|private key|security code|bvn|nin|ssn|social security)\\b", RegexOption.IGNORE_CASE)

        private fun kindName(kind: String) = when (kind) {
            "messaging" -> "messages"; "video" -> "videos"; "music" -> "music"; "podcast" -> "podcasts"; else -> kind
        }

        private fun possessive(key: String) = "Your $key"

        private fun normKey(raw: String): String = raw.lowercase()
            .replace(Regex("^(?:my|our|the)\\s+"), "")
            .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        /** Parse a memory instruction, or null when the utterance is not about memory. */
        fun parse(raw: String): Op? {
            val text = raw.trim().trimEnd('.', '!', '?').trim()
            val l = text.lowercase()
            if (Regex("^(?:forget everything|clear (?:your |my )?memory|forget all(?: of)? (?:that|it|my data)?|wipe (?:your |my )?memory|forget what i told you)$").matches(l)) return Op.ForgetAll
            if (Regex("^(?:what do you (?:remember|know)(?: about me)?|show (?:me )?(?:my |your )?memory|what have i (?:asked|told) you to remember)$").matches(l)) return Op.ListAll

            // Spoken definitions, with or without quote marks. Handles
            // "remember (that) whenever/when/if I say X (or Y) it means ...",
            // "remember that X means Y" and "when I say X, open Y".
            EmaShortcuts.parseDefinition(text)?.let { return Op.Shortcut(it) }

            Regex("^(?:remember\\s+(?:that\\s+)?)?(.+?)\\s+is the same as\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                return Op.Synonym(m.groupValues[1].trim().trim('"', '\''), m.groupValues[2].trim().trim('"', '\''))
            }
            Regex("^(?:remember\\s+(?:that\\s+)?)?(.+?)\\s+and\\s+(.+?)\\s+are the same(?: thing)?$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                return Op.Synonym(m.groupValues[1].trim().trim('"', '\''), m.groupValues[2].trim().trim('"', '\''))
            }

            Regex("^(?:when i say|if i say)\\s+[\"']?(.+?)[\"']?,?\\s+i mean\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                return Op.Alias(normKey(m.groupValues[1]), m.groupValues[2].trim().trim('"', '\''))
            }
            Regex("^(?:remember that\\s+)?[\"']([^\"']+)[\"']\\s+means\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                return Op.Alias(normKey(m.groupValues[1]), m.groupValues[2].trim())
            }
            Regex("^(?:use|always use)\\s+(.+?)\\s+(?:for|to send|to play|to watch)\\s+(messages|messaging|texts|texting|chats|videos?|music|songs|podcasts?)$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                return Op.SetDefault(kindOf(m.groupValues[2]), m.groupValues[1].trim())
            }
            Regex("^(?:make|set)\\s+(.+?)\\s+(?:as\\s+)?(?:my|the)\\s+default\\s+(messaging|message|text|chat|video|music|podcast)\\s+app$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                return Op.SetDefault(kindOf(m.groupValues[2]), m.groupValues[1].trim())
            }
            Regex("^set\\s+(?:my|the)\\s+default\\s+(messaging|message|text|chat|video|music|podcast)\\s+app\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                return Op.SetDefault(kindOf(m.groupValues[1]), m.groupValues[2].trim())
            }

            Regex("^(?:please\\s+)?remember\\s+(?:that\\s+)?(.+?)\\s+(is|are)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                val subject = m.groupValues[1].trim()
                val value = m.groupValues[3].trim()
                if (SECRET.containsMatchIn(subject) || SECRET.containsMatchIn(value) || Regex("\\b\\d{4,}\\b").containsMatchIn(value) && Regex("code|number|key", RegexOption.IGNORE_CASE).containsMatchIn(subject)) {
                    return Op.Refused("I don't store passwords, PINs, codes or card and bank details. Please keep those in a password manager.")
                }
                // "remember to buy milk" is a reminder, not memory: no "is/are" so this branch never sees it.
                val display = subject.replace(Regex("(?i)^my\\b"), "your") + " " + m.groupValues[2] + " " + value
                return Op.Remember(normKey(subject), value, display)
            }
            Regex("^(?:what(?:'s| is| are)|who(?:'s| is| are)|where(?:'s| is)|when(?:'s| is)|do you remember|tell me)\\s+my\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                return Op.Recall(normKey(m.groupValues[1]))
            }
            Regex("^forget\\s+what\\s+(?:i taught you|you taught me)\\s+about\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                return Op.Forget(normKey(m.groupValues[1]))
            }
            Regex("^forget\\s+(?:about\\s+)?(?:my\\s+|that\\s+|what\\s+)?[\"']?(.+?)[\"']?(?:\\s+means)?$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                val key = normKey(m.groupValues[1])
                if (key.isNotBlank() && key !in setOf("it", "that", "this")) return Op.Forget(key)
            }
            return null
        }

        private fun kindOf(word: String): String = when (word.lowercase().trimEnd('s')) {
            "message", "messaging", "text", "texting", "chat" -> "messaging"
            "video" -> "video"
            "music", "song" -> "music"
            "podcast" -> "podcast"
            else -> word.lowercase()
        }

        // --------------------------------------------- simple persistence

        /**
         * Line-based encoding, stable and inspectable; values are escaped.
         *
         * The first line carries the schema version. Shortcut lines hold the
         * trigger, the target kind, and the app package + label or the command
         * text, so a restart cannot lose what the user taught EMA.
         */
        fun encode(memory: EmaMemory): String = buildString {
            append("V\t").append(SCHEMA_VERSION).append('\n')
            fun section(tag: String, map: Map<String, String>) = map.forEach { (k, v) -> append(tag).append('\t').append(esc(k)).append('\t').append(esc(v)).append('\n') }
            section("F", memory.facts); section("A", memory.aliases); section("D", memory.defaults)
            memory.shortcuts.forEach { (trigger, target) ->
                when (target) {
                    is EmaShortcuts.Target.App -> append("S\t").append(esc(trigger)).append("\tapp\t")
                        .append(esc(target.packageName)).append('\t').append(esc(target.label)).append('\n')
                    is EmaShortcuts.Target.Command -> append("S\t").append(esc(trigger)).append("\tcmd\t")
                        .append(esc(target.text)).append("\t-\n")
                }
            }
        }

        fun decode(text: String?): EmaMemory {
            if (text.isNullOrBlank()) return EmaMemory()
            val f = linkedMapOf<String, String>(); val a = linkedMapOf<String, String>(); val d = linkedMapOf<String, String>()
            val s = linkedMapOf<String, EmaShortcuts.Target>()
            text.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val p = line.split('\t')
                if (p[0] == "V") return@forEach
                if (p[0] == "S") {
                    if (p.size < 5) return@forEach
                    val trigger = unesc(p[1])
                    when (p[2]) {
                        "app" -> s[trigger] = EmaShortcuts.Target.App(unesc(p[3]), unesc(p[4]))
                        "cmd" -> s[trigger] = EmaShortcuts.Target.Command(unesc(p[3]))
                    }
                    return@forEach
                }
                if (p.size != 3) return@forEach
                val k = unesc(p[1]); val v = unesc(p[2])
                when (p[0]) { "F" -> f[k] = v; "A" -> a[k] = v; "D" -> d[k] = v }
            }
            return EmaMemory(f, a, d, s)
        }

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
        private fun unesc(s: String): String {
            val out = StringBuilder(); var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) { 't' -> out.append('\t'); 'n' -> out.append('\n'); else -> out.append(s[i + 1]) }
                    i += 2
                } else { out.append(c); i++ }
            }
            return out.toString()
        }
    }
}
