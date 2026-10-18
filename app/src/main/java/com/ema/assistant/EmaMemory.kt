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
 *
 * This is the only on-device adaptation EMA performs, and it happens only when
 * the user explicitly asks. Short-term state (the active result list) lives in
 * [EmaConversationContext] and expires on its own; message recipients and
 * bodies are never remembered.
 */
data class EmaMemory(
    val facts: Map<String, String> = emptyMap(),
    val aliases: Map<String, String> = emptyMap(),
    val defaults: Map<String, String> = emptyMap()
) {
    sealed class Op {
        data class Remember(val key: String, val value: String, val display: String) : Op()
        data class Recall(val key: String) : Op()
        data class Forget(val key: String) : Op()
        object ForgetAll : Op()
        object ListAll : Op()
        data class Alias(val spoken: String, val meaning: String) : Op()
        data class SetDefault(val kind: String, val app: String) : Op()
        data class Refused(val reason: String) : Op()
    }

    data class Applied(val memory: EmaMemory, val reply: String)

    fun apply(op: Op): Applied? = when (op) {
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
                else -> Applied(this, "I don't have anything saved about $k.")
            }
        }
        Op.ForgetAll -> Applied(EmaMemory(), "I've cleared everything you asked me to remember, including names you taught me and default apps.")
        Op.ListAll -> Applied(this, describe())
        is Op.Alias -> {
            if (aliases.size >= MAX_ALIASES && op.spoken !in aliases) Applied(this, "I already know $MAX_ALIASES names. Ask me to forget one first.")
            else Applied(copy(aliases = aliases + (op.spoken to op.meaning)), "OK. When you say \"${op.spoken}\", I'll use ${op.meaning}.")
        }
        is Op.SetDefault -> Applied(copy(defaults = defaults + (op.kind to op.app)), "OK. I'll use ${op.app} for ${kindName(op.kind)} when you don't name an app.")
        is Op.Refused -> Applied(this, op.reason)
    }

    fun describe(): String {
        if (facts.isEmpty() && aliases.isEmpty() && defaults.isEmpty()) return "You haven't asked me to remember anything."
        val parts = mutableListOf<String>()
        if (facts.isNotEmpty()) parts += "Facts: " + facts.entries.joinToString("; ") { "${it.key}: ${it.value}" } + "."
        if (aliases.isNotEmpty()) parts += "Names: " + aliases.entries.joinToString("; ") { "\"${it.key}\" means ${it.value}" } + "."
        if (defaults.isNotEmpty()) parts += "Default apps: " + defaults.entries.joinToString("; ") { "${kindName(it.key)}: ${it.value}" } + "."
        return parts.joinToString(" ")
    }

    companion object {
        const val MAX_FACTS = 50
        const val MAX_ALIASES = 50

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

        /** Line-based encoding, stable and inspectable; values are escaped. */
        fun encode(memory: EmaMemory): String = buildString {
            fun section(tag: String, map: Map<String, String>) = map.forEach { (k, v) -> append(tag).append('\t').append(esc(k)).append('\t').append(esc(v)).append('\n') }
            section("F", memory.facts); section("A", memory.aliases); section("D", memory.defaults)
        }

        fun decode(text: String?): EmaMemory {
            if (text.isNullOrBlank()) return EmaMemory()
            val f = linkedMapOf<String, String>(); val a = linkedMapOf<String, String>(); val d = linkedMapOf<String, String>()
            text.lineSequence().forEach { line ->
                val p = line.split('\t')
                if (p.size != 3) return@forEach
                val k = unesc(p[1]); val v = unesc(p[2])
                when (p[0]) { "F" -> f[k] = v; "A" -> a[k] = v; "D" -> d[k] = v }
            }
            return EmaMemory(f, a, d)
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
