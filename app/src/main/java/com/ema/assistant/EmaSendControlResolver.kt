package com.ema.assistant

/**
 * Deterministic resolution of a composer's submit control.
 *
 * This is the logic behind "send the message", and it is deliberately pure so
 * it can be exhaustively unit tested. Real chat applications disagree about
 * everything: the Send control may be a labelled button, an icon with only a
 * content description, a view id with no text at all, or an unlabelled sibling
 * sitting immediately to the right of the message field.
 *
 * The resolver therefore scores every candidate on screen along four
 * independent signals and returns the strongest one:
 *   1. view id leaf ("send", "send_button", "btn_send", "compose_send")
 *   2. label / content description, in any of the supported languages
 *   3. geometry relative to the composer field
 *   4. control class (ImageButton/Button next to an EditText)
 *
 * Controls that are clearly *not* a submit action (attach, emoji, camera,
 * microphone) are rejected outright rather than merely down-ranked, because a
 * false positive here sends a message the user did not ask for.
 */
object EmaSendControlResolver {

    /**
     * Minimum score accepted as a real submit control.
     *
     * Calibrated so that geometry alone is never enough: an unlabelled control
     * to the right of the composer must also be icon-shaped or a button class
     * (55 + 15/20) before EMA is willing to press it and send a message.
     */
    const val ACCEPT_THRESHOLD = 60

    private val SEND_WORDS = setOf(
        "send", "send message", "send message button", "send button", "sending",
        "submit", "go", "done", "post", "reply", "enviar", "enviar mensaje",
        "envoyer", "senden", "versturen", "inviare", "invia", "wyślij", "wyslij",
        "отправить", "أرسل", "ارسال", " enviar", "kirim", "gönder", "gonder",
        "ส่ง", "发送", "送信", "보내기", "aposta", "tuma"
    ).map { it.trim() }.toSet()

    /** Id fragments used by real messaging/mail apps for their submit control. */
    private val SEND_ID_FRAGMENTS = listOf(
        "send", "send_button", "sendbutton", "btn_send", "send_btn",
        "compose_send", "chat_send", "send_msg", "submit", "post_comment",
        "comment_send", "send_message", "message_send", "sendaction", "action_send"
    )

    /** Controls that must never be mistaken for Send. */
    private val EXCLUDED = listOf(
        "attach", "attachment", "attachment button", "emoji", "emoticon", "sticker",
        "gif", "camera", "photo", "gallery", "microphone", "mic ", "voice message",
        "record voice", "plus", "add", "more options", "overflow", "back",
        "close", "cancel", "delete", "clear", "location", "contact card",
        "payment", "poll", "document", "audio", "reaction", "like", "love"
    )

    data class Decision(
        val nodeIndex: Int,
        val clickTargetIndex: Int,
        val score: Int,
        val reason: String
    )

    /**
     * Choose the submit control for the given screen.
     *
     * @param composer the field holding the message, or null when unknown. Supplying it
     *                 enables the geometry signal, which is what rescues apps whose Send
     *                 control has no text and no useful view id.
     */
    fun resolve(screen: EmaScreenSnapshot, composer: EmaNodeSnapshot?): Decision? {
        var best: Decision? = null
        for (node in screen.nodes) {
            if (!node.visible || !node.enabled || !node.actionable) continue
            val decision = score(node, composer) ?: continue
            if (best == null || decision.score > best.score) best = decision
        }
        return best?.takeIf { it.score >= ACCEPT_THRESHOLD }
    }

    /** Score one candidate, or null when it is not a submit control at all. */
    fun score(node: EmaNodeSnapshot, composer: EmaNodeSnapshot?): Decision? {
        val label = EmaNodeSnapshot.normalize(node.label)
        val searchable = node.searchable
        val idLeaf = node.viewIdLeaf.lowercase()

        // Hard rejection beats every positive signal. A Send-labelled control
        // inside an attachment sheet is still not the composer's submit action.
        if (EXCLUDED.any { label.contains(it) || idLeaf.contains(it.replace(' ', '_')) }) return null
        // The composer itself must never be selected as its own submit control.
        if (node.editable) return null

        var score = 0
        val reasons = mutableListOf<String>()

        val idMatch = SEND_ID_FRAGMENTS.any { frag ->
            idLeaf == frag || idLeaf.startsWith("${frag}_") || idLeaf.endsWith("_$frag") ||
                idLeaf == frag.replace("_", "") || searchable.contains(":id/$frag") ||
                searchable.endsWith("/$frag")
        }
        if (idMatch) { score += 120; reasons += "id:$idLeaf" }

        val wordMatch = SEND_WORDS.any { word ->
            label == word || label.startsWith("$word ") || label.endsWith(" $word") ||
                label.contains("$word message") || label.contains("message $word")
        }
        if (wordMatch) { score += 110; reasons += "label:$label" }

        // "Send" appearing only as part of a longer description still counts,
        // but weaker: "Send message to group" versus "Sender details".
        if (!wordMatch && !idMatch && (label.contains("send") || label.contains("submit"))) {
            if (!label.contains("sender") && !label.contains("send again")) {
                score += 70; reasons += "partial-label"
            }
        }

        if (composer != null) {
            val verticalOverlap = minOf(node.bottom, composer.bottom) - maxOf(node.top, composer.top)
            val aligned = verticalOverlap > 0
            val rightOfComposer = node.left >= composer.right - 40
            val square = node.width in 24..260 && node.height in 24..260

            if (rightOfComposer && aligned) {
                score += 55; reasons += "right-of-composer"
                if (square) { score += 20; reasons += "square-icon" }
                if (node.className.contains("ImageButton") || node.className.contains("ImageView") ||
                    node.className.contains("Button") || node.className.contains("ComposeView")
                ) { score += 15; reasons += "button-class" }
            } else if (aligned && node.centerX > composer.centerX) {
                score += 30; reasons += "same-row-right"
            }

            // A control in the bottom-right of the screen above a bottom composer
            // is the conventional submit position on Android.
            if (aligned && node.right > composer.right) { score += 10; reasons += "trailing-edge" }
        }

        if (score <= 0) return null

        // Position alone is never sufficient. An unlabelled control to the right
        // of the composer also has to be icon-shaped or an actual button class,
        // otherwise a wide suggestion chip or toolbar container could be pressed
        // and silently send the message somewhere unintended.
        val semantic = idMatch || wordMatch || (!idMatch && !wordMatch && score >= 70 && reasons.contains("partial-label"))
        val geometric = reasons.contains("right-of-composer") &&
            (reasons.contains("square-icon") || reasons.contains("button-class"))
        if (!semantic && !geometric) return null

        return Decision(
            nodeIndex = node.index,
            clickTargetIndex = node.clickTargetIndex,
            score = score,
            reason = reasons.joinToString("+")
        )
    }

    /**
     * True when the visible evidence says a message actually left the composer.
     *
     * Two independent signals are required, because either one alone is
     * unreliable: the composer being emptied proves the app consumed the text,
     * and the body appearing in the conversation transcript proves it was
     * posted. Relying on "the field is blank" alone produced false successes
     * whenever the field merely lost focus.
     */
    fun sendConfirmed(
        composerTextAfter: String,
        transcriptAfter: String,
        body: String,
        composerStillFocused: Boolean
    ): Boolean {
        val normalizedBody = EmaNodeSnapshot.normalize(body)
        if (normalizedBody.isBlank()) return false
        val inTranscript = transcriptContains(transcriptAfter, body)
        val composerEmpty = composerTextAfter.isBlank()
        // A very short body such as "ok" or "hi" is likely to already appear in
        // an earlier part of the conversation, so on its own it is not proof.
        // For those the composer must also have been emptied *while still
        // focused*, which distinguishes "the app consumed the text" from "the
        // field simply lost focus and now reads as blank".
        val shortBody = normalizedBody.length < 4
        return when {
            inTranscript && composerEmpty && composerStillFocused -> true
            inTranscript && composerEmpty && !shortBody -> true
            else -> false
        }
    }

    fun transcriptContains(transcript: String, body: String): Boolean {
        val haystack = EmaNodeSnapshot.normalize(transcript)
        val needle = EmaNodeSnapshot.normalize(body)
        if (needle.isBlank()) return false
        if (wordBoundedContains(haystack, needle)) return true
        // Long bodies are often truncated in the visible transcript, so a
        // distinctive prefix is accepted as evidence.
        val probe = needle.take(40)
        return probe.length >= 12 && wordBoundedContains(haystack, probe)
    }

    /** Substring match that respects word edges, so "ok" does not match "look". */
    private fun wordBoundedContains(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        var from = 0
        while (true) {
            val index = haystack.indexOf(needle, from)
            if (index < 0) return false
            val before = if (index == 0) ' ' else haystack[index - 1]
            val afterIndex = index + needle.length
            val after = if (afterIndex >= haystack.length) ' ' else haystack[afterIndex]
            val leftOk = !before.isLetterOrDigit()
            // The right edge may be open when a long body was truncated on screen.
            val rightOk = !after.isLetterOrDigit() || needle.length >= 12
            if (leftOk && rightOk) return true
            from = index + 1
        }
    }
}
