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

    fun execute(raw: String, done: (String) -> Unit) {
        val command = raw.trim()
        if (command.isBlank()) return
        val lower = command.lowercase(Locale.getDefault())

        when {
            lower == "scroll down" || lower == "scroll up" || lower == "scroll to top" -> {
                val direction = if (lower.contains("up") || lower.contains("top")) "up" else "down"
                val ok = EmaAccessibilityService.scroll(direction)
                val message = if (ok) "Scrolled " + direction + "." else "I could not scroll the current screen."
                speak(message); done(message)
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

            lower.startsWith("tap ") || lower.startsWith("press ") -> {
                val target = command.substringAfter(' ').trim()
                val ok = EmaAccessibilityService.tapText(target)
                val message = if (ok) "Tapped $target." else "I could not find a tappable \"$target\" on the current screen."
                speak(message); done(message)
            }

            lower.startsWith("type ") -> {
                val value = command.substringAfter("type ").trim()
                val ok = EmaAccessibilityService.setFocusedText(value)
                val message = if (ok) "Typed the requested text." else "I could not find an editable field on the current screen."
                speak(message); done(message)
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
                val explicitAi = lower.startsWith("ema ai ") || lower.startsWith("emma ai ") ||
                    lower.startsWith("ask ema ai ") || lower.startsWith("ask emma ai ")
                val needsScreenUnderstanding = listOf(
                    "what is on this screen", "what's on this screen", "what does this screen mean",
                    "what am i looking at", "explain this screen", "understand this screen",
                    "what is on this page", "explain this page"
                ).any(lower::contains)

                if (!explicitAi && !needsScreenUnderstanding) {
                    val result = "I can do that on the phone, but I need a more specific command."
                    speak(result); done(result); return
                }

                val promptCommand = command
                    .replaceFirst(Regex("(?i)^ask\\s+emma\\s+ai\\s*"), "")
                    .replaceFirst(Regex("(?i)^ask\\s+ema\\s+ai\\s*"), "")
                    .replaceFirst(Regex("(?i)^emma\\s+ai\\s*"), "")
                    .replaceFirst(Regex("(?i)^ema\\s+ai\\s*"), "")
                    .trim()

                val screen = EmaAccessibilityService.currentScreenText()
                val prompt = if (screen.isBlank()) promptCommand
                else "Current screen context:\n$screen\n\nUser request: $promptCommand"

                CentralHubClient(context).ask(prompt) { answer ->
                    speak(answer); done(answer)
                }
            }
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

        val normalized = recipient.trim()
        var exactName: String? = null
        var phone: String? = null
        var email: String? = null

        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1
            ),
            "${ContactsContract.Data.DISPLAY_NAME} = ?",
            arrayOf(normalized),
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val dataIndex = cursor.getColumnIndex(ContactsContract.Data.DATA1)
            while (cursor.moveToNext()) {
                exactName = cursor.getString(nameIndex).orEmpty()
                val mime = cursor.getString(mimeIndex).orEmpty()
                val value = cursor.getString(dataIndex).orEmpty()
                when (mime) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                        if (phone.isNullOrBlank()) phone = value
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE ->
                        if (email.isNullOrBlank()) email = value
                }
            }
        }

        if (exactName.isNullOrBlank()) {
            val result = "I couldn't find an exact contact named $recipient."
            speak(result); done(result); return
        }

        appEngine.sendMessage(app, exactName!!, phone, email, body) { _, result ->
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

    private fun callContact(rawName: String, done: (String) -> Unit) {
        val sim = Regex("(?i)\\b(?:sim|slot)\\s*([12])\\s*$").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val name = rawName.replace(Regex("(?i)\\s+(?:sim|slot)\\s*[12]\\s*$"), "").trim()
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

        var foundName: String? = null
        var foundNumber: String? = null

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, null, null, null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val contactName = cursor.getString(nameIndex).orEmpty()
                if (contactName.equals(name, true)) {
                    foundName = contactName
                    foundNumber = cursor.getString(numberIndex)
                    break
                }
            }
        }

        if (foundNumber.isNullOrBlank()) {
            val result = "I couldn't find $name in your contacts."
            speak(result); done(result); return
        }

        placeCall(foundNumber!!, foundName ?: name, done, sim)
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
        if (target != null) lastAppName = target.label
        val message = if (target == null) "I couldn't find or open $appName on this phone." else "${target.label}"
        val finalMessage = if (target == null) message else "Opening $message."
        speak(finalMessage); done(finalMessage)
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

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ema")
    }
}
