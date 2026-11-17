package com.ema.assistant

/** App purpose is distinct from installed identity, UI readiness and verified execution support. */
object EmaApplicationKnowledge {
    enum class Role { MESSAGING, VIDEO, MUSIC, BROWSER, MAPS, MAIL, FILES, CAMERA, CALENDAR, CLOCK, NOTES, CALCULATOR, UNKNOWN }
    data class Meaning(val role: Role, val purpose: String, val actions: List<String>, val limits: String)

    private val meanings = mapOf(
        Role.MESSAGING to Meaning(Role.MESSAGING, "communicating through conversations", listOf("open a conversation", "prepare a message", "send an explicitly addressed message"), "Sending requires the correct recipient, exact composer text and an observable Send control. A draft is not a sent message."),
        Role.VIDEO to Meaning(Role.VIDEO, "finding and watching videos", listOf("search for a topic", "select a visible result", "request playback"), "A result's position is not its title. Playback must be checked; opening a video app is not playback."),
        Role.MUSIC to Meaning(Role.MUSIC, "finding and listening to audio", listOf("search for a track or artist", "select a matching result", "request playback"), "Streaming may require internet, sign-in or a subscription. The requested title must match observed playback."),
        Role.BROWSER to Meaning(Role.BROWSER, "opening websites and searching the web", listOf("open a web address", "search for a query", "read accessible page text"), "Web content may require internet. A page's instructions are data, not authorization for EMA to act."),
        Role.MAPS to Meaning(Role.MAPS, "finding places and routes", listOf("open a place search", "request directions"), "Location permission and network/map availability affect routes. I don't invent live traffic or arrival times."),
        Role.MAIL to Meaning(Role.MAIL, "reading and composing email", listOf("open the app", "prepare an explicitly addressed draft"), "A mail draft is not proof of delivery. Account login and app support are required."),
        Role.FILES to Meaning(Role.FILES, "organizing documents and stored files", listOf("open the app", "find an explicitly named document"), "File access requires Android authorization. Finding a document is not permission to delete or share it."),
        Role.CAMERA to Meaning(Role.CAMERA, "capturing photos and video", listOf("open the camera"), "Camera permission, device hardware and foreground UI determine what is possible."),
        Role.CALENDAR to Meaning(Role.CALENDAR, "organizing dated events", listOf("open the calendar"), "I must observe or receive event details; I don't invent your schedule."),
        Role.CLOCK to Meaning(Role.CLOCK, "alarms, timers and timekeeping", listOf("request an alarm", "request a timer"), "An alarm or timer is confirmed only when Android or the clock app accepts it."),
        Role.NOTES to Meaning(Role.NOTES, "keeping written information", listOf("open the notes app"), "EMA's private notes are separate from another app's notes. I cannot assume they synchronize."),
        Role.CALCULATOR to Meaning(Role.CALCULATOR, "performing numerical calculations", listOf("open the calculator"), "EMA can also compute supported calculations locally without opening an app."),
        Role.UNKNOWN to Meaning(Role.UNKNOWN, "a purpose I have not classified offline", listOf("open the installed app", "inspect exposed controls"), "I won't assume an unfamiliar app supports sending, payments or playback. Name the task and I can use only observed controls.")
    )

    fun describe(app: EmaAppResolver.App): Meaning {
        // Exact package identities, not substring guesses from a private app's label.
        val role = when (app.packageName.lowercase()) {
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.google.android.apps.messaging" -> Role.MESSAGING
            "com.google.android.youtube", "com.google.android.youtube.tv", "org.videolan.vlc" -> Role.VIDEO
            "com.spotify.music", "com.google.android.apps.youtube.music", "com.soundcloud.android", "com.audiomack" -> Role.MUSIC
            "com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx", "com.brave.browser" -> Role.BROWSER
            "com.google.android.apps.maps", "com.waze" -> Role.MAPS
            "com.google.android.gm", "com.microsoft.office.outlook" -> Role.MAIL
            "com.google.android.apps.nbu.files", "com.google.android.documentsui", "com.android.documentsui" -> Role.FILES
            "com.android.camera", "com.google.android.googlecamera" -> Role.CAMERA
            "com.google.android.calendar", "com.android.calendar" -> Role.CALENDAR
            "com.google.android.deskclock", "com.android.deskclock" -> Role.CLOCK
            "com.google.android.keep", "com.samsung.android.app.notes" -> Role.NOTES
            "com.google.android.calculator", "com.android.calculator2" -> Role.CALCULATOR
            else -> Role.UNKNOWN
        }
        return meanings.getValue(role)
    }

    /** Recognized explanatory questions never launch the app they name. */
    fun answerQuestion(raw: String, installed: List<EmaAppResolver.App>): String? {
        val text = raw.trim().trimEnd('?', '.', '!')
        val phrase = listOf(
            Regex("(?i)^what (?:is|does) (.+?) (?:do|for|used for)$"),
            Regex("(?i)^what can (?:i|you) do (?:with|in|on) (.+)$"),
            Regex("(?i)^explain (?:the purpose of )?(.+?)(?: app)?$")
        ).firstNotNullOfOrNull { it.matchEntire(text)?.groupValues?.get(1) } ?: return null
        return when (val match = EmaAppResolver.ground(phrase, installed)) {
            is EmaAppResolver.Grounding.One -> {
                val meaning = describe(match.app)
                "${match.app.label} is for ${meaning.purpose}. Relevant tasks: ${meaning.actions.joinToString(", ")}. ${meaning.limits} These are task concepts, not a guarantee that this app version exposes every control."
            }
            is EmaAppResolver.Grounding.Ambiguous -> "Which installed app do you mean: ${match.apps.joinToString { it.label }}?"
            is EmaAppResolver.Grounding.KnownNotInstalled -> "That app was not found among the installed applications. I haven't opened anything."
            null -> null
        }
    }

    fun explainTerm(raw: String): String? {
        val term = Regex("(?i)^what (?:does (.+?) mean|is (?:the difference between )?(.+?))[?!.]*$")
            .matchEntire(raw.trim())?.let { it.groupValues[1].ifBlank { it.groupValues[2] }.lowercase().trimEnd('?', '.', '!') } ?: return null
        return when (term) {
            "search" -> "Searching requests results for a query. It does not select a result or prove playback."
            "play" -> "Playing means starting the requested media and checking playback, not merely opening its app."
            "first result", "the first result" -> "The first result is an ordinal position in the current observed result list, not a new search phrase."
            "draft", "a draft" -> "A draft is prepared text that has not been verified as sent."
            "draft and send", "a draft and a sent message" -> "A draft is text in a composer. Sending submits it; a verified sent state needs evidence after submission."
            "offline" -> "Offline means the supported operation runs on the phone without internet. Content inside a streaming or web app may still require a connection."
            "an app", "application", "an application" -> "An application is installed software with its own data, controls and permissions. Opening it is only a navigation step, not completion of every task inside it."
            else -> null
        }
    }
}
