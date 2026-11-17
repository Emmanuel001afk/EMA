package com.ema.assistant

import java.util.Locale

/**
 * Priority notification triage, summarization, sender/app filtering, OTP-safe
 * redaction and unambiguous inline-reply target matching — EMA's zero-network
 * answer to Apple Intelligence Priority Notifications and Gemini notification
 * summaries.
 *
 * Pure Kotlin, zero Android framework dependencies, exhaustively unit tested.
 */
object EmaNotificationDigest {

    enum class Priority { URGENT, MESSAGE, ALERT, LOW }

    data class Item(
        val key: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val category: String = "",
        val postTimeMs: Long = 0L,
        val isOngoing: Boolean = false,
        val canReply: Boolean = false
    )

    sealed class ReplyMatch {
        data class Single(val item: Item) : ReplyMatch()
        data class Ambiguous(val candidates: List<Item>, val prompt: String) : ReplyMatch()
        data class None(val reason: String) : ReplyMatch()
    }

    private val MESSAGE_CATEGORIES = setOf("msg", "email", "social")
    private val URGENT_CATEGORIES = setOf("call", "alarm", "event", "reminder", "err")
    private val MESSAGE_APP_HINTS = setOf(
        "whatsapp", "telegram", "signal", "messages", "messaging", "sms", "messenger",
        "slack", "discord", "teams", "gmail", "outlook", "mail", "instagram", "chat"
    )
    private val URGENT_KEYWORDS = Regex(
        "(?i)\\b(?:missed call|incoming call|urgent|emergency|action required|security alert|" +
            "declined|fraud|unauthorized|starting in \\d+|boarding|gate change|overdue)\\b"
    )
    private val ALERT_KEYWORDS = Regex(
        "(?i)\\b(?:delivered|out for delivery|arriving|shipped|driver|ride|payment|received|" +
            "credited|debited|transfer|invoice|receipt|order|booking|flight)\\b"
    )
    private val OTP_REDACT = Regex(
        "(?i)(\\b(?:otp|verification|security|login|2fa|passcode|one[- ]?time)\\s*(?:code|pin)?\\s*(?:is\\s*|:|-)?\\s*)\\d{4,8}\\b"
    )

    fun classify(item: Item): Priority {
        if (item.isOngoing && item.category !in URGENT_CATEGORIES) return Priority.LOW
        val cat = item.category.lowercase(Locale.ROOT)
        val combined = "${item.title} ${item.text}"
        if (cat in URGENT_CATEGORIES || URGENT_KEYWORDS.containsMatchIn(combined)) return Priority.URGENT
        val appLow = "${item.appName} ${item.packageName}".lowercase(Locale.ROOT)
        if (cat in MESSAGE_CATEGORIES || item.canReply || MESSAGE_APP_HINTS.any { appLow.contains(it) }) {
            return Priority.MESSAGE
        }
        if (ALERT_KEYWORDS.containsMatchIn(combined)) return Priority.ALERT
        return Priority.LOW
    }

    /** Redact raw one-time verification codes from spoken summaries unless explicitly requested. */
    fun redactSensitiveCodes(text: String): String {
        var safe = OTP_REDACT.replace(text, "$1[verification code hidden]")
        // Handles both "code: 123456" and "123456 is your verification code".
        if (Regex("(?i)\\b(?:otp|verification|passcode|one[- ]?time|2fa|security code|login code)\\b").containsMatchIn(text)) {
            safe = safe.replace(Regex("\\b[0-9]{4,8}\\b"), "[verification code hidden]")
        }
        return safe
    }

    /**
     * Build a concise, prioritized notification summary suitable for speaking
     * aloud or embedding in a morning briefing.
     */
    fun summarize(items: List<Item>, priorityOnly: Boolean = false, brief: Boolean = false): String {
        val meaningful = items
            .filter { it.title.isNotBlank() || it.text.isNotBlank() }
            .filterNot { it.isOngoing && classify(it) == Priority.LOW }
            .sortedWith(compareBy<Item> { classify(it).ordinal }.thenByDescending { it.postTimeMs })

        if (meaningful.isEmpty()) {
            return if (priorityOnly) "You have no urgent or active message notifications."
            else "You're all caught up — no active notifications."
        }

        val urgent = meaningful.filter { classify(it) == Priority.URGENT }
        val messages = meaningful.filter { classify(it) == Priority.MESSAGE }
        val alerts = meaningful.filter { classify(it) == Priority.ALERT }
        val relevant = if (priorityOnly) urgent + messages else meaningful

        if (relevant.isEmpty()) {
            return "No urgent notifications right now (${meaningful.size} low-priority update${if (meaningful.size == 1) "" else "s"})."
        }

        if (brief) {
            val parts = mutableListOf<String>()
            if (urgent.isNotEmpty()) parts += "${urgent.size} urgent"
            if (messages.isNotEmpty()) {
                val senders = messages.map { redactItemPart(it, it.title.ifBlank { it.appName }) }.distinct().take(3)
                parts += "${messages.size} message${if (messages.size == 1) "" else "s"} (${senders.joinToString(", ")})"
            }
            if (alerts.isNotEmpty()) parts += "${alerts.size} update${if (alerts.size == 1) "" else "s"}"
            val summary = parts.joinToString(", ").ifBlank { "${meaningful.size} active" }
            return "Notifications: $summary"
        }

        val sb = StringBuilder()
        sb.append("You have ${meaningful.size} notification${if (meaningful.size == 1) "" else "s"}")
        val byApp = meaningful.groupBy { it.appName.ifBlank { shortPkg(it.packageName) } }
        if (byApp.size > 1) {
            val appCounts = byApp.entries
                .sortedByDescending { it.value.size }
                .take(4)
                .joinToString(", ") { "${it.key} (${it.value.size})" }
            sb.append(" across $appCounts")
        }
        sb.append(".")

        val highlights = relevant.take(5).map { formatOne(it) }
        if (highlights.isNotEmpty()) {
            sb.append(" Top: ").append(highlights.joinToString(" · "))
        }
        return sb.toString()
    }

    /** Filter active notifications by app name or sender/keyword query. */
    fun filterByQuery(items: List<Item>, rawQuery: String): String {
        val q = rawQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return summarize(items)
        val matches = items.filter { item ->
            "${item.appName} ${item.packageName} ${item.title} ${item.text}".lowercase(Locale.ROOT).contains(q)
        }.sortedByDescending { it.postTimeMs }
        if (matches.isEmpty()) {
            return "You have no active notifications matching \"$rawQuery\"."
        }
        val body = matches.take(6).joinToString("\n") { "• ${formatOne(it)}" }
        return "${matches.size} notification${if (matches.size == 1) "" else "s"} matching \"$rawQuery\":\n$body"
    }

    /** Extract the newest OTP / verification code from recent notifications (on explicit owner request only). */
    fun findLatestVerificationCode(items: List<Item>): EmaScreenIntelligence.Entity? {
        for (item in items.sortedByDescending { it.postTimeMs }) {
            val combined = "${item.title}: ${item.text}"
            val otp = EmaScreenIntelligence.extractEntities(combined)
                .firstOrNull { it.kind == EmaScreenIntelligence.EntityKind.OTP_CODE }
            if (otp != null) return otp.copy(context = item.appName.ifBlank { shortPkg(item.packageName) })
        }
        return null
    }

    /**
     * Resolve which replyable notification the owner wants to reply to.
     * NEVER guesses when multiple distinct senders could match!
     */
    fun resolveReplyTarget(items: List<Item>, targetQuery: String?): ReplyMatch {
        val replyable = items.filter { it.canReply }.sortedByDescending { it.postTimeMs }
        if (replyable.isEmpty()) {
            return ReplyMatch.None("There are no active notifications with a direct reply action right now.")
        }
        val q = targetQuery?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (q.isEmpty()) {
            val distinctSenders = replyable.distinctBy { "${it.packageName}|${it.title.lowercase(Locale.ROOT)}" }
            return if (distinctSenders.size == 1) {
                ReplyMatch.Single(distinctSenders.first())
            } else {
                val names = distinctSenders.take(3).joinToString(" or ") {
                    "${it.title.ifBlank { it.appName }} (${it.appName})"
                }
                ReplyMatch.Ambiguous(
                    distinctSenders.take(3),
                    "You have replyable notifications from $names. Say \"reply to <name>'s notification saying <message>\" so I reply to the right person."
                )
            }
        }
        val matching = replyable.filter {
            it.title.lowercase(Locale.ROOT).contains(q) ||
                it.appName.lowercase(Locale.ROOT).contains(q) ||
                shortPkg(it.packageName).lowercase(Locale.ROOT).contains(q)
        }
        if (matching.isEmpty()) {
            return ReplyMatch.None("I couldn't find an active replyable notification from \"$targetQuery\".")
        }
        val distinct = matching.distinctBy { "${it.packageName}|${it.title.lowercase(Locale.ROOT)}" }
        return if (distinct.size == 1) {
            ReplyMatch.Single(distinct.first())
        } else {
            val names = distinct.take(3).joinToString(" or ") { "${it.title} (${it.appName})" }
            ReplyMatch.Ambiguous(
                distinct.take(3),
                "More than one notification matches \"$targetQuery\": $names. Please name the exact sender."
            )
        }
    }

    private fun redactItemPart(item: Item, part: String): String {
        val combined = "${item.title} ${item.text}"
        val codes = EmaScreenIntelligence.extractEntities(combined)
            .filter { it.kind == EmaScreenIntelligence.EntityKind.OTP_CODE }
        var safe = redactSensitiveCodes(part)
        for (code in codes) safe = safe.replace(code.value, "[verification code hidden]")
        return safe
    }

    private fun formatOne(item: Item): String {
        val app = item.appName.ifBlank { shortPkg(item.packageName) }
        val safeText = redactItemPart(item, item.text.replace(Regex("\\s+"), " ").trim()).take(110)
        val title = redactItemPart(item, item.title.trim()).take(80)
        return when {
            title.isNotEmpty() && safeText.isNotEmpty() -> "$app — $title: $safeText"
            title.isNotEmpty() -> "$app — $title"
            else -> "$app: $safeText"
        }
    }

    private fun shortPkg(pkg: String): String =
        pkg.substringAfterLast('.').replaceFirstChar { it.titlecase(Locale.ROOT) }
}
