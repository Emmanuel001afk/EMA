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
