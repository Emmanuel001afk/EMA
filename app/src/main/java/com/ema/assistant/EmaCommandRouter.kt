package com.ema.assistant

import android.Manifest
import android.app.Activity
import android.media.AudioManager
import android.telecom.TelecomManager
import android.provider.Settings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.content.ContentProviderOperation
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * First-stage command router.
 *
 * Deterministic phone/app commands are handled locally. The AI tunnel is only
 * used when EMA needs natural-language reasoning or information.
 */
class EmaCommandRouter(
    private val context: Context,
    private val tts: TextToSpeech
) {
    private val appEngine = EmaAppActionEngine(context)
    private var lastAppName: String? = null

    private data class PendingTask(val type: String, val target: String, val app: String? = null, val body: String? = null, val sim: Int? = null, val candidates: List<String> = emptyList())
    companion object { @Volatile private var pendingTask: PendingTask? = null }

    fun execute(raw: String, done: (String) -> Unit) {
        val command = raw.trim()
        if (command.isBlank()) return
        if (handlePendingFollowUp(command, done)) return
        pendingTask = null
        if (handleScheduledAction(command, done)) return
        EmaConversationStore.add(context, "user", command)
        if (handleRecurringRoutine(command, done)) return
        val lower = command.lowercase(Locale.getDefault())

        // Execute explicit multi-action requests sequentially. The existing
        // command handlers remain responsible for each individual action.
        val steps = splitActionSequence(command)
        if (steps.size > 1) {
            executeSequence(steps, 0, done)
            return
        }

        when {
            lower == "scroll down" || lower == "scroll up" || lower == "scroll to top" -> {
                val direction = if (lower.contains("up") || lower.contains("top")) "up" else "down"
                EmaGenericUiController.scroll(direction) { _, message ->
                    speak(message); done(message)
                }
            }

            lower == "tap the first result" || lower == "select the first result" || lower == "open the first result" -> {
                val ok = EmaAccessibilityService.tapFirstResult()
                val message = if (ok) "Selected the first visible result." else "I could not identify a selectable result on this screen."
                speak(message); done(message)
            }

            lower == "read screen with camera" || lower == "read everything on screen" ||
                lower == "what does the screen say" || lower == "what is written on my screen" -> {
                statusWithOcr(done)
            }

            lower.startsWith("remind me ") || lower.startsWith("set a reminder ") -> {
                handleReminder(command, done)
            }

            lower.startsWith("create a calendar task ") || lower.startsWith("calendar task ") ||
                lower.startsWith("set a calendar task ") -> {
                handleCalendarTask(command, done)
            }

            lower.startsWith("create a calendar event ") || lower.startsWith("add calendar event ") ||
                lower.startsWith("calendar event ") || lower.startsWith("create an event ") -> {
                handleCalendarEvent(command, done)
            }

            lower == "read notifications" || lower == "show notifications" || lower == "what are my notifications" -> {
                val message = EmaNotificationListenerService.describeActiveNotifications()
                speak(message); done(message)
            }

            lower.startsWith("dismiss notification") || lower.startsWith("clear notification") -> {
                val target = command.substringAfter(' ').replaceFirst(Regex("(?i)^(notification|notifications)\\s*"), "").trim()
                val ok = EmaNotificationListenerService.dismissMatching(target)
                val message = if (ok) "I dismissed the matching notification." else "I could not find a matching active notification."
                speak(message); done(message)
            }

            lower == "open notifications" || lower == "show notification shade" -> {
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Showing notifications.", done)
            }

            lower == "what time is it" || lower == "tell me the time" || lower == "current time" -> {
                val time = java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date())
                val message = "It is " + time + "."
                speak(message); done(message)
            }

            lower.startsWith("set an alarm for ") || lower.startsWith("set alarm for ") -> {
                val value = command.substringAfter("alarm for ").trim()
                openAlarm(value, done)
            }

            lower.startsWith("navigate to ") || lower.startsWith("directions to ") -> {
                val destination = command.substringAfter(' ').substringAfter(' ').trim()
                openMaps(destination, done)
            }

            isSaveContactCommand(lower) -> {
                saveContact(command, done)
            }

            isMessageCommand(lower) -> {
                val parsed = parseMessageCommand(command)
                if (parsed == null) {
                    val result = "Tell me who to message, which app to use, and what to say."
                    speak(result); done(result)
                } else {
                    val (recipient, app, body) = parsed
                    sendAppMessage(recipient, app, body, done)
                }
            }

            lower.startsWith("text ") || lower.startsWith("send a text to ") || lower.startsWith("send a message to ") -> {
                openMessageComposer(command, done)
            }

            lower == "back" || lower == "go back" ->
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK, "Going back.", done)

            lower == "home" || lower == "go home" ->
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME, "Going home.", done)

            lower == "volume up" || lower == "increase volume" -> adjustVolume(AudioManager.ADJUST_RAISE, "Volume increased.", done)
            lower == "volume down" || lower == "decrease volume" -> adjustVolume(AudioManager.ADJUST_LOWER, "Volume decreased.", done)
            lower == "mute" || lower == "mute volume" -> adjustVolume(AudioManager.ADJUST_MUTE, "Volume muted.", done)
            lower == "unmute" || lower == "unmute volume" -> adjustVolume(AudioManager.ADJUST_UNMUTE, "Volume unmuted.", done)

            lower.startsWith("set a timer for ") || lower.startsWith("timer for ") -> {
                val value = if (lower.startsWith("set a timer for ")) command.substringAfter("set a timer for ").trim()
                else command.substringAfter("timer for ").trim()
                openTimer(value, done)
            }

            lower == "open wifi settings" || lower == "open wi-fi settings" -> openSettings(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings", done)
            lower == "open bluetooth settings" -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings", done)
            lower == "open display settings" -> openSettings(Settings.ACTION_DISPLAY_SETTINGS, "display settings", done)
            lower == "open sound settings" -> openSettings(Settings.ACTION_SOUND_SETTINGS, "sound settings", done)
            lower == "open notification settings" -> openAppNotificationSettings(done)
            lower == "open accessibility settings" -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, "Accessibility settings", done)
            lower == "open assistant settings" -> openSettings(Settings.ACTION_VOICE_INPUT_SETTINGS, "assistant settings", done)

            lower == "recent apps" || lower == "show recent apps" ->
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS, "Showing recent apps.", done)

            lower == "lock phone" || lower == "lock the phone" -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locking the phone.", done)
                } else {
                    val result = "Phone locking is not supported on this Android version."
                    speak(result); done(result)
                }
            }

            lower == "play" || lower == "pause" || lower == "play or pause" -> {
                val ok = EmaAccessibilityService.toggleMediaPlayback()
                val message = if (ok) "Toggling media playback." else "I could not control media playback."
                speak(message); done(message)
            }

            lower == "read screen" || lower == "read this screen" || lower == "what is on my screen" -> {
                val screen = EmaAccessibilityService.currentScreenText()
                val message = if (screen.isBlank()) "EMA cannot read the current screen yet. Enable EMA's Accessibility access first." else screen
                speak(message.take(3000))
                done(message)
            }

            lower == "inspect screen" || lower == "inspect this screen" ||
                lower == "understand this screen" || lower == "analyze this screen" -> {
                val ui = EmaAccessibilityService.inspectCurrentUi()
                val message = if (ui.isBlank()) {
                    "EMA cannot inspect the current application's accessible UI. The app may not expose accessible controls, or Accessibility access may not be active."
                } else {
                    ui
                }
                speak(if (ui.isBlank()) message else "I inspected the accessible controls on this screen.")
                done(message)
            }

            lower.startsWith("tap ") || lower.startsWith("press ") -> {
                val target = command.substringAfter(' ').trim()
                EmaGenericUiController.tap(target) { _, message ->
                    speak(message); done(message)
                }
            }

            lower.startsWith("type ") -> {
                val value = command.substringAfter("type ").trim()
                EmaGenericUiController.type(value) { _, message ->
                    speak(message); done(message)
                }
            }

            lower.startsWith("call ") || lower.startsWith("dial ") || lower.startsWith("phone ") ||
                lower.startsWith("ring ") || lower.startsWith("call up ") -> {
                val name = when {
                    lower.startsWith("call ") -> command.substringAfter("call ").trim()
                    lower.startsWith("dial ") -> command.substringAfter("dial ").trim()
                    lower.startsWith("phone ") -> command.substringAfter("phone ").trim()
                    lower.startsWith("ring ") -> command.substringAfter("ring ").trim()
                    else -> command.substringAfter("call up ").trim()
                }
                callContact(name, done)
            }

            isAppSearchCommand(lower) -> {
                val parsed = parseAppSearch(command)
                if (parsed == null) {
                    speak("I could not understand which app you want to search.")
                    done("Try: go into YouTube and search for a topic.")
                } else {
                    val (app, query) = parsed
                    lastAppName = app
                    speak("Opening " + app + " and searching for " + query + ".")
                    if (lower.contains("first result") || lower.contains("first one")) {
                        appEngine.openAndSearchThenTapFirst(app, query) { _, result ->
                            speak(result); done(result)
                        }
                    } else {
                        appEngine.openAndSearch(app, query) { _, result ->
                            speak(result); done(result)
                        }
                    }
                }
            }

            isMediaPlayCommand(lower) -> {
                val parsed = parseMediaPlay(command)
                if (parsed == null) {
                    speak("Tell me what to play and which app to use.")
                    done("Try: play a song on Spotify.")
                } else {
                    val (query, app) = parsed
                    if (appEngine.playMediaInApp(app, query)) {
                        val result = "Playing $query in $app."
                        speak(result); done(result)
                    } else {
                        appEngine.openAndSearch(app, query) { _, result ->
                            val message = if (result.startsWith("I searched")) "$result I could not invoke playback automatically." else result
                            speak(message); done(message)
                        }
                    }
                }
            }

            lower.startsWith("open ") -> openApp(command.substringAfter("open ").trim(), done)

            lower.startsWith("go to ") && lower.contains(" in settings") -> {
                val target = command.substringAfter("go to ").replace(Regex("(?i)\\s+in\\s+settings\\s*$"), "").trim()
                openSettingsAndFind(target, done)
            }

            lower.startsWith("go to ") && lower.contains(" settings") -> {
                val target = command.substringAfter("go to ").replace(Regex("(?i)\\s+settings\\s*$"), "").trim()
                openSettingsAndFind(target, done)
            }

            lower.startsWith("search with google ") || lower.startsWith("search google for ") || lower.startsWith("google search ") -> {
                val query = when {
                    lower.startsWith("search with google ") -> command.substringAfter("search with google ").trim()
                    lower.startsWith("search google for ") -> command.substringAfter("search google for ").trim()
                    else -> command.substringAfter("google search ").trim()
                }
                googleSearch(query, done)
            }

            lower.startsWith("search ") || lower.startsWith("find ") -> {
                val query = command.substringAfter(' ').trim()
                val app = lastAppName
                if (!app.isNullOrBlank()) {
                    appEngine.openAndSearch(app, query) { _, result -> speak(result); done(result) }
                } else webSearch(query, done)
            }

            lower.startsWith("go to ") || lower.startsWith("navigate to ") ->
                openTarget(command.substringAfter(' ').trim().removePrefix("to ").trim(), done)

            else -> {
                val prompt = EmaAiCommandInterpreter.buildPrompt(context, command)
                CentralHubClient(context).ask(prompt) { rawAnswer ->
                    when (val parsed = EmaAiCommandInterpreter.parse(rawAnswer)) {
                        is EmaAiCommandInterpreter.Result.Actions -> {
                            if (parsed.actions.isEmpty()) {
                                val answer = parsed.answer.ifBlank { "I could not turn that request into a phone action." }
                                EmaConversationStore.add(context, "assistant", answer)
                                speak(answer); done(answer)
                            } else {
                                executeAiActions(parsed.actions, 0, parsed.answer, done)
                            }
                        }
                        is EmaAiCommandInterpreter.Result.Answer -> {
                            EmaConversationStore.add(context, "assistant", parsed.text)
                            speak(parsed.text); done(parsed.text)
                        }
                    }
                }
            }
        }
    }

    private fun splitActionSequence(command: String): List<String> {
        // Keep message bodies intact by only treating "then" as a separator
        // when another recognizable action follows it.
        val pieces = command.split(Regex("(?i)\\s+then\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (pieces.size < 2) return listOf(command)
        return pieces
    }

    private fun executeSequence(
        steps: List<String>,
        index: Int,
        done: (String) -> Unit
    ) {
        if (index >= steps.size) {
            done("Done.")
            return
        }
        execute(steps[index]) { result ->
            if (index == steps.lastIndex) {
                done(result)
            } else {
                executeSequence(steps, index + 1, done)
            }
        }
    }

    private fun handleRecurringRoutine(command: String, done: (String) -> Unit): Boolean {
        val match = Regex("(?i)^every\\s+(day|weekday|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+at\\s+(.+?)\\s*:\\s*(.+)$").find(command)
            ?: Regex("(?i)^every\\s+(day|weekday|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+at\\s+(.+?)\\s+to\\s+(.+)$").find(command)
            ?: return false
        val recurrence = match.groupValues[1].lowercase()
        val time = match.groupValues[2].trim()
        val action = match.groupValues[3].trim()
        val parsed = Regex("(?i)^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$").find(time) ?: return false
        var hour = parsed.groupValues[1].toIntOrNull() ?: return false
        val minute = parsed.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: 0
        val meridiem = parsed.groupValues[3].lowercase()
        if (meridiem == "am" && hour == 12) hour = 0
        if (meridiem == "pm" && hour != 12) hour += 12
        if (hour !in 0..23 || minute !in 0..59) return false

        val ok = if (recurrence == "day" || recurrence == "weekday") {
            if (recurrence == "weekday") {
                val now = java.util.Calendar.getInstance()
                var found = false
                for (offset in 0..6) {
                    val c = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, offset) }
                    if (c.get(java.util.Calendar.DAY_OF_WEEK) in 2..6) { found = true; break }
                }
                EmaRoutineScheduler.scheduleDaily(context, action, hour, minute) && found
            } else {
                EmaRoutineScheduler.scheduleDaily(context, action, hour, minute)
            }
        } else {
            val day = mapOf(
                "sunday" to java.util.Calendar.SUNDAY, "monday" to java.util.Calendar.MONDAY,
                "tuesday" to java.util.Calendar.TUESDAY, "wednesday" to java.util.Calendar.WEDNESDAY,
                "thursday" to java.util.Calendar.THURSDAY, "friday" to java.util.Calendar.FRIDAY,
                "saturday" to java.util.Calendar.SATURDAY
            )[recurrence] ?: return false
            EmaRoutineScheduler.scheduleWeekly(context, action, day, hour, minute)
        }
        val result = if (ok) "Recurring routine scheduled: $recurrence at $time." else "Android could not schedule that recurring routine."
        speak(result); done(result)
        return true
    }

    private fun handleScheduledAction(command: String, done: (String) -> Unit): Boolean {
        // Scheduling is deliberately an additional layer over the existing
        // command language. The scheduled item is the same command EMA would
        // execute immediately, so it does not create a second action system.
        val match = Regex(
            """(?i)^(.*?)(?:\\s+)(?:by|at)\\s+([0-9]{1,2}(?::[0-9]{2})?\\s*(?:am|pm)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\s*$"""
        ).find(command) ?: return false

        val baseCommand = match.groupValues[1].trim()
        val timeText = match.groupValues[2].trim()

        val lowerBase = baseCommand.lowercase(Locale.getDefault())
        val isSchedulableAction = lowerBase.isNotBlank()
        if (!isSchedulableAction) return false

        val normalizedTime = when (timeText.lowercase(Locale.getDefault())) {
            "one" -> "1"
            "two" -> "2"
            "three" -> "3"
            "four" -> "4"
            "five" -> "5"
            "six" -> "6"
            "seven" -> "7"
            "eight" -> "8"
            "nine" -> "9"
            "ten" -> "10"
            "eleven" -> "11"
            "twelve" -> "12"
            else -> timeText
        }

        val trigger = EmaScheduler.parseTimeToFutureMillis(normalizedTime)
        if (trigger == null) {
            val result = "I understood the scheduled action, but I could not understand the time."
            speak(result); done(result)
            return true
        }

        val scheduled = EmaScheduler.schedule(context, baseCommand, trigger)
        val formatted = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
            .format(java.util.Date(trigger))

        val result = if (scheduled) {
            "Scheduled that action for $formatted."
        } else {
            "I understood the action and time, but Android could not schedule it."
        }
        speak(result); done(result)
        return true
    }

    private fun isSaveContactCommand(lower: String): Boolean =
        lower.startsWith("save ") && (
            lower.contains(" as ") ||
            lower.contains("'s number ") ||
            lower.contains(" number ")
        )

    private fun saveContact(command: String, done: (String) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.WRITE_CONTACTS)
            val result = "EMA needs Contacts write access to save a contact. Grant it, then repeat the command."
            speak(result); done(result); return
        }

        val patterns = listOf(
            Regex("(?i)^save\\s+(.+?)\\s+as\\s+(.+)$"),
            Regex("(?i)^save\\s+(.+?)['’]s\\s+number\\s+(.+)$"),
            Regex("(?i)^save\\s+(.+?)\\s+number\\s+(.+)$")
        )

        var number: String? = null
        var name: String? = null
        for (pattern in patterns) {
            val match = pattern.find(command) ?: continue
            val first = match.groupValues[1].trim()
            val second = match.groupValues[2].trim()
            val firstLooksLikeNumber = first.matches(Regex("[+0-9()\\-\\s]{7,}"))
            if (firstLooksLikeNumber) {
                number = first
                name = second
            } else if (second.matches(Regex("[+0-9()\\-\\s]{7,}"))) {
                name = first
                number = second
            }
            if (number != null && name != null) break
        }

        // "save this number as NAME" can use a copied phone number as the
        // current number, without pretending the assistant can infer hidden data.
        if (number == null && command.matches(Regex("(?i)^save\\s+this\\s+number\\s+as\\s+.+$"))) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            if (clip.matches(Regex("[+0-9()\\-\\s]{7,}"))) {
                number = clip
                name = Regex("(?i)\\s+as\\s+(.+)$").find(command)?.groupValues?.getOrNull(1)?.trim()
            }
        }

        if (number.isNullOrBlank() || name.isNullOrBlank()) {
            val result = "Tell me the number and the name, for example: save 08012345678 as Daniel."
            speak(result); done(result); return
        }

        val cleanNumber = number!!.trim()
        val cleanName = name!!.trim()

        runCatching {
            val operations = arrayListOf<ContentProviderOperation>()
            operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()

            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, cleanName)
                .build()

            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, cleanNumber)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
        }.onSuccess {
            val result = "Saved $cleanName with number $cleanNumber."
            speak(result); done(result)
        }.onFailure {
            val result = "I could not save $cleanName to Contacts."
            speak(result); done(result)
        }
    }

    private fun statusWithOcr(done: (String) -> Unit) {
        val accessible = EmaAccessibilityService.currentScreenText()
        EmaAccessibilityService.readScreenWithOcr { ocr ->
            val combined = listOf(accessible, ocr).filter { it.isNotBlank() }.distinct().joinToString("\n")
            val result = combined.ifBlank { "I could not read text from the current screen." }.take(6000)
            speak(result); done(result)
        }
    }

    private fun adjustVolume(direction: Int, message: String, done: (String) -> Unit) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            val result = "I could not access the phone volume controls."
            speak(result); done(result); return
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        speak(message); done(message)
    }

    private fun openTimer(value: String, done: (String) -> Unit) {
        val match = Regex("(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)", RegexOption.IGNORE_CASE).find(value)
        if (match == null) {
            val result = "Tell me the timer duration, for example: set a timer for 10 minutes."
            speak(result); done(result); return
        }
        val amount = match.groupValues[1].toInt()
        val unit = match.groupValues[2].lowercase(Locale.getDefault())
        val seconds = when {
            unit.startsWith("hour") || unit.startsWith("hr") -> amount * 3600
            unit.startsWith("minute") || unit.startsWith("min") -> amount * 60
            else -> amount
        }
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "EMA timer")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onSuccess {
            val result = "Opening a timer for $value."
            speak(result); done(result)
        }.onFailure {
            val result = "I could not open the phone timer."
            speak(result); done(result)
        }
    }

    private fun openAppNotificationSettings(done: (String) -> Unit) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onSuccess { val result = "Opening notification settings."; speak(result); done(result) }
            .onFailure { val result = "I could not open notification settings."; speak(result); done(result) }
    }

    private fun openSettings(action: String, label: String, done: (String) -> Unit) {
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { val result = "Opening $label."; speak(result); done(result) }
            .onFailure { val result = "I could not open $label on this phone."; speak(result); done(result) }
    }

    private fun openAlarm(value: String, done: (String) -> Unit) {
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "EMA alarm")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val time = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(value)
        if (time == null) {
            val message = "I could not understand the alarm time."
            speak(message); done(message); return
        }
        var hour = time.groupValues[1].toInt()
        val minute = time.groupValues[2].ifBlank { "0" }.toInt()
        when (time.groupValues[3].lowercase()) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        intent.putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
        intent.putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
        runCatching { context.startActivity(intent) }.onSuccess {
            val message = "Opening the alarm screen for " + value + "."
            speak(message); done(message)
        }.onFailure {
            val message = "This phone does not allow EMA to open the alarm screen."
            speak(message); done(message)
        }
    }

    private fun openMaps(destination: String, done: (String) -> Unit) {
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode(destination))
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val message = "Opening directions to " + destination + "."
            speak(message); done(message)
        }.onFailure {
            openTarget("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(destination), done)
        }
    }

    private fun openMessageComposer(command: String, done: (String) -> Unit) {
        val lower = command.lowercase(Locale.getDefault())
        val marker = when {
            lower.startsWith("send a message to ") -> "send a message to "
            lower.startsWith("send a text to ") -> "send a text to "
            else -> "text "
        }
        val rest = command.substring(marker.length).trim()
        val parts = rest.split(Regex("\\s+(?:saying|that says|message)\\s+"), limit = 2)
        val recipient = parts.firstOrNull().orEmpty()
        val body = parts.getOrNull(1).orEmpty()
        val uri = Uri.parse("smsto:" + Uri.encode(recipient))
        val intent = Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (body.isNotBlank()) intent.putExtra("sms_body", body)
        runCatching { context.startActivity(intent) }.onSuccess {
            val message = "Opening messages for " + recipient + "."
            speak(message); done(message)
        }.onFailure {
            val message = "I could not open the messaging app."
            speak(message); done(message)
        }
    }

    private data class MessageCommand(
        val recipient: String,
        val app: String,
        val body: String
    )

    private fun isMessageCommand(lower: String): Boolean {
        return lower.startsWith("message ") ||
            lower.startsWith("send a message ") ||
            (lower.startsWith("send ") && (
                lower.contains(" on ") || lower.contains(" in ")
            ) && (
                lower.contains(" saying ") || lower.contains(" that ") || lower.contains(" to ")
            )) ||
            (lower.startsWith("tell ") && (
                lower.contains(" on ") || lower.contains(" in ")
            ))
    }

    private fun parseMessageCommand(command: String): MessageCommand? {
        val patterns = listOf(
            Regex("(?i)^message\\s+(.+?)\\s+(?:on|in|using)\\s+(.+?)\\s+(?:saying|that|to say)\\s+(.+)$"),
            Regex("(?i)^send\\s+(?:a\\s+)?message\\s+(.+?)\\s+(?:on|in|using)\\s+(.+?)\\s+(?:saying|that|to say)\\s+(.+)$"),
            Regex("(?i)^send\\s+(.+?)\\s+(?:a\\s+)?message\\s+(?:on|in|using)\\s+(.+?)\\s+(?:saying|that|to say)\\s+(.+)$"),
            Regex("(?i)^tell\\s+(.+?)\\s+(?:on|in|using)\\s+(.+?)\\s+(?:that|to say)\\s+(.+)$"),
            Regex("(?i)^message\\s+(.+?)\\s+(?:on|in|using)\\s+(.+?)\\s*:\\s*(.+)$"),
            Regex("(?i)^send\\s+(?:a\\s+)?message\\s+to\\s+(.+?)\\s+(?:on|in|using)\\s+(.+?)\\s+(?:saying|that)\\s+(.+)$")
        )
        for (pattern in patterns) {
            val match = pattern.find(command) ?: continue
            val recipient = match.groupValues[1].trim()
            val app = match.groupValues[2].trim()
            val body = match.groupValues[3].trim()
            if (recipient.isNotBlank() && app.isNotBlank() && body.isNotBlank()) {
                return MessageCommand(recipient, app, body)
            }
        }
        return null
    }

    private fun sendAppMessage(
        recipient: String,
        app: String,
        body: String,
        done: (String) -> Unit
    ) {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.READ_CONTACTS)
            val result = "EMA needs Contacts access to find $recipient. Grant it, then repeat the message."
            speak(result); done(result); return
        }

        val requested = recipient.trim().lowercase()
        data class MessageCandidate(
            val displayName: String,
            val phone: String?,
            val email: String?,
            val rank: Int
        )
        val candidates = mutableListOf<MessageCandidate>()

        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1
            ),
            null, null, null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val dataIndex = cursor.getColumnIndex(ContactsContract.Data.DATA1)

            val grouped = linkedMapOf<String, Triple<String, String?, String?>>()
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex).orEmpty().trim()
                if (displayName.isBlank()) continue
                val key = displayName.lowercase()
                val current = grouped[key]
                val mime = cursor.getString(mimeIndex).orEmpty()
                val value = cursor.getString(dataIndex).orEmpty()
                var phone = current?.second
                var email = current?.third
                if (mime == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE && phone.isNullOrBlank()) phone = value
                if (mime == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE && email.isNullOrBlank()) email = value
                grouped[key] = Triple(displayName, phone, email)
            }

            val requestedWords = requested.split(Regex("\\s+")).filter { it.isNotBlank() }
            for ((_, value) in grouped) {
                val displayName = value.first
                val candidate = displayName.lowercase()
                val words = candidate.split(Regex("\\s+")).filter { it.isNotBlank() }
                val rank = when {
                    candidate == requested -> 0
                    words.any { it == requested } -> 1
                    requestedWords.size > 1 && requestedWords.all { word -> words.any { it == word } } -> 2
                    candidate.startsWith("$requested ") || candidate.endsWith(" $requested") -> 3
                    candidate.contains(" $requested ") -> 3
                    candidate.startsWith(requested) || candidate.endsWith(requested) -> 4
                    candidate.contains(requested) -> 5
                    else -> Int.MAX_VALUE
                }
                if (rank != Int.MAX_VALUE) {
                    candidates.add(MessageCandidate(displayName, value.second, value.third, rank))
                }
            }
        }

        if (candidates.isEmpty()) {
            val result = "I couldn't find $recipient in your contacts."
            speak(result); done(result); return
        }

        val bestRank = candidates.minOf { it.rank }
        val best = candidates.filter { it.rank == bestRank }
            .distinctBy { it.displayName.lowercase() }

        if (best.size > 1) {
            val names = best.take(3).joinToString(", ") { it.displayName }
            pendingTask = PendingTask("message", recipient, app, body, candidates = best.map { it.displayName }.distinct())
            val result = "I found more than one possible contact for $recipient: $names. Tell me which one."
            speak(result); done(result); return
        }

        pendingTask = null
        val selected = best.first()
        appEngine.sendMessage(app, selected.displayName, selected.phone, selected.email, body) { _, result ->
            speak(result); done(result)
        }
    }

    private fun isAppSearchCommand(lower: String): Boolean {
        // App-directed search is a phone action, not a web-search fallback.
        if (isCallIntent(lower)) return false
        if (!lower.contains("search") && !lower.contains("find")) return false

        // Both natural word orders are supported:
        // "YouTube search for cats" and "search for cats on YouTube".
        // "open YouTube and search for cats" is also supported.
        val appFirst = Regex(
            "(?i)^(?:open\\s+|go\\s+(?:into|to)\\s+)?[^ ](?:.*?)(?:\\s+search(?:\\s+for)?\\s+|\\s+find\\s+).+"
        ).matches(lower)

        val appLast = Regex(
            "(?i)^(?:search(?:\\s+for)?|find)\\s+.+?\\s+(?:on|in|using)\\s+.+$"
        ).matches(lower)

        val namedApp = Regex(
            "(?i)^(?:search|find)\\s+[^ ]+\\s+(?:for|about)\\s+.+$"
        ).matches(lower)

        return appFirst || appLast || namedApp
    }

    private fun parseAppSearch(command: String): Pair<String, String>? {
        val lower = command.lowercase(Locale.getDefault())

        // App-first: "YouTube search for cats", "open YouTube and search cats".
        val appFirst = Regex(
            "(?i)^(.*?)\\s+(?:and\\s+)?search(?:\\s+for)?\\s+(.+)$"
        ).find(command)

        if (appFirst != null) {
            val app = appFirst.groupValues[1]
                .replaceFirst(Regex("(?i)^go\\s+(?:into|to)\\s+"), "")
                .replaceFirst(Regex("(?i)^open\\s+"), "")
                .trim()
            val query = appFirst.groupValues[2].trim()
            if (app.isNotBlank() && query.isNotBlank()) return app to query
        }

        // Search-first: "search Lonely on Spotify", "find a video in YouTube".
        val appLast = Regex(
            "(?i)^(?:search(?:\\s+for)?|find)\\s+(.+?)\\s+(?:on|in|using)\\s+(.+)$"
        ).find(command)

        if (appLast != null) {
            val query = appLast.groupValues[1].trim()
            val app = appLast.groupValues[2].trim()
            if (app.isNotBlank() && query.isNotBlank()) return app to query
        }

        val namedApp = Regex(
            "(?i)^(?:search|find)\\s+(.+?)\\s+(?:for|about)\\s+(.+)$"
        ).find(command)
        if (namedApp != null) {
            val app = namedApp.groupValues[1].trim()
            val query = namedApp.groupValues[2].trim()
            if (app.isNotBlank() && query.isNotBlank()) return app to query
        }

        return null
    }

    private fun isCallIntent(lower: String): Boolean =
        lower.startsWith("call ") || lower.startsWith("dial ") ||
            lower.startsWith("phone ") || lower.startsWith("ring ") ||
            lower.startsWith("call up ")

    private fun isMediaPlayCommand(lower: String): Boolean =
        lower.startsWith("play ") || lower.startsWith("listen to ")

    private fun parseMediaPlay(command: String): Pair<String, String>? {
        val lower = command.lowercase(Locale.getDefault())
        val markers = listOf(" on ", " in ", " using ")
        val marker = markers.firstOrNull { lower.lastIndexOf(it) > 5 } ?: return null
        val index = lower.lastIndexOf(marker)
        val prefix = command.substring(0, index).trim()
        val app = command.substring(index + marker.length).trim()
        val query = prefix
            .replaceFirst(Regex("(?i)^play\\s+"), "")
            .replaceFirst(Regex("(?i)^listen to\\s+"), "")
            .trim()
        if (query.isBlank() || app.isBlank()) return null
        return query to app
    }

    private fun handlePendingFollowUp(command: String, done: (String) -> Unit): Boolean {
        val task = pendingTask ?: return false
        val lower = command.lowercase(Locale.getDefault()).trim()
        val exactOriginal = lower.contains("without") &&
            (lower.contains("prefix") || lower.contains("suffix") || lower.contains("anything before") || lower.contains("anything after"))
        val ordinal = when {
            Regex("(?i)\\b(first|1st|one)\\b").containsMatchIn(command) -> 0
            Regex("(?i)\\b(second|2nd|two)\\b").containsMatchIn(command) -> 1
            Regex("(?i)\\b(third|3rd|three)\\b").containsMatchIn(command) -> 2
            else -> -1
        }
        val sim = Regex("(?i)\\b(?:with\\s+)?(?:sim|slot)\\s*([12])\\b").find(command)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val selectedName = when {
            exactOriginal -> task.target
            ordinal >= 0 -> task.candidates.getOrNull(ordinal)
            else -> command.replace(Regex("(?i)^(the\\s+)?(one|person)\\s+"), "").trim()
                .takeIf { it.isNotBlank() && !it.equals("the other", true) }
        } ?: return false
        pendingTask = null
        if (task.type == "call") {
            callContact(if (sim != null) "$selectedName with SIM $sim" else selectedName, done)
            return true
        }
        if (task.type == "message" && task.app != null && !task.body.isNullOrBlank()) {
            sendAppMessage(selectedName, task.app, task.body, done)
            return true
        }
        return false
    }

    private fun callContact(rawName: String, done: (String) -> Unit) {
        val sim = Regex("(?i)\\b(?:with\\s+)?(?:sim|slot)\\s*([12])\\s*$").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val name = rawName.replace(Regex("(?i)\\s+(?:with\\s+)?(?:sim|slot)\\s*[12]\\s*$"), "").trim()
        if (name.isBlank()) {
            speak("Tell me who to call."); done("Tell me who to call."); return
        }

        if (name.matches(Regex("[+0-9()\\-\\s]{7,}"))) {
            placeCall(name.replace(Regex("[^+0-9]"), ""), name, done, sim)
            return
        }

        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.READ_CONTACTS)
            val result = "EMA needs Contacts access to find $name. Grant it, then say call $name again."
            speak(result); done(result); return
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        data class ContactCandidate(
            val displayName: String,
            val number: String,
            val rank: Int
        )

        val candidates = mutableListOf<ContactCandidate>()

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val requested = name.trim().lowercase()
            val requestedWords = requested.split(Regex("\\s+")).filter { it.isNotBlank() }

            while (cursor.moveToNext()) {
                val contactName = cursor.getString(nameIndex).orEmpty().trim()
                val number = cursor.getString(numberIndex).orEmpty().trim()
                if (contactName.isBlank() || number.isBlank()) continue

                val candidate = contactName.lowercase()
                val candidateWords = candidate.split(Regex("\\s+")).filter { it.isNotBlank() }

                val rank = when {
                    candidate == requested -> 0
                    candidateWords.any { it == requested } -> 1
                    requestedWords.size > 1 && requestedWords.all { word -> candidateWords.any { it == word } } -> 2
                    candidate.startsWith("$requested ") || candidate.endsWith(" $requested") -> 3
                    candidate.contains(" $requested ") -> 3
                    candidate.startsWith(requested) || candidate.endsWith(requested) -> 4
                    candidate.contains(requested) -> 5
                    else -> Int.MAX_VALUE
                }

                if (rank != Int.MAX_VALUE) {
                    candidates.add(ContactCandidate(contactName, number, rank))
                }
            }
        }

        if (candidates.isEmpty()) {
            val result = "I couldn't find $name in your contacts."
            speak(result); done(result); return
        }

        val bestRank = candidates.minOf { it.rank }
        val best = candidates.filter { it.rank == bestRank }
            .distinctBy { it.displayName.lowercase() to it.number }

        if (best.size > 1) {
            val names = best.take(3).joinToString(", ") { it.displayName }
            pendingTask = PendingTask("call", name, sim = sim, candidates = best.map { it.displayName }.distinct())
            val result = "I found more than one possible contact for $name: $names. Tell me which one."
            speak(result); done(result); return
        }

        pendingTask = null
        val selected = best.first()
        placeCall(selected.number, selected.displayName, done, sim)
    }

    private fun placeCall(number: String, displayName: String, done: (String) -> Unit, sim: Int? = null) {
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.CALL_PHONE)
            val result = "EMA needs phone-call permission before it can call $displayName. Grant it, then say call $displayName again."
            speak(result); done(result); return
        }

        runCatching {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (sim != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val accounts = telecom?.callCapablePhoneAccounts.orEmpty()
                val handle = accounts.getOrNull(sim - 1)
                if (handle != null) intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                else throw IllegalArgumentException("Requested SIM is not available")
            }
            context.startActivity(intent)
        }.onSuccess {
            val result = if (sim == null) "Calling $displayName." else "Calling $displayName on SIM $sim."
            speak(result); done(result)
        }.onFailure {
            val result = "Android did not allow EMA to place the call."
            speak(result); done(result)
        }
    }

    private fun requestPermission(permission: String) {
        (context as? Activity)?.requestPermissions(arrayOf(permission), 42)
    }

    private fun globalAction(action: Int, message: String, done: (String) -> Unit) {
        val ok = EmaAccessibilityService.performGlobalAction(action)
        val result = if (ok) message else "EMA needs Accessibility access to perform that action."
        speak(result); done(result)
    }

    private fun openApp(appName: String, done: (String) -> Unit) {
        val target = appEngine.openApp(appName)
        if (target == null) {
            val message = "I couldn't find or open " + appName + " on this phone."
            speak(message); done(message); return
        }
        lastAppName = target.label
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val ui = EmaAccessibilityService.inspectCurrentUi()
            val finalMessage = if (ui.isNotBlank()) "Opened " + target.label + " and inspected the accessible UI." else "Opened " + target.label + "."
            speak(finalMessage); done(finalMessage)
        }, 600L)
    }
    private fun openSettingsAndFind(target: String, done: (String) -> Unit) {
        if (target.isBlank()) { openSettings(Settings.ACTION_SETTINGS, "Settings", done); return }
        runCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                EmaGenericUiController.tap(target) { ok, detail ->
                    val message = if (ok) "Opened " + target + " in Settings. " + detail else "I opened Settings, but I could not locate " + target + "."
                    speak(message); done(message)
                }
            }, 900L)
        }.onFailure {
            val message = "I could not open Settings."
            speak(message); done(message)
        }
    }

    private fun googleSearch(query: String, done: (String) -> Unit) {
        if (query.isBlank()) {
            val result = "Tell me what to search for."
            speak(result); done(result); return
        }
        val target = "https://www.google.com/search?q=" + Uri.encode(query)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onSuccess {
            val result = "Searching Google for $query."
            speak(result); done(result)
        }.onFailure {
            val result = "I could not open Google search."
            speak(result); done(result)
        }
    }

    private fun webSearch(query: String, done: (String) -> Unit) {
        if (query.isBlank()) return
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(intent) }.onSuccess {
            val message = "Searching for $query."
            speak(message); done(message)
        }.onFailure {
            openTarget("https://www.google.com/search?q=" + Uri.encode(query), done)
        }
    }

    private fun openTarget(target: String, done: (String) -> Unit) {
        val uri = if (target.contains("://")) Uri.parse(target) else Uri.parse("https://$target")
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val message = "Opening $target."
            speak(message); done(message)
        }.onFailure {
            val message = "I couldn't open $target."
            speak(message); done(message)
        }
    }

    private fun executeAiActions(actions: List<String>, index: Int, finalAnswer: String, done: (String) -> Unit) {
        if (index >= actions.size) {
            val answer = finalAnswer.ifBlank { "Done." }
            EmaConversationStore.add(context, "assistant", answer)
            speak(answer); done(answer); return
        }
        execute(actions[index]) { result ->
            if (index == actions.lastIndex) {
                val answer = finalAnswer.ifBlank { result }
                EmaConversationStore.add(context, "assistant", answer)
                speak(answer); done(answer)
            } else {
                executeAiActions(actions, index + 1, finalAnswer, done)
            }
        }
    }

    private fun handleReminder(command: String, done: (String) -> Unit) {
        val match = Regex("(?i)^(?:remind me|set a reminder)(?: to| that)? (.+?) (?:at|by) (.+)$").find(command)
        if (match == null) {
            val result = "Tell me what to remind you about and when."
            speak(result); done(result); return
        }
        val text = match.groupValues[1].trim()
        val trigger = EmaReminderScheduler.parseTime(match.groupValues[2].trim())
        if (trigger == null) {
            val result = "I understood the reminder, but I could not understand the time."
            speak(result); done(result); return
        }
        val ok = EmaReminderScheduler.schedule(context, text, trigger)
        val formatted = java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(trigger))
        val result = if (ok) "Reminder set for $formatted: $text." else "Android could not schedule that reminder."
        speak(result); done(result)
    }

    private fun handleCalendarTask(command: String, done: (String) -> Unit) {
        val taskText = command
            .replaceFirst(Regex("(?i)^create a calendar task\\s*"), "")
            .replaceFirst(Regex("(?i)^calendar task\\s*"), "")
            .replaceFirst(Regex("(?i)^set a calendar task\\s*"), "")
            .trim()
        if (taskText.isBlank()) {
            val result = "Tell me what the calendar task should be."
            speak(result); done(result); return
        }
        val calendar = appEngine.findApp("Calendar")
        if (calendar == null) {
            val result = "I could not find a Calendar app on this phone."
            speak(result); done(result); return
        }
        appEngine.openApp(calendar.label)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            EmaGenericUiController.tap("Task") { found, _ ->
                if (!found) {
                    EmaGenericUiController.tap("Tasks") { foundTasks, _ ->
                        if (!foundTasks) {
                            val result = "I opened Calendar, but Android did not expose its Task control."
                            speak(result); done(result)
                        } else {
                            createTaskThroughCurrentUi(taskText, done)
                        }
                    }
                } else {
                    createTaskThroughCurrentUi(taskText, done)
                }
            }
        }, 900L)
    }

    private fun createTaskThroughCurrentUi(text: String, done: (String) -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val opened = EmaAccessibilityService.activate("Add task") || EmaAccessibilityService.activate("Create task") || EmaAccessibilityService.activate("New task")
            if (!opened) {
                EmaGenericUiController.type(text) { typed, _ ->
                    val result = if (typed) "I entered the calendar task." else "I could not access the calendar task editor."
                    speak(result); done(result)
                }
                return@postDelayed
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                EmaGenericUiController.type(text) { typed, _ ->
                    val saved = EmaAccessibilityService.activate("Save")
                    val result = if (typed && saved) "Calendar task created." else if (typed) "I entered the calendar task, but could not verify Save." else "I could not enter the calendar task."
                    speak(result); done(result)
                }
            }, 500L)
        }, 500L)
    }

    private fun handleCalendarEvent(command: String, done: (String) -> Unit) {
        val title = command
            .replaceFirst(Regex("(?i)^create (?:a )?calendar event\\s*"), "")
            .replaceFirst(Regex("(?i)^add calendar event\\s*"), "")
            .replaceFirst(Regex("(?i)^calendar event\\s*"), "")
            .replaceFirst(Regex("(?i)^create an event\\s*"), "")
            .trim()
        val timeMatch = Regex("(?i)^(.+?)\\s+(?:at|on)\\s+(.+)$").find(title)
        val eventTitle = timeMatch?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { title }
        val timeText = timeMatch?.groupValues?.getOrNull(2)?.trim().orEmpty()
        val trigger = if (timeText.isBlank()) null else EmaScheduler.parseTimeToFutureMillis(timeText)
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = Uri.parse("content://com.android.calendar/events")
            putExtra("title", eventTitle)
            if (trigger != null) {
                putExtra("beginTime", trigger)
                putExtra("endTime", trigger + 60 * 60 * 1000L)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onSuccess {
            val result = if (trigger != null) "I opened Calendar with $eventTitle scheduled for " +
                java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(trigger)) + "."
            else "I opened Calendar ready to create $eventTitle."
            speak(result); done(result)
        }.onFailure {
            val result = "I could not open the calendar event editor."
            speak(result); done(result)
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ema")
    }
}
