package com.ema.assistant

/** Conservative, provider-neutral evidence. Missing evidence means stop, not guess. */
object EmaActionEvidence {
    fun recipientHeaderMatches(screen: EmaScreenSnapshot, expectedPackage: String, recipient: String): Boolean {
        if (screen.packageName != expectedPackage || recipient.isBlank()) return false
        val name = EmaNodeSnapshot.normalize(recipient)
        val height = screen.nodes.maxOfOrNull { it.bottom } ?: return false
        return screen.nodes.any { node ->
            val headerId = node.viewIdLeaf.lowercase().let {
                it.contains("title") || it.contains("contact_name") || it.contains("conversation_name") || it.contains("toolbar")
            }
            node.visible && node.enabled && !node.editable &&
                node.top >= 0 && node.bottom > node.top && node.top < height / 4 &&
                (headerId || node.bottom < height / 5) &&
                (EmaNodeSnapshot.normalize(node.text) == name || EmaNodeSnapshot.normalize(node.description) == name)
        }
    }

    fun maySend(screen: EmaScreenSnapshot, expectedPackage: String, recipient: String, body: String): Boolean =
        body.isNotBlank() && recipientHeaderMatches(screen, expectedPackage, recipient) &&
            screen.nodes.any { it.visible && it.enabled && it.editable && it.text.trim() == body.trim() }

    fun transcriptBodyCount(screen: EmaScreenSnapshot, body: String): Int {
        if (body.isBlank()) return 0
        val wanted = EmaNodeSnapshot.normalize(body)
        return screen.nodes.count { it.visible && !it.editable && EmaNodeSnapshot.normalize(it.text) == wanted }
    }

    fun requestedMediaPlaying(expectedPackage: String, actualPackage: String, query: String,
                              title: String, artist: String, playing: Boolean): Boolean {
        if (!playing || expectedPackage.isBlank() || expectedPackage != actualPackage || title.isBlank()) return false
        fun words(value: String) = value.lowercase().split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.isNotBlank() }
        val requested = words(query).filterNot { it in setOf("by", "song", "track", "video") }
        val metadata = words("$title $artist").toSet()
        return requested.isNotEmpty() && requested.all { it in metadata }
    }
}
