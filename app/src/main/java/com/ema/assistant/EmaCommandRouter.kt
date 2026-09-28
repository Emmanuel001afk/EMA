package com.ema.assistant

import android.Manifest
import android.app.Activity
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

            lower.startsWith("text ") || lower.startsWith("send a text to ") || lower.startsWith("send a message to ") -> {
                openMessageComposer(command, done)
            }

            lower == "back" || lower == "go back" ->
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK, "Going back.", done)

            lower == "home" || lower == "go home" ->
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME, "Going home.", done)

            lower == "play" || lower == "pause" || lower == "play or pause" ->
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_MEDIA_PLAY_PAUSE, "Toggling media playback.", done)

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

            lower.startsWith("call ") -> callContact(command.substringAfter("call ").trim(), done)

            isAppSearchCommand(lower) -> {
                val parsed = parseAppSearch(command)
                if (parsed == null) {
                    speak("I could not understand which app you want to search.")
                    done("Try: go into YouTube and search for a topic.")
                } else {
                    val (app, query) = parsed
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
                val needsScreen = listOf("this screen", "my screen", "on screen", "this page", "current page", "what am i looking at", "read this")
                    .any(lower::contains)

                val prompt = if (needsScreen) {
                    val screen = EmaAccessibilityService.currentScreenText()
                    if (screen.isBlank()) command else "Current screen context:\n$screen\n\nUser request: $command"
                } else command

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

    private fun isAppSearchCommand(lower: String): Boolean =
        (lower.startsWith("open ") || lower.startsWith("go into ") || lower.startsWith("go to ")) &&
            (lower.contains(" search ") || lower.contains(" search for "))

    private fun parseAppSearch(command: String): Pair<String, String>? {
        val lower = command.lowercase(Locale.getDefault())
        val markers = listOf(" and search for ", " and search ", " search for ", " search ")
        val marker = markers.firstOrNull { lower.contains(it) } ?: return null
        val index = lower.indexOf(marker)
        val appPart = command.substring(0, index)
            .replaceFirst(Regex("(?i)^go into\\s+"), "")
            .replaceFirst(Regex("(?i)^go to\\s+"), "")
            .replaceFirst(Regex("(?i)^open\\s+"), "")
            .trim()
        val query = command.substring(index + marker.length).trim()
        if (appPart.isBlank() || query.isBlank()) return null
        return appPart to query
    }

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

    private fun callContact(name: String, done: (String) -> Unit) {
        if (name.isBlank()) {
            speak("Tell me who to call."); done("Tell me who to call."); return
        }

        if (name.matches(Regex("[+0-9()\\-\\s]{7,}"))) {
            placeCall(name.replace(Regex("[^+0-9]"), ""), name, done)
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
                if (contactName.equals(name, true) || contactName.contains(name, true)) {
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

        placeCall(foundNumber!!, foundName ?: name, done)
    }

    private fun placeCall(number: String, displayName: String, done: (String) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.CALL_PHONE)
            val result = "EMA needs phone-call permission before it can call $displayName. Grant it, then say call $displayName again."
            speak(result); done(result); return
        }

        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onSuccess {
            val result = "Calling $displayName."
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
