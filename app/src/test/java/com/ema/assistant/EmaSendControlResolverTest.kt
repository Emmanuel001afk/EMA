package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "it typed the message but never pressed Send" class of failure.
 *
 * Real chat apps expose their submit control in incompatible ways, so each of
 * those shapes is reproduced here from observed node trees: a labelled button,
 * a description-only icon, an id-only control, and an unlabelled icon that can
 * only be found geometrically next to the composer.
 */
class EmaSendControlResolverTest {

    private fun node(
        index: Int,
        text: String = "",
        description: String = "",
        viewId: String = "",
        className: String = "android.widget.TextView",
        clickable: Boolean = true,
        enabled: Boolean = true,
        visible: Boolean = true,
        editable: Boolean = false,
        focused: Boolean = false,
        left: Int = 0,
        top: Int = 0,
        right: Int = 100,
        bottom: Int = 100,
        clickableAncestor: Int = -1
    ) = EmaNodeSnapshot(
        index = index, text = text, description = description, viewId = viewId,
        className = className, visible = visible, enabled = enabled,
        clickableSelf = clickable, clickableAncestorIndex = clickableAncestor,
        editable = editable, focused = focused,
        left = left, top = top, right = right, bottom = bottom
    )

    private fun composer(index: Int = 0) = node(
        index = index, className = "android.widget.EditText", editable = true, focused = true,
        clickable = false, left = 40, top = 2200, right = 900, bottom = 2300
    )

    // --------------------------------------------- WhatsApp's real Send shape

    @Test
    fun findsWhatsAppSendByViewId() {
        val screen = EmaScreenSnapshot(
            "com.whatsapp",
            listOf(
                composer(),
                node(1, description = "Message", className = "android.widget.EditText"),
                node(2, viewId = "com.whatsapp:id/send", description = "Send",
                    className = "android.widget.ImageButton", left = 940, top = 2210, right = 1030, bottom = 2290)
            )
        )
        val decision = EmaSendControlResolver.resolve(screen, screen.firstEditable)
        assertNotNull("WhatsApp's Send button must be found", decision)
        assertEquals(2, decision!!.nodeIndex)
    }

    @Test
    fun findsSendWhenOnlyTheViewIdMatchesAndThereIsNoLabel() {
        val screen = EmaScreenSnapshot(
            "com.example.chat",
            listOf(
                composer(),
                node(1, viewId = "com.example.chat:id/btn_send", className = "android.widget.ImageView",
                    left = 940, top = 2210, right = 1020, bottom = 2290)
            )
        )
        val decision = EmaSendControlResolver.resolve(screen, screen.firstEditable)
        assertNotNull("an id-only Send control must still be pressed", decision)
        assertEquals(1, decision!!.nodeIndex)
    }

    @Test
    fun findsSendWhenOnlyTheContentDescriptionMatches() {
        val screen = EmaScreenSnapshot(
            "org.telegram.messenger",
            listOf(
                composer(),
                node(1, description = "Send message", className = "android.widget.ImageView",
                    left = 940, top = 2210, right = 1020, bottom = 2290)
            )
        )
        val decision = EmaSendControlResolver.resolve(screen, screen.firstEditable)
        assertNotNull(decision)
        assertEquals(1, decision!!.nodeIndex)
    }

    @Test
    fun findsUnlabelledSendIconGeometrically() {
        val screen = EmaScreenSnapshot(
            "com.custom.chat",
            listOf(
                composer(),
                node(1, className = "androidx.compose.ui.platform.ComposeView",
                    left = 930, top = 2205, right = 1030, bottom = 2300)
            )
        )
        val decision = EmaSendControlResolver.resolve(screen, screen.firstEditable)
        assertNotNull("a square icon immediately right of the composer is the Send control", decision)
        assertEquals(1, decision!!.nodeIndex)
    }

    @Test
    fun clickFallsBackToTheClickableAncestor() {
        // Very common: the label sits on a child TextView while the click
        // handler is on the parent row. Only the ancestor is actionable.
        val screen = EmaScreenSnapshot(
            "com.example.chat",
            listOf(
                composer(),
                node(1, className = "android.widget.FrameLayout", left = 930, top = 2200, right = 1030, bottom = 2300),
                node(2, text = "Send", clickable = false, clickableAncestor = 1,
                    left = 940, top = 2210, right = 1020, bottom = 2290)
            )
        )
        val decision = EmaSendControlResolver.resolve(screen, screen.firstEditable)
        assertNotNull(decision)
        assertEquals(2, decision!!.nodeIndex)
        assertEquals("the click must be dispatched to the ancestor", 1, decision.clickTargetIndex)
    }

    // ------------------------------------------------------- false positives

    @Test
    fun neverPressesTheAttachmentOrEmojiControl() {
        val screen = EmaScreenSnapshot(
            "com.whatsapp",
            listOf(
                composer(),
                node(1, description = "Attach", viewId = "com.whatsapp:id/attach",
                    left = 10, top = 2210, right = 90, bottom = 2290),
                node(2, description = "Emoji", viewId = "com.whatsapp:id/emoji",
                    left = 95, top = 2210, right = 175, bottom = 2290),
                node(3, description = "Camera", left = 180, top = 2210, right = 260, bottom = 2290)
            )
        )
        assertNull("no Send control exists, so nothing may be pressed", EmaSendControlResolver.resolve(screen, screen.firstEditable))
    }

    @Test
    fun neverPressesTheComposerItself() {
        val screen = EmaScreenSnapshot("com.example.chat", listOf(composer()))
        assertNull(EmaSendControlResolver.resolve(screen, screen.firstEditable))
    }

    @Test
    fun ignoresDisabledAndInvisibleControls() {
        val screen = EmaScreenSnapshot(
            "com.example.chat",
            listOf(
                composer(),
                node(1, description = "Send", enabled = false, left = 940, top = 2210, right = 1030, bottom = 2290),
                node(2, description = "Send", visible = false, left = 940, top = 2210, right = 1030, bottom = 2290)
            )
        )
        assertNull("a disabled Send button must not be reported as pressed", EmaSendControlResolver.resolve(screen, screen.firstEditable))
    }

    @Test
    fun ignoresNonActionableControls() {
        val screen = EmaScreenSnapshot(
            "com.example.chat",
            listOf(
                composer(),
                node(1, description = "Send", clickable = false, clickableAncestor = -1,
                    left = 940, top = 2210, right = 1030, bottom = 2290)
            )
        )
        assertNull(EmaSendControlResolver.resolve(screen, screen.firstEditable))
    }

    @Test
    fun ignoresASenderDetailsRow() {
        val screen = EmaScreenSnapshot(
            "com.example.mail",
            listOf(
                composer(),
                node(1, text = "Sender details", left = 20, top = 400, right = 400, bottom = 460)
            )
        )
        assertNull(EmaSendControlResolver.resolve(screen, screen.firstEditable))
    }

    @Test
    fun sendIsPreferredOverAnUnrelatedRightHandIcon() {
        val screen = EmaScreenSnapshot(
            "com.example.chat",
            listOf(
                composer(),
                node(1, description = "More options", left = 940, top = 100, right = 1030, bottom = 190),
                node(2, description = "Send", viewId = "com.example.chat:id/send",
                    left = 940, top = 2210, right = 1030, bottom = 2290)
            )
        )
        val decision = EmaSendControlResolver.resolve(screen, screen.firstEditable)
        assertNotNull(decision)
        assertEquals(2, decision!!.nodeIndex)
    }

    // ------------------------------------------------------- localised labels

    @Test
    fun findsSendInOtherLanguages() {
        for (label in listOf("Enviar", "Envoyer", "Senden", "Inviare", "Kirim", "Gönder")) {
            val screen = EmaScreenSnapshot(
                "com.example.chat",
                listOf(composer(), node(1, description = label, className = "android.widget.ImageButton",
                    left = 940, top = 2210, right = 1030, bottom = 2290))
            )
            assertNotNull("Send labelled '$label' must be found", EmaSendControlResolver.resolve(screen, screen.firstEditable))
        }
    }

    // ------------------------------------------------------- send confirmed?

    @Test
    fun confirmedWhenComposerEmptiedAndBodyReachedTheTranscript() {
        assertTrue(
            EmaSendControlResolver.sendConfirmed(
                composerTextAfter = "",
                transcriptAfter = "Daniel\nhello there\n10:02",
                body = "hello there",
                composerStillFocused = true
            )
        )
    }

    @Test
    fun confirmedByTranscriptAlone() {
        assertTrue(
            EmaSendControlResolver.sendConfirmed(
                composerTextAfter = "hello there",
                transcriptAfter = "hello there",
                body = "hello there",
                composerStillFocused = false
            )
        )
    }

    @Test
    fun notConfirmedWhenTheFieldMerelyLostFocus() {
        // This is the exact false positive that used to be reported as success.
        assertFalse(
            EmaSendControlResolver.sendConfirmed(
                composerTextAfter = "",
                transcriptAfter = "Daniel\nonline",
                body = "hello there",
                composerStillFocused = false
            )
        )
    }

    @Test
    fun notConfirmedWhenTheBodyIsStillInTheComposer() {
        assertFalse(
            EmaSendControlResolver.sendConfirmed(
                composerTextAfter = "hello there",
                transcriptAfter = "Daniel\nonline",
                body = "hello there",
                composerStillFocused = true
            )
        )
    }

    @Test
    fun longBodyIsConfirmedByItsDistinctivePrefix() {
        assertTrue(
            EmaSendControlResolver.transcriptContains(
                "Daniel\nthe package arrived yesterday and I have left it on the table\n10:02",
                "the package arrived yesterday and I have left it on the table by the door"
            )
        )
    }

    @Test
    fun shortBodyDoesNotMatchInsideAnotherWord() {
        // Word-boundary matching: "ok" must not be "proved" by the word "look".
        assertFalse(EmaSendControlResolver.transcriptContains("look at this", "ok"))
        assertTrue(EmaSendControlResolver.transcriptContains("Daniel\nok\n10:02", "ok"))
    }

    @Test
    fun shortBodyAloneIsNotProofUnlessTheComposerEmptied() {
        // "ok" may already appear earlier in the same conversation, so the
        // transcript alone cannot confirm it was just sent.
        assertFalse(
            EmaSendControlResolver.sendConfirmed(
                composerTextAfter = "",
                transcriptAfter = "Daniel\nok\n10:00",
                body = "ok",
                composerStillFocused = false
            )
        )
        // Once the composer is also empty and still focused, it is confirmed.
        assertTrue(
            EmaSendControlResolver.sendConfirmed(
                composerTextAfter = "",
                transcriptAfter = "Daniel\nok\n10:02",
                body = "ok",
                composerStillFocused = true
            )
        )
    }

    // ------------------------------------------------------------ node model

    @Test
    fun nodeLabelCombinesTextAndDescription() {
        val n = node(0, text = "Send", description = "Send message")
        assertEquals("Send Send message", n.label)
        assertTrue(n.searchable.contains("send"))
    }

    @Test
    fun viewIdLeafStripsThePackagePrefix() {
        assertEquals("send_button", node(0, viewId = "com.whatsapp:id/send_button").viewIdLeaf)
        assertEquals("send", node(0, viewId = "send").viewIdLeaf)
    }

    @Test
    fun screenSnapshotExposesTheComposer() {
        val screen = EmaScreenSnapshot("com.whatsapp", listOf(composer(), node(1, text = "Send")))
        assertEquals(0, screen.firstEditable?.index)
        assertEquals(0, screen.focusedNode?.index)
        assertNull(screen.nodeAt(9))
    }

    @Test
    fun scoringRejectsGeometryAlone() {
        // A wide, non-icon-shaped control to the right of the composer is not
        // safe to press: it may be a suggestion chip rather than Send.
        val wide = node(
            1, className = "android.widget.LinearLayout",
            left = 900, top = 2200, right = 1600, bottom = 2300
        )
        val decision = EmaSendControlResolver.score(wide, composer())
        assertTrue("score ${decision?.score} must stay below the threshold", decision == null || decision.score < EmaSendControlResolver.ACCEPT_THRESHOLD)
    }
}
