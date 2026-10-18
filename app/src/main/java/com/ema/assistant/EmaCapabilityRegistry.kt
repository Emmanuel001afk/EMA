package com.ema.assistant

/**
 * What EMA can do, what each operation needs, how it is carried out and how
 * success is checked.
 *
 * One source of truth used for three things:
 *  1. plan validation: a consequential step whose prerequisites are missing is
 *     stopped *before* anything is done, with a precise explanation
 *  2. honest self-description ("what can you do?") that reflects the
 *     permissions actually granted on this phone
 *  3. helpful "not understood" answers that point at real capabilities
 */
object EmaCapabilityRegistry {

    enum class Prerequisite(val label: String, val howTo: String) {
        ACCESSIBILITY("screen control", "turn on EMA in Android Settings > Accessibility"),
        CONTACTS("Contacts access", "allow Contacts for EMA"),
        PHONE("Phone access", "allow Phone calls for EMA"),
        NOTIFICATIONS("notification access", "allow EMA in Settings > Notification access"),
        MICROPHONE("microphone access", "allow the microphone for EMA"),
        INTERNET_AI("an online AI service", "configure the AI service and switch AI on in EMA settings")
    }

    enum class Mechanism { ANDROID_INTENT, ACCESSIBILITY_UI, SYSTEM_API, LOCAL_COMPUTATION, MEDIA_SESSION }

    data class Capability(
        val id: String,
        val title: String,
        val examples: List<String>,
        /** Hard requirements: without them the operation cannot be completed. */
        val requires: Set<Prerequisite>,
        val mechanisms: List<Mechanism>,
        val verification: String,
        val consequential: Boolean = false
    )

    val ALL: List<Capability> = listOf(
        Capability("message", "Send messages in any chat app", listOf("message Daniel on WhatsApp that I'm coming home"),
            setOf(Prerequisite.CONTACTS, Prerequisite.ACCESSIBILITY), listOf(Mechanism.ANDROID_INTENT, Mechanism.ACCESSIBILITY_UI),
            "recipient header matches, composer holds the exact text, then the sent bubble is observed", consequential = true),
        Capability("sms", "Send SMS", listOf("text Mum good morning"),
            setOf(Prerequisite.CONTACTS), listOf(Mechanism.ANDROID_INTENT, Mechanism.ACCESSIBILITY_UI),
            "composer contents and send state", consequential = true),
        Capability("call", "Make phone calls", listOf("call Daniel"), setOf(Prerequisite.CONTACTS, Prerequisite.PHONE),
            listOf(Mechanism.ANDROID_INTENT), "Android accepts the call intent", consequential = true),
        Capability("open", "Open any installed app or a page inside it", listOf("open My Portal and go to Reports"),
            emptySet(), listOf(Mechanism.ANDROID_INTENT, Mechanism.ACCESSIBILITY_UI), "the app comes to the foreground"),
        Capability("search", "Search inside apps", listOf("search YouTube for football"),
            emptySet(), listOf(Mechanism.ANDROID_INTENT, Mechanism.ACCESSIBILITY_UI), "results appear for the query"),
        Capability("select", "Choose a result by position or by what the screen shows", listOf("play the third result", "choose the longest one"),
            setOf(Prerequisite.ACCESSIBILITY), listOf(Mechanism.ACCESSIBILITY_UI), "the screen changes to the chosen item; playback is checked through the media session"),
        Capability("media", "Play and control music and video", listOf("play Arcade on Spotify", "pause"),
            emptySet(), listOf(Mechanism.ANDROID_INTENT, Mechanism.MEDIA_SESSION, Mechanism.ACCESSIBILITY_UI), "the app's media session reports playback"),
        Capability("ui", "Tap, type and scroll in the app on screen", listOf("tap Export", "scroll down"),
            setOf(Prerequisite.ACCESSIBILITY), listOf(Mechanism.ACCESSIBILITY_UI), "the targeted control exists and the screen changes"),
        Capability("clock", "Alarms, timers and reminders", listOf("set an alarm for 6:30", "remind me to call Mum at 8"),
            emptySet(), listOf(Mechanism.ANDROID_INTENT, Mechanism.SYSTEM_API), "the clock app or Android scheduler accepts it"),
        Capability("device", "Volume, brightness, flashlight, Do Not Disturb, screenshots, battery and storage", listOf("turn the volume up"),
            emptySet(), listOf(Mechanism.SYSTEM_API), "the new setting is read back where Android allows"),
        Capability("screen", "Read and describe the screen", listOf("read screen"),
            setOf(Prerequisite.ACCESSIBILITY), listOf(Mechanism.ACCESSIBILITY_UI), "text is read from the live screen"),
        Capability("knowledge", "Calculations, conversions and date questions offline", listOf("what is 15% of 2400", "convert 5 km to miles"),
            emptySet(), listOf(Mechanism.LOCAL_COMPUTATION), "computed on the phone"),
        Capability("memory", "Remember facts, names and default apps you tell me", listOf("when I say portal I mean Acme Staff Portal"),
            emptySet(), listOf(Mechanism.LOCAL_COMPUTATION), "stored on this phone; visible and erasable in Settings"),
        Capability("open_questions", "Open-ended questions", listOf("explain photosynthesis"),
            setOf(Prerequisite.INTERNET_AI), listOf(Mechanism.SYSTEM_API), "answer comes from the optional AI service")
    )

    fun byId(id: String): Capability = ALL.first { it.id == id }

    /** Which capability a structured command uses. [smsApp] is true when the message goes through the SMS app. */
    fun forCommand(command: EmaCommand, smsApp: Boolean = false): Capability? = when (command) {
        is EmaCommand.SendMessage -> if (smsApp) byId("sms") else byId("message")
        is EmaCommand.OpenSmsComposer -> byId("sms")
        is EmaCommand.CallContact -> byId("call")
        is EmaCommand.SelectResult -> byId("select")
        is EmaCommand.SearchInApp -> byId("search")
        is EmaCommand.PlayMedia, is EmaCommand.MediaControl -> byId("media")
        is EmaCommand.OpenApp, is EmaCommand.OpenAppSection -> byId("open")
        is EmaCommand.SetAlarm, is EmaCommand.SetTimer, is EmaCommand.SetReminder -> byId("clock")
        else -> null
    }

    fun missing(capability: Capability, granted: Set<Prerequisite>): Set<Prerequisite> = capability.requires - granted

    /** One clear sentence for a blocked step. */
    fun explainMissing(capability: Capability, missing: Set<Prerequisite>): String {
        val list = missing.toList()
        val needs = list.joinToString(" and ") { it.label }
        val how = list.joinToString("; ") { it.howTo }
        return "To ${capability.title.lowercase()} I need $needs. Please $how, then ask me again. I haven't done anything yet."
    }

    /** "What can you do?" answered from what is really available on this phone. */
    fun describe(granted: Set<Prerequisite>, aiAvailable: Boolean): String {
        val ready = ALL.filter { it.id != "open_questions" && missing(it, granted).isEmpty() }.map { it.title.lowercase() }
        val blocked = ALL.filter { it.id != "open_questions" && missing(it, granted).isNotEmpty() }
        val sb = StringBuilder("Right now I can: ").append(ready.joinToString(", ")).append('.')
        if (blocked.isNotEmpty()) {
            val needs = blocked.flatMap { missing(it, granted) }.toSet().joinToString(" and ") { it.label }
            sb.append(" With ").append(needs).append(" I could also: ").append(blocked.joinToString(", ") { it.title.lowercase() }).append('.')
        }
        sb.append(if (aiAvailable) " Open-ended questions can go to the optional AI service." else " Everything above works offline; open-ended questions would need the optional AI service, which is off.")
        return sb.toString()
    }
}
