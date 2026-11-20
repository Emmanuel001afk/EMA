package com.ema.assistant

import java.util.Locale

/** Device concepts and explicit operations. Labels in messages/searches never become authority. */
object EmaDeviceKnowledge {
    data class Feature(val id: String, val name: String, val aliases: List<String>, val action: String, val purpose: String, val guidance: String)
    enum class Operation { OPEN, STATUS, EXPLAIN, ENABLE, DISABLE, WORKFLOW }
    data class Request(val feature: Feature, val operation: Operation, val detail: String = "")
    val features = listOf(
        Feature("wifi", "Wi-Fi", listOf("wifi", "wi fi", "wi-fi", "wireless network"), "android.settings.WIFI_SETTINGS", "connects the phone to a wireless network", "Choose a network and enter its password yourself. Enabled Wi-Fi does not prove internet access."),
        Feature("bluetooth", "Bluetooth", listOf("bluetooth", "blue tooth"), "android.settings.BLUETOOTH_SETTINGS", "connects nearby accessories wirelessly", "Put the accessory in pairing mode, select it, and confirm the pairing code. Enabled Bluetooth does not prove an accessory is connected."),
        Feature("dnd", "Do Not Disturb", listOf("dnd", "do not disturb"), "android.settings.ZEN_MODE_SETTINGS", "filters interruptions according to Android notification rules", "Configure people, alarms, apps and schedules here. DND is not the same as muting media; policy access is required for EMA to change it."),
        Feature("airplane", "airplane mode", listOf("airplane mode", "aeroplane mode", "flight mode"), "android.settings.AIRPLANE_MODE_SETTINGS", "disables cellular radios; Wi-Fi and Bluetooth may remain separately enabled", "This can interrupt calls and internet access."),
        Feature("location", "location", listOf("location", "gps", "location services"), "android.settings.LOCATION_SOURCE_SETTINGS", "allows location services used by maps and other permitted apps", "Enabling location does not grant every app location permission or prove a GPS fix."),
        Feature("hotspot", "hotspot", listOf("hotspot", "personal hotspot", "tethering"), "android.settings.TETHER_SETTINGS", "shares a network connection with other devices", "Review the hotspot name, password, mobile-data cost and connected devices. EMA will not reveal or change the password automatically."),
        Feature("nfc", "NFC", listOf("nfc", "near field communication"), "android.settings.NFC_SETTINGS", "supports short-range contactless communication", "Hardware support is required. Turning NFC on is not authorization for a payment."),
        Feature("battery", "battery saver", listOf("battery saver", "power saving", "power saving mode"), "android.settings.BATTERY_SAVER_SETTINGS", "reduces background work to conserve battery", "Background notifications and EMA's wake service may be delayed."),
        Feature("display", "display", listOf("display", "screen", "brightness"), "android.settings.DISPLAY_SETTINGS", "controls brightness, rotation and other display preferences", "Some controls depend on the phone manufacturer. Brightness changes need write-settings access."),
        Feature("rotation", "auto rotate", listOf("auto rotate", "auto rotation", "screen rotation"), "android.settings.DISPLAY_SETTINGS", "lets the display orientation follow how you hold the phone", "Turning it off locks rotation; it does not choose a particular portrait or landscape orientation."),
        Feature("silent", "silent mode", listOf("silent mode", "silent", "ringer mute"), "android.settings.SOUND_SETTINGS", "silences the ringtone stream", "Media and alarm volume are separate. Android may require Do Not Disturb policy access."),
        Feature("vibrate", "vibrate mode", listOf("vibrate mode", "vibration mode"), "android.settings.SOUND_SETTINGS", "uses vibration instead of ringing for incoming calls", "Hardware, Do Not Disturb and app notification rules still affect alerts."),
        Feature("sound", "sound", listOf("sound", "volume", "ringtone", "vibration"), "android.settings.SOUND_SETTINGS", "controls audio streams, ringing and vibration", "Media, alarm and ringtone volumes are different controls."),
        Feature("notifications", "notifications", listOf("notifications", "notification settings"), "android.settings.NOTIFICATION_SETTINGS", "controls which apps may interrupt you", "Notification access for reading and permission for posting notifications are separate."),
        Feature("apps", "applications", listOf("apps", "applications", "app permissions", "permissions"), "android.settings.APPLICATION_SETTINGS", "manages installed applications and their permissions", "Grant permissions yourself after reviewing the requesting app. EMA cannot grant itself access."),
        Feature("storage", "storage", listOf("storage", "device storage"), "android.settings.INTERNAL_STORAGE_SETTINGS", "shows device storage use", "Review files before deletion. Opening storage is not permission to erase anything."),
        Feature("accessibility", "accessibility", listOf("accessibility", "accessibility settings"), "android.settings.ACCESSIBILITY_SETTINGS", "provides assistive interaction services", "EMA needs its accessibility service enabled to inspect controls. Only enable services you trust."),
        Feature("vpn", "VPN", listOf("vpn"), "android.settings.VPN_SETTINGS", "routes traffic through a configured virtual private network", "Choose a trusted provider. EMA will not invent credentials or approve a VPN consent dialog."),
        Feature("data", "mobile data", listOf("mobile data", "cellular data", "data usage"), "android.settings.DATA_USAGE_SETTINGS", "uses your mobile operator's internet connection", "Operator charges and SIM selection apply. EMA cannot bypass carrier restrictions."),
        Feature("date", "date and time", listOf("date and time", "time settings", "time zone"), "android.settings.DATE_SETTINGS", "controls system time and time zone", "Incorrect time can disrupt alarms and secure connections."),
        Feature("security", "security", listOf("security", "screen lock", "fingerprint"), "android.settings.SECURITY_SETTINGS", "protects the phone and its credentials", "Authentication and security confirmations must be completed by you."),
        Feature("keyboard", "keyboard", listOf("keyboard", "input method", "languages"), "android.settings.INPUT_METHOD_SETTINGS", "manages keyboards and input languages", "A keyboard language does not change EMA's bundled English acoustic model."),
        Feature("sync", "accounts and sync", listOf("accounts", "sync", "accounts and sync"), "android.settings.SYNC_SETTINGS", "manages account synchronization", "Review account details yourself. EMA does not know passwords.")
    )
    fun find(name: String): Feature? {
        val key = name.lowercase(Locale.ROOT).trim().removePrefix("the ").removePrefix("my ").removeSuffix(" settings").trim()
        return features.firstOrNull { key == it.id || key == it.name.lowercase(Locale.ROOT) || it.aliases.any { alias -> key == alias.removeSuffix(" settings").trim() } }
    }
    fun parse(raw: String): Request? {
        val t = raw.lowercase(Locale.ROOT).trim().trimEnd('?', '.', '!')
        val forms = listOf(
            Operation.STATUS to Regex("^(?:is|are) (.+?) (?:on|off|enabled|disabled)$"),
            Operation.STATUS to Regex("^(?:check|show|tell me) (?:the |my )?(.+?) (?:state|status)$"),
            Operation.EXPLAIN to Regex("^(?:what is|what does|explain) (.+?)(?: do| mean| used for)?$"),
            Operation.OPEN to Regex("^(?:open|show|find|go to|take me to|manage|configure) (.+?)(?: settings)?$"),
            Operation.OPEN to Regex("^(?:search|look) (?:the )?settings for (.+)$"),
            Operation.ENABLE to Regex("^(?:turn on|switch on|enable|activate) (.+)$"),
            Operation.DISABLE to Regex("^(?:turn off|switch off|disable|deactivate) (.+)$"),
            Operation.ENABLE to Regex("^(?:turn|switch) (.+?) on$"),
            Operation.DISABLE to Regex("^(?:turn|switch) (.+?) off$"),
            Operation.ENABLE to Regex("^(.+?) on$"),
            Operation.DISABLE to Regex("^(.+?) off$")
        )
        for ((op, pattern) in forms) {
            val target = pattern.matchEntire(t)?.groupValues?.get(1) ?: continue
            val feature = find(target) ?: continue
            return Request(feature, op)
        }
        Regex("^(connect to|pair|connect|disconnect|forget|scan for|search for) (.+)$").matchEntire(t)?.let { m ->
            val subject = m.groupValues[2]
            val feature = when {
                subject.startsWith("wifi ") || subject.startsWith("wi-fi ") || subject in listOf("wifi", "wi-fi", "networks") -> find("wifi")
                subject.startsWith("bluetooth ") || subject in listOf("bluetooth", "nearby devices") -> find("bluetooth")
                else -> null
            } ?: return null
            return Request(feature, Operation.WORKFLOW, raw.trim())
        }
        return null
    }
}
