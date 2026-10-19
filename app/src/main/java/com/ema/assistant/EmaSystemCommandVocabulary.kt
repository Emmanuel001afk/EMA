package com.ema.assistant

/**
 * The literal device commands EMA's router handles directly, without parsing.
 *
 * These are the commands that need no slot extraction — "back", "read screen",
 * "turn on DND" — and they matter in three places that must not disagree:
 *
 * 1. the router's `handleSystemCommand` dispatch,
 * 2. the gate that decides whether an AI-proposed plan is really a device
 *    action request, and
 * 3. the vocabulary EMA advertises to the AI layer.
 *
 * Keeping the phrases here makes the contract testable: [recognises] is pure, so
 * a test can prove that every command EMA offers the AI is one EMA can execute.
 *
 * **When adding a literal command to `EmaCommandRouter.handleSystemCommand`, add
 * it here too.** Otherwise the AI may propose it and the action gate will reject
 * the plan, silently downgrading a valid device action to a spoken answer.
 */
object EmaSystemCommandVocabulary {

    /** Commands matched on the whole, normalized utterance. */
    val EXACT: Set<String> = setOf(
        // Navigation
        "back", "go back", "home", "go home", "recent apps", "open recents",
        // Screen reading and OCR
        "read screen", "read this screen", "inspect screen", "what is on my screen",
        "read screen with camera", "read everything on screen",
        "what does the screen say", "what is written on my screen",
        "copy screen text", "copy this screen",
        // Notifications
        "read notifications", "show notifications", "what are my notifications",
        "read the last notification", "what was the last notification",
        // Reminders
        "list my reminders", "list reminders", "show my reminders",
        "what are my reminders", "what reminders do i have",
        "cancel all reminders", "delete all my reminders", "clear my reminders",
        // Device state
        "what time is it", "what is the time", "what is today's date",
        "what is the date", "what is my battery level", "what is my battery",
        "how much storage do i have", "what is my storage",
        // Media and hardware toggles
        "take a screenshot", "screenshot", "turn on flashlight", "turn off flashlight",
        "flashlight on", "flashlight off", "torch on", "torch off",
        "increase brightness", "decrease brightness", "brightness up", "brightness down",
        "turn on dnd", "turn dnd on", "enable dnd", "do not disturb on",
        "turn off dnd", "turn dnd off", "disable dnd", "do not disturb off",
        "mute", "unmute", "max volume", "volume max", "volume up", "volume down",
        // Scrolling and result selection
        "scroll up", "scroll down", "scroll to top",
        "tap the first result", "select the first result",
        "open the first result", "play the first result",
        // Files
        "list files", "show files", "list my files",
        // Assistant itself
        "close ema", "exit ema", "what can you do", "help",
        "what mode are you in", "turn off ai", "disable ai", "no ai mode",
        "turn on ai", "enable ai"
    )

    /** Commands matched by their opening words, the rest being the argument. */
    val PREFIXES: List<String> = listOf(
        "type ", "set brightness to ", "set volume to ", "volume to ",
        "list files in ", "find file ", "find the file ",
        "cancel my reminder ", "cancel the reminder ",
        "delete my reminder ", "remove my reminder ",
        "create a calendar task ", "calendar task ", "set a calendar task ",
        "create a calendar event ", "add calendar event ", "calendar event ",
        "create an event ", "add an event ",
        "schedule my day", "set my schedule", "set today's schedule",
        "go to ", "open settings and find "
    )

    /**
     * A device setting the user asked EMA to change.
     *
     * This is a parser, not a phrase list: the subject (DND, brightness,
     * airplane/Wi-Fi/Bluetooth) and the action (on, off, increase, decrease,
     * maximum, minimum, a percentage) are recognised independently, so
     * "turn on aeroplane mode", "switch flight mode off" and "raise the
     * brightness to full" all work without an entry per wording.
     */
    data class DeviceSetting(val kind: Kind, val action: Action, val value: Int? = null) {
        enum class Kind { DND, BRIGHTNESS, VOLUME, AIRPLANE, WIFI, BLUETOOTH }
        enum class Action { ON, OFF, INCREASE, DECREASE, MAX, MIN, SET }

        /** For on/off toggles; null for brightness-style values. */
        val on: Boolean? get() = when (action) {
            Action.ON -> true
            Action.OFF -> false
            else -> null
        }
    }

    private val MAX_WORDS = EmaLexicon.MAX_WORDS.toList()
    private val MIN_WORDS = EmaLexicon.MIN_WORDS.toList()
    private val INCREASE_WORDS = EmaLexicon.INCREASE_WORDS.toList()
    private val DECREASE_WORDS = EmaLexicon.DECREASE_WORDS.toList()
    private val ON_WORDS = EmaLexicon.ON_WORDS.toList()
    private val OFF_WORDS = EmaLexicon.OFF_WORDS.toList()

    /** The words that name a device setting, in the order they are recognised. */
    private val TARGET_PATTERNS: List<Pair<Regex, DeviceSetting.Kind>> = listOf(
        Regex("\\b(?:do not disturb|dnd|disturb|silent mode|silent)\\b") to DeviceSetting.Kind.DND,
        Regex("\\bbrightness\\b|\\bdim\\b|\\bbrighter\\b|\\bdarker\\b") to DeviceSetting.Kind.BRIGHTNESS,
        Regex("\\b(?:volume|sound|speaker|ringtone)\\b") to DeviceSetting.Kind.VOLUME,
        Regex("\\b(?:airplane|flight) mode\\b|\\bairplane\\b") to DeviceSetting.Kind.AIRPLANE,
        Regex("\\b(?:wifi|wireless|wireless network)\\b") to DeviceSetting.Kind.WIFI,
        Regex("\\bbluetooth\\b") to DeviceSetting.Kind.BLUETOOTH
    )

    /**
     * Words that belong to another command. They may never appear before the
     * setting itself, because then the utterance is a message, a search or a
     * reminder that merely mentions a setting.
     */
    private val OTHER_COMMAND_WORDS: Set<String> = buildSet {
        for (intent in EmaLexicon.INTENTS) {
            if (intent.id == "device-setting") continue
            addAll(intent.verbs)
        }
    }

    /**
     * Parse a device-setting command, or null when the utterance is not one.
     *
     * A setting command is the WHOLE utterance. It starts with a setting verb
     * (after the wake word and politeness) and no word that belongs to
     * another command may come before the setting itself. Without that rule
     * "send John a message saying increase the brightness" changed the
     * brightness instead of sending the message.
     *
     * Pure and unit tested; the router only performs the resulting action.
     */
    fun parseDeviceSetting(command: String): DeviceSetting? {
        val text = " " + command.lowercase()
            .replace("wi-fi", "wifi")
            .replace("wi fi", "wifi")
            .replace("aeroplane", "airplane")
            .replace(Regex("[^a-z0-9% ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim() + " "

        // The utterance must start with a setting verb.
        val leading = EmaLexicon.stripLeadings(text.trim())
        val firstWord = leading.split(' ').firstOrNull { it.isNotBlank() } ?: return null
        if (firstWord !in (EmaLexicon.intent("device-setting")?.verbs ?: emptySet())) return null

        val target = TARGET_PATTERNS.firstNotNullOfOrNull { (pattern, kind) ->
            pattern.find(text)?.let { kind to it }
        } ?: return null
        val kind = target.first

        // Nothing that belongs to another command may come before the setting.
        val prefix = text.substring(0, target.second.range.first).trim()
        if (prefix.split(' ').any { it.isNotBlank() && it in OTHER_COMMAND_WORDS }) return null

        // Plain word/phrase membership. (Regex.escape emits \Q..\E, which
        // java.util.regex does not support, so multi-word phrases never matched.)
        fun has(words: List<String>) = words.any { phrase ->
            Regex("(?:^| )" + phrase.replace(" ", "\\s+") + "(?= |$)").containsMatchIn(text.trim())
        }

        val percent = Regex("\\b(\\d{1,3})\\s*(?:percent|%)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: valueWords(text)
        val action = when {
            has(MAX_WORDS) -> DeviceSetting.Action.MAX
            has(MIN_WORDS) -> DeviceSetting.Action.MIN
            kind != DeviceSetting.Kind.BRIGHTNESS && kind != DeviceSetting.Kind.VOLUME && has(ON_WORDS) -> DeviceSetting.Action.ON
            kind != DeviceSetting.Kind.BRIGHTNESS && kind != DeviceSetting.Kind.VOLUME && has(OFF_WORDS) -> DeviceSetting.Action.OFF
            (kind == DeviceSetting.Kind.BRIGHTNESS || kind == DeviceSetting.Kind.VOLUME) && percent != null -> DeviceSetting.Action.SET
            has(INCREASE_WORDS) -> DeviceSetting.Action.INCREASE
            has(DECREASE_WORDS) -> DeviceSetting.Action.DECREASE
            (kind == DeviceSetting.Kind.BRIGHTNESS || kind == DeviceSetting.Kind.VOLUME) &&
                Regex("\\bset\\b").containsMatchIn(text) -> DeviceSetting.Action.SET
            else -> return null
        }
        val value = when (action) {
            DeviceSetting.Action.SET -> (percent ?: when {
                Regex("\\bhalf\\b").containsMatchIn(text) -> 50
                Regex("\\bquarter\\b").containsMatchIn(text) -> 25
                else -> 50
            }).coerceIn(1, 100)
            else -> percent
        }
        return DeviceSetting(kind, action, value)
    }

    private fun valueWords(text: String): Int? = when {
        Regex("\\b100\\b|\\bfull\\b").containsMatchIn(text) -> 100
        Regex("\\bhalf\\b").containsMatchIn(text) -> 50
        Regex("\\bquarter\\b").containsMatchIn(text) -> 25
        else -> null
    }

    /**
     * Whether [command] is a literal device command.
     *
     * [command] must already be normalized (trimmed, lowercased); the router does
     * that before calling.
     */
    fun recognises(command: String): Boolean {
        val normalized = command.trim()
        if (normalized.isEmpty()) return false
        if (normalized in EXACT) return true
        return PREFIXES.any { normalized.startsWith(it) }
    }

    /**
     * Whether an AI-proposed action is a device action EMA can carry out.
     *
     * Combines the literal vocabulary with the verb-led forms the grammar parses,
     * which is what stops the AI gate from rejecting a valid plan.
     */
    fun looksLikeDeviceAction(normalizedCommand: String): Boolean {
        val command = normalizedCommand.trim()
        if (command.isEmpty()) return false
        if (recognises(command)) return true
        return ACTION_VERBS.any { command.startsWith(it) }
    }

    /** Opening verbs of the grammar-parsed, slot-filling commands. */
    private val ACTION_VERBS: List<String> = listOf(
        "open ", "launch ", "start ", "close ", "exit ", "go to ", "navigate to ", "enter ",
        "tap ", "click ", "press ", "select ", "choose ", "write ",
        "search ", "find ", "look for ", "google ",
        "call ", "phone ", "dial ", "send ", "message ", "text ",
        "play ", "pause", "stop ", "skip ", "resume", "next track", "previous track",
        "set ", "create ", "add ", "schedule ", "remind ", "remember ", "save ",
        "turn ", "enable ", "disable ", "increase ", "decrease ", "raise ", "lower ", "change ",
        "scroll ", "swipe ", "take ", "capture ", "record ",
        "every day at ", "every weekday at ", "every monday at ", "every tuesday at ",
        "every wednesday at ", "every thursday at ", "every friday at ",
        "every saturday at ", "every sunday at "
    )
}
