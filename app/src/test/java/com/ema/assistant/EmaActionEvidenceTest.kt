package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaActionEvidenceTest {
    private fun chat(name: String = "Daniel", body: String = "I am coming home", pkg: String = "chat.app") =
        EmaScreenSnapshot(pkg, listOf(
            EmaNodeSnapshot(0, text = name, viewId = "chat.app:id/conversation_title", visible = true,
                enabled = true, top = 30, bottom = 90),
            EmaNodeSnapshot(1, text = body, editable = true, focused = true, visible = true,
                enabled = true, top = 800, bottom = 950)
        ))

    @Test fun exactHeaderAndBodyAreRequired() {
        assertTrue(EmaActionEvidence.maySend(chat(), "chat.app", "Daniel", "I am coming home"))
        assertFalse(EmaActionEvidence.maySend(chat("Danielle"), "chat.app", "Daniel", "I am coming home"))
        assertFalse(EmaActionEvidence.maySend(chat(body = "different draft"), "chat.app", "Daniel", "I am coming home"))
        assertFalse(EmaActionEvidence.maySend(chat(pkg = "other.app"), "chat.app", "Daniel", "I am coming home"))
    }
    @Test fun bodyCannotStandInForHeader() {
        val screen = chat("Other person", "Daniel")
        assertFalse(EmaActionEvidence.recipientHeaderMatches(screen, "chat.app", "Daniel"))
    }
    @Test fun composerIsNotATranscriptBubble() {
        assertEquals(0, EmaActionEvidence.transcriptBodyCount(chat(), "I am coming home"))
        val screen = chat().let { it.copy(nodes = it.nodes + EmaNodeSnapshot(2,
            text = "I am coming home", visible = true, top = 300, bottom = 350)) }
        assertEquals(1, EmaActionEvidence.transcriptBodyCount(screen, "I am coming home"))
    }
    @Test fun playingSomeOtherSongOrAppIsNotSuccess() {
        fun verified(pkg: String, title: String, playing: Boolean = true) =
            EmaActionEvidence.requestedMediaPlaying("spotify", pkg, "Arcade", title, "Duncan Laurence", playing)
        assertTrue(verified("spotify", "Arcade"))
        assertFalse(verified("youtube", "Arcade"))
        assertFalse(verified("spotify", "Another song"))
        assertFalse(verified("spotify", "Arcade", false))
        assertFalse(verified("spotify", "Arcades"))
    }
}
