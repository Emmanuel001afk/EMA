package com.ema.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EMA's screen understanding and screen control layer.
 *
 * Two rules shape everything in this file.
 *
 * 1. EVERY asynchronous request terminates. The previous implementation queued
 *    a search and waited for an accessibility event that might never arrive —
 *    when the service was disconnected, or when the target app never exposed an
 *    editable field, the callback was simply never invoked and the user waited
 *    for the router's 60 second timeout. Every path here now ends in exactly
 *    one callback, on a deadline, whether it succeeded or not.
 *
 * 2. Control resolution is layered, not hopeful. Accessibility semantics are
 *    tried first, then view ids, then geometry relative to the focused field,
 *    then the keyboard's own action button, and only then OCR over a screenshot.
 *    Each layer is attempted with retries because chat applications build their
 *    Send control *after* text arrives, so a single immediate attempt misses it.
 */
class EmaAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var assistantScreenshot: Bitmap? = null
    private var searchDriverScheduled = false

    companion object {
        @Volatile private var instance: EmaAccessibilityService? = null
        private val pendingSearch = ArrayDeque<PendingSearch>()
        private val pendingSearchLock = Any()

        /** How long a screen search may keep retrying before it reports failure. */
        private const val SEARCH_DEADLINE_MS = 9_000L
        private const val SEARCH_RETRY_MS = 220L
        private const val MAX_SEARCH_ATTEMPTS = 40

        private data class PendingSearch(
            val packageName: String?,
            val query: String,
            val callback: (Boolean) -> Unit,
            val deadline: Long,
            val attempts: Int,
            val finished: AtomicBoolean = AtomicBoolean(false)
        )

        fun isConnected(): Boolean = instance != null

        /**
         * Human-readable reason screen control is unavailable, or null when it
         * is ready. Callers use this to tell the user what to switch on instead
         * of silently timing out.
         */
        fun availabilityProblem(): String? =
            if (instance != null) null
            else "EMA screen control is off. Open EMA, choose \"Enable EMA screen control\", and turn on the EMA service in Accessibility settings."

        fun requestTextSearch(query: String) {
            searchInPackage(null, query) {}
        }

        /**
         * Type [query] into the search/edit field of [packageName].
         *
         * The callback is guaranteed to run exactly once: either the text was
         * committed, or the deadline passed, or screen control is unavailable.
         */
        fun searchInPackage(packageName: String?, query: String, callback: (Boolean) -> Unit) {
            val service = instance
            if (service == null) {
                android.util.Log.w("EMA", "searchInPackage('$query') rejected: screen control service is not connected")
                runCatching { callback(false) }
                return
            }
            val request = PendingSearch(
                packageName = packageName,
                query = query,
                callback = callback,
                deadline = SystemClock.uptimeMillis() + SEARCH_DEADLINE_MS,
                attempts = 0
            )
            synchronized(pendingSearchLock) {
                // A bounded queue prevents one stuck request from blocking every
                // later command for the rest of the process lifetime.
                while (pendingSearch.size >= 4) {
                    val dropped = pendingSearch.removeFirst()
                    finishSearch(dropped, false)
                }
                pendingSearch.addLast(request)
            }
            service.ensureSearchDriver()
        }

        private fun finishSearch(request: PendingSearch, success: Boolean) {
            if (!request.finished.compareAndSet(false, true)) return
            runCatching { request.callback(success) }
                .onFailure { android.util.Log.e("EMA", "search callback failed", it) }
        }

        fun updateAssistantScreenshot(bitmap: Bitmap?) {
            instance?.setAssistantScreenshotInternal(bitmap)
        }

        fun captureDisplayScreenshot(callback: (Bitmap?) -> Unit) {
            val service = instance
            if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                callback(null)
                return
            }
            service.captureDisplayScreenshotInternal(callback)
        }

        fun currentScreenText(maxChars: Int = 12000): String =
            instance?.readCurrentWindow(maxChars).orEmpty()

        fun performGlobalAction(action: Int): Boolean =
            instance?.performGlobalAction(action) == true

        fun tapText(text: String): Boolean = instance?.tapTextInternal(text) == true

        fun tapTextExact(text: String): Boolean = instance?.tapTextExactInternal(text) == true

        fun setFocusedText(text: String): Boolean = instance?.setFocusedTextInternal(text) == true

        /** One synchronous, semantic Send attempt on a verified chat and body.
         * No delayed geometric/OCR/IME fallback may act after the target changes.
         */
        fun sendVerified(packageName: String, recipient: String, body: String): Boolean {
            val service = instance ?: return false
            if (service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return false
            val screen = service.screenModelInternal(500, packageName).snapshot
            if (!EmaActionEvidence.maySend(screen, packageName, recipient, body)) return false
            return service.trySnapshotSend(packageName)
        }

        fun selectContactInTarget(packageName: String, name: String): Boolean {
            val service = instance ?: return false
            val model = service.screenModelInternal(500, packageName)
            val matches = model.snapshot.nodes.filter {
                it.visible && it.enabled && !it.editable && it.actionable &&
                    (EmaNodeSnapshot.normalize(it.text) == EmaNodeSnapshot.normalize(name) ||
                        EmaNodeSnapshot.normalize(it.description) == EmaNodeSnapshot.normalize(name))
            }.distinctBy { it.clickTargetIndex }
            if (matches.size != 1) return false
            return model.nodes.getOrNull(matches.single().clickTargetIndex)
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }

        fun selectMediaResult(packageName: String, query: String): Boolean {
            val service = instance ?: return false
            val model = service.screenModelInternal(500, packageName)
            val result = model.snapshot.nodes.firstOrNull {
                it.visible && it.enabled && !it.editable && it.actionable &&
                    EmaExecutionVerifier.queryVisible(it.label, query)
            } ?: return false
            return model.nodes.getOrNull(result.clickTargetIndex)
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }

        fun playInTarget(packageName: String): Boolean {
            val service = instance ?: return false
            val model = service.screenModelInternal(500, packageName)
            val play = model.snapshot.nodes.firstOrNull {
                it.visible && it.enabled && it.actionable &&
                    EmaNodeSnapshot.normalize(it.label) in setOf("play", "play song", "play video", "play track", "resume playback")
            } ?: return false
            return model.nodes.getOrNull(play.clickTargetIndex)
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }

        /** Package of the top application window, or "" when unknown. */
        fun foregroundPackage(): String {
            val service = instance ?: return ""
            return runCatching {
                service.windows.orEmpty().filter {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                }.maxByOrNull { it.layer }?.root?.packageName?.toString()
                    ?: service.rootInActiveWindow?.packageName?.toString()
            }.getOrNull().orEmpty()
        }

        /** Result rows currently visible in [packageName]'s top window (generic, see EmaResultExtractor). */
        fun resultItems(packageName: String, query: String?): List<EmaResultItem> {
            val service = instance ?: return emptyList()
            val snapshot = service.screenModelInternal(RESULT_NODE_LIMIT, packageName).snapshot
            return EmaResultExtractor.extract(snapshot, query)
        }

        /**
         * Click a previously observed result row. The screen is read again and
         * the row is matched by title (and roughly by position) first, so a
         * list that moved or changed between observing and acting can never
         * cause a different item to be tapped.
         */
        fun clickResult(packageName: String, item: EmaResultItem, query: String?): Boolean {
            val service = instance ?: return false
            if (service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return false
            val model = service.screenModelInternal(RESULT_NODE_LIMIT, packageName)
            val items = EmaResultExtractor.extract(model.snapshot, query)
            val wanted = EmaResultExtractor.normalizeTitle(item.title)
            val same = items.filter { EmaResultExtractor.normalizeTitle(it.title) == wanted }
                .minByOrNull { kotlin.math.abs(it.top - item.top) } ?: return false
            val node = model.nodes.getOrNull(same.clickIndex) ?: return false
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            // Some lists only respond to a real gesture on the row.
            return service.tapCoordinatesInternal((same.left + same.right) / 2f, (same.top + same.bottom) / 2f)
        }

        /** Scroll the largest scrollable container of [packageName] forward (loads more results). */
        fun scrollResultsForward(packageName: String): Boolean {
            val service = instance ?: return false
            val model = service.screenModelInternal(RESULT_NODE_LIMIT, packageName)
            val list = model.snapshot.nodes.filter { it.scrollable && it.visible }
                .maxByOrNull { it.width.toLong() * it.height } ?: return service.scrollInternal("down")
            return model.nodes.getOrNull(list.index)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true ||
                service.scrollInternal("down")
        }

        private const val RESULT_NODE_LIMIT = 1200

        fun targetSnapshot(packageName: String): EmaScreenSnapshot =
            instance?.screenModelInternal(500, packageName)?.snapshot ?: EmaScreenSnapshot("", emptyList())

        fun typeMessageVerified(packageName: String, recipient: String, body: String): Boolean {
            val service = instance ?: return false
            if (service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return false
            val model = service.screenModelInternal(500, packageName)
            if (!EmaActionEvidence.recipientHeaderMatches(model.snapshot, packageName, recipient)) return false
            val field = model.snapshot.focusedNode ?: model.snapshot.firstEditable ?: return false
            return model.nodes.getOrNull(field.index)?.let { service.commitText(it, body) } == true
        }

        fun focusedEditableText(): String = instance?.focusedEditableTextInternal().orEmpty()

        /** True when a text field currently holds input focus. */
        fun composerHasFocus(): Boolean = instance?.composerHasFocusInternal() == true

        fun scroll(direction: String): Boolean = instance?.scrollInternal(direction) == true

        fun inspectCurrentUi(maxChars: Int = 16000): String =
            instance?.inspectCurrentUiInternal(maxChars).orEmpty()

        /**
         * A provider-neutral snapshot of every readable control on screen.
         *
         * All decisions about which control to press are made on this snapshot
         * by pure, unit-tested logic instead of being buried in node traversal.
         */
        fun screenSnapshot(maxNodes: Int = 500): EmaScreenSnapshot =
            instance?.screenSnapshotInternal(maxNodes) ?: EmaScreenSnapshot("", emptyList())

        fun activate(text: String): Boolean = instance?.activateTextInternal(text) == true

        /**
         * Accessibility semantics first, then visual OCR coordinates when the
         * visible control is missing from the node tree.
         */
        fun activateWithVisualFallback(text: String, callback: (Boolean) -> Unit) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            if (service.activateTextInternal(text)) {
                callback(true)
                return
            }
            service.captureAndTapTextVisuallyWithRetry(
                packageName = service.rootInActiveWindow?.packageName?.toString().orEmpty(),
                target = text,
                attempts = 3,
                callback = callback
            )
        }

        fun longPressText(text: String): Boolean = instance?.longPressTextInternal(text) == true

        fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

        fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

        fun tapCoordinates(x: Float, y: Float): Boolean =
            instance?.tapCoordinatesInternal(x, y) == true

        fun tapFirstResult(query: String = ""): Boolean =
            instance?.tapFirstResultInternal(query) == true

        fun tapSendButton(packageName: String): Boolean =
            instance?.tapSendButtonInternal(packageName) == true

        /**
         * Press the composer's submit control using every available route.
         *
         * Retries matter: chat apps typically enable or even create the Send
         * control only after the text has been committed and laid out, so an
         * immediate single attempt routinely misses it. That single missed
         * attempt is exactly the "typed the message but never sent it" failure.
         */
        fun pressSend(packageName: String, attempts: Int = 5, callback: (Boolean) -> Unit) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            service.pressSendInternal(packageName, attempts, callback)
        }

        /** Retained entry point; now routes through the layered resolver. */
        fun tapSendButtonWithVisualFallback(packageName: String, callback: (Boolean) -> Unit) {
            pressSend(packageName, 5, callback)
        }

        fun toggleMediaPlayback(): Boolean = instance?.mediaKeyInternal(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) == true

        fun mediaTransport(action: MediaTransportAction): Boolean =
            instance?.mediaKeyInternal(action.keyCode) == true

        /**
         * Open a search entry point when the current screen has no editable
         * field. Apps such as Spotify keep search on a separate navigation tab,
         * so "type the song into the search box" needs the box to be revealed
         * first.
         */
        fun revealSearchEntry(packageName: String?): Boolean =
            instance?.revealSearchEntryInternal(packageName) == true

        fun describeScreenImage(callback: (String) -> Unit) {
            val service = instance
            if (service == null) callback("") else service.captureAndLabel(callback)
        }

        fun readScreenWithOcr(callback: (String) -> Unit) {
            val service = instance
            if (service == null) callback("") else service.captureAndRecognize(callback)
        }

        /** True when Android reports music/audio playback is actually running. */
        fun isAudioPlaying(): Boolean = instance?.isAudioPlayingInternal() == true
    }

    enum class MediaTransportAction(val keyCode: Int) {
        PLAY(KeyEvent.KEYCODE_MEDIA_PLAY),
        PAUSE(KeyEvent.KEYCODE_MEDIA_PAUSE),
        PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        STOP(KeyEvent.KEYCODE_MEDIA_STOP),
        NEXT(KeyEvent.KEYCODE_MEDIA_NEXT),
        PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    // ------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        android.util.Log.i("EMA", "Screen control service connected")
        ensureSearchDriver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Cheap: only drives work that is actually queued.
        if (hasPendingSearch()) drivePendingSearch()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        val queued = synchronized(pendingSearchLock) {
            val out = pendingSearch.toList()
            pendingSearch.clear()
            out
        }
        queued.forEach { finishSearch(it, false) }
        searchDriverScheduled = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun hasPendingSearch(): Boolean =
        synchronized(pendingSearchLock) { pendingSearch.isNotEmpty() }

    /**
     * Keeps a timer running while any search is queued, so progress does not
     * depend on the target app happening to emit an accessibility event.
     */
    private fun ensureSearchDriver() {
        if (searchDriverScheduled) return
        if (!hasPendingSearch()) return
        searchDriverScheduled = true
        mainHandler.postDelayed({
            searchDriverScheduled = false
            drivePendingSearch()
            if (hasPendingSearch()) ensureSearchDriver()
        }, SEARCH_RETRY_MS)
    }

    private fun drivePendingSearch() {
        val request = synchronized(pendingSearchLock) { pendingSearch.firstOrNull() } ?: return
        val now = SystemClock.uptimeMillis()

        if (now >= request.deadline || request.attempts >= MAX_SEARCH_ATTEMPTS) {
            dropHead(request)
            android.util.Log.w(
                "EMA",
                "screen search expired for '${request.query}' in ${request.packageName ?: "any app"} after ${request.attempts} attempts"
            )
            finishSearch(request, false)
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            advance(request)
            return
        }
        if (request.packageName != null && root.packageName?.toString() != request.packageName) {
            // The target app is not in front yet. Wait for it rather than typing
            // the query into whatever else happens to be open.
            advance(request)
            return
        }

        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: findEditable(root)
            ?: findSearchNode(root)

        if (field == null || !field.isEditable) {
            // Reveal a hidden search entry (tab, icon, or collapsed search view)
            // and retry on the next tick. Either way the attempt is consumed.
            clickSearchControl(root)
            revealSearchEntryInternal(request.packageName)
            advance(request)
            return
        }

        if (commitText(field, request.query)) {
            // Nudge the app to run its search: the IME action first, then any
            // explicit search/go control that is still on screen.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
            }
            clickSearchControl(root)
            dropHead(request)
            finishSearch(request, true)
            return
        }

        advance(request)
    }

    private fun advance(request: PendingSearch) {
        synchronized(pendingSearchLock) {
            if (pendingSearch.firstOrNull() !== request) return
            pendingSearch.removeFirst()
            pendingSearch.addFirst(request.copy(attempts = request.attempts + 1))
        }
    }

    private fun dropHead(request: PendingSearch) {
        synchronized(pendingSearchLock) {
            if (pendingSearch.firstOrNull() === request) pendingSearch.removeFirst()
        }
    }

    private fun commitText(field: AccessibilityNodeInfo, value: String): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // Some fields only accept text once they hold input focus.
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ------------------------------------------------------- screen reading

    private fun readCurrentWindow(maxChars: Int): String {
        val root = rootInActiveWindow ?: return ""
        val out = StringBuilder()

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || out.length >= maxChars) return
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val value = when {
                text.isNotBlank() -> text
                description.isNotBlank() -> description
                else -> ""
            }
            if (value.isNotBlank()) {
                val normalized = value.replace(Regex("\\s+"), " ")
                if (normalized.length <= 500) out.append(normalized).append('\n')
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
                if (out.length >= maxChars) return
            }
        }

        walk(root)
        return out.toString().trim().take(maxChars)
    }

    private fun inspectCurrentUiInternal(maxChars: Int): String {
        val root = rootInActiveWindow ?: return ""
        val out = StringBuilder()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || out.length >= maxChars) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = when {
                text.isNotBlank() && desc.isNotBlank() -> "$text ($desc)"
                text.isNotBlank() -> text
                desc.isNotBlank() -> desc
                else -> ""
            }.replace(Regex("\\s+"), " ")

            if (label.isNotBlank()) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                out.append("  ".repeat(depth.coerceAtMost(8)))
                    .append(label.take(300))
                    .append(" | clickable=").append(node.isClickable)
                    .append(" | editable=").append(node.isEditable)
                    .append(" | scrollable=").append(node.isScrollable)
                    .append(" | enabled=").append(node.isEnabled)
                    .append(" | id=").append(node.viewIdResourceName.orEmpty())
                    .append(" | bounds=").append(bounds)
                    .append('\n')
            }

            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        return out.toString().trim().take(maxChars)
    }

    /**
     * Build the provider-neutral screen model used by every resolver.
     *
     * Interactive windows are included (dialogs, bottom sheets, IME) because
     * with only the active window readable, a permission dialog or a keyboard
     * makes the app underneath look like it has no controls at all.
     */
    /** A screen snapshot plus the live nodes it was built from, index-aligned. */
    private class ScreenModel(
        val snapshot: EmaScreenSnapshot,
        val nodes: List<AccessibilityNodeInfo>
    )

    private fun screenSnapshotInternal(maxNodes: Int): EmaScreenSnapshot =
        screenModelInternal(maxNodes).snapshot

    private fun screenModelInternal(maxNodes: Int, targetPackage: String? = null): ScreenModel {
        val roots = if (targetPackage == null) collectRoots() else {
            // Never combine keyboard/assistant/other-app controls with a chat.
            // Only the top application window may be a target, including when
            // an IME or the system-assistant window has input focus above it.
            val application = windows.orEmpty().filter {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
            }.maxByOrNull { it.layer }?.root ?: rootInActiveWindow
            if (application?.packageName?.toString() == targetPackage) listOf(application) else emptyList()
        }
        if (roots.isEmpty()) return ScreenModel(EmaScreenSnapshot("", emptyList()), emptyList())

        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val parentIndex = mutableListOf<Int>()

        fun walk(node: AccessibilityNodeInfo?, parent: Int) {
            if (node == null || nodes.size >= maxNodes) return
            val self = nodes.size
            nodes += node
            parentIndex += parent
            for (i in 0 until node.childCount) {
                if (nodes.size >= maxNodes) return
                walk(node.getChild(i), self)
            }
        }

        for (root in roots) walk(root, -1)
        if (nodes.isEmpty()) return ScreenModel(EmaScreenSnapshot("", emptyList()), emptyList())

        val snapshots = ArrayList<EmaNodeSnapshot>(nodes.size)
        for (index in nodes.indices) {
            val node = nodes[index]
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            snapshots += EmaNodeSnapshot(
                index = index,
                text = node.text?.toString()?.trim().orEmpty(),
                description = node.contentDescription?.toString()?.trim().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                visible = node.isVisibleToUser,
                enabled = node.isEnabled,
                clickableSelf = node.isClickable,
                clickableAncestorIndex = clickableAncestorOf(index, nodes, parentIndex),
                editable = node.isEditable,
                focused = node.isFocused,
                scrollable = node.isScrollable,
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom
            )
        }

        val packageName = roots.firstOrNull()?.packageName?.toString().orEmpty()
        return ScreenModel(EmaScreenSnapshot(packageName, snapshots), nodes)
    }

    private fun clickableAncestorOf(
        index: Int,
        nodes: List<AccessibilityNodeInfo>,
        parentIndex: List<Int>
    ): Int {
        var cursor = parentIndex.getOrElse(index) { -1 }
        var guard = 0
        while (cursor >= 0 && guard++ < 64) {
            val node = nodes.getOrNull(cursor) ?: return -1
            if (node.isClickable && node.isEnabled && node.isVisibleToUser) return cursor
            cursor = parentIndex.getOrElse(cursor) { -1 }
        }
        return -1
    }

    /** Roots of every window EMA is allowed to read, most relevant first. */
    private fun collectRoots(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            for (window in windows.orEmpty()) {
                val root = window?.root ?: continue
                out += root
            }
        }.onFailure {
            android.util.Log.w("EMA", "interactive window enumeration failed; using the active window", it)
        }
        if (out.isEmpty()) rootInActiveWindow?.let { out += it }
        return out
    }

    // ------------------------------------------------------- control action

    private fun activateTextInternal(text: String): Boolean {
        val node = findBestVisibleNode(text) ?: return false
        return when {
            node.isClickable -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            clickableAncestor(node)?.let { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true -> true
            node.isFocusable -> node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            else -> node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
        }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        var guard = 0
        while (parent != null && guard++ < 32) {
            if (parent.isVisibleToUser && parent.isEnabled && parent.isClickable) return parent
            parent = parent.parent
        }
        return null
    }

    private fun longPressTextInternal(text: String): Boolean {
        val node = findBestVisibleNode(text) ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return false
        return dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat(), 900L)
    }

    private fun findBestVisibleNode(query: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val wanted = query.trim().lowercase()
        if (wanted.isBlank()) return null
        var exact: AccessibilityNodeInfo? = null
        var contains: AccessibilityNodeInfo? = null

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser || !node.isEnabled) return
            val text = node.text?.toString().orEmpty().trim().lowercase()
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
            if (text == wanted || desc == wanted) {
                if (exact == null) exact = node
            } else if (contains == null && (text.contains(wanted) || desc.contains(wanted))) {
                contains = node
            }
            for (i in 0 until node.childCount) {
                if (exact == null) walk(node.getChild(i))
            }
        }

        walk(root)
        return exact ?: contains
    }

    private fun tapTextExactInternal(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val wanted = text.trim().lowercase()
        if (wanted.isBlank()) return false

        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser || !node.isEnabled) return null
            val nodeText = node.text?.toString().orEmpty().trim().lowercase()
            val nodeDesc = node.contentDescription?.toString().orEmpty().trim().lowercase()
            if (nodeText == wanted || nodeDesc == wanted) {
                if (node.isClickable) return node
                clickableAncestor(node)?.let { return it }
            }
            for (i in 0 until node.childCount) {
                val found = find(node.getChild(i))
                if (found != null) return found
            }
            return null
        }

        return find(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun tapTextInternal(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val wanted = text.trim().lowercase()
        if (wanted.isBlank()) return false

        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser || !node.isEnabled) return null
            val label = (
                node.text?.toString().orEmpty() + " " +
                    node.contentDescription?.toString().orEmpty()
                ).trim().lowercase()

            if (label.contains(wanted)) {
                if (node.isClickable) return node
                clickableAncestor(node)?.let { return it }
            }

            for (i in 0 until node.childCount) {
                val found = find(node.getChild(i))
                if (found != null) return found
            }
            return null
        }

        return find(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun revealSearchEntryInternal(packageName: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        if (packageName != null && root.packageName?.toString() != packageName) return false
        // Bottom navigation and toolbars commonly label the entry "Search".
        val labels = listOf("Search", "Search tab", "Explore", "Find", "Discover")
        for (label in labels) {
            if (tapTextExactInternal(label)) return true
        }
        for (label in labels) {
            val node = findBestVisibleNode(label) ?: continue
            val target = if (node.isClickable) node else clickableAncestor(node) ?: continue
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    private fun isAudioPlayingInternal(): Boolean = runCatching {
        val audio = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return false
        audio.isMusicActive
    }.getOrDefault(false)

    private fun mediaKeyInternal(keyCode: Int): Boolean {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        }.getOrDefault(false)
    }

    private fun tapFirstResultInternal(query: String = ""): Boolean {
        val root = rootInActiveWindow ?: return false
        data class Candidate(val node: AccessibilityNodeInfo, val label: String, val score: Int)
        val candidates = mutableListOf<Candidate>()
        val ignored = setOf("back", "home", "search", "menu", "more options", "settings")

        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser || !node.isEnabled) return
            val actionable = node.isClickable || clickableAncestor(node) != null
            if (actionable) {
                val label = (
                    node.text?.toString().orEmpty() + " " +
                        node.contentDescription?.toString().orEmpty()
                    ).trim().replace(Regex("\\s+"), " ")
                val normalized = label.lowercase()
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (label.isNotBlank() && bounds.width() > 40 && bounds.height() > 30 && normalized !in ignored) {
                    val words = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
                    val score = when {
                        query.isNotBlank() && normalized == query.lowercase() -> 100
                        query.isNotBlank() && words.isNotEmpty() && words.all { normalized.contains(it) } -> 80
                        query.isNotBlank() && words.any { normalized.contains(it) } -> 50
                        bounds.top > 0 -> 10
                        else -> 0
                    }
                    candidates.add(Candidate(node, label, score))
                }
            }
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }

        collect(root)
        val candidate = candidates
            .sortedWith(
                compareByDescending<Candidate> { it.score }.thenBy {
                    Rect().also { r -> it.node.getBoundsInScreen(r) }.top
                }
            )
            .firstOrNull() ?: return false
        if (candidate.node.isClickable) {
            return candidate.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val ancestor = clickableAncestor(candidate.node)
        return ancestor?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun tapSendButtonInternal(packageName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (packageName.isNotBlank() && root.packageName?.toString() != packageName) return false

        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser || !node.isEnabled) return null
            val text = node.text?.toString().orEmpty().trim().lowercase()
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
            val resource = node.viewIdResourceName?.lowercase().orEmpty()

            val isSend = text == "send" ||
                desc == "send" ||
                desc.contains("send message") ||
                desc.contains("send") ||
                resource.contains(":id/send") ||
                resource.endsWith("/send") ||
                resource.endsWith("/send_button")

            if (isSend) {
                if (node.isClickable) return node
                clickableAncestor(node)?.let { return it }
            }
            for (i in 0 until node.childCount) {
                val found = find(node.getChild(i))
                if (found != null) return found
            }
            return null
        }

        val button = find(root) ?: return false
        if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        val bounds = Rect().also { button.getBoundsInScreen(it) }
        if (!bounds.isEmpty && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat(), 80L)
        }
        return false
    }

    /**
     * The layered submit route.
     *
     * Order: semantic/id/geometry resolution on a full screen snapshot, then the
     * keyboard's own action button, then a legacy id/label scan, then OCR over a
     * live screenshot. Each attempt is separated by a short delay so a Send
     * control that is still being created has time to appear.
     */
    private fun pressSendInternal(packageName: String, attempts: Int, callback: (Boolean) -> Unit) {
        if (attempts <= 0) {
            callback(false)
            return
        }

        if (packageName.isNotBlank() && rootInActiveWindow?.packageName?.toString() != packageName) {
            callback(false)
            return
        }
        val attemptOnce = {
            trySnapshotSend(packageName) ||
                tryImeActionSend() ||
                tapSendButtonInternal(packageName) ||
                tapLikelySubmitControlInternal(packageName)
        }

        if (attemptOnce()) {
            callback(true)
            return
        }

        // OCR is expensive, so it is only reached once the node tree has failed.
        val rootPackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        val target = packageName.ifBlank { rootPackage }
        if (target.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureAndTapTextVisually(target, "send") { visualOk ->
                if (visualOk) {
                    callback(true)
                } else {
                    mainHandler.postDelayed({
                        pressSendInternal(packageName, attempts - 1, callback)
                    }, 320L)
                }
            }
            return
        }

        mainHandler.postDelayed({
            pressSendInternal(packageName, attempts - 1, callback)
        }, 320L)
    }

    /** Resolve the submit control through the pure, unit-tested scorer. */
    private fun trySnapshotSend(packageName: String): Boolean {
        val model = screenModelInternal(500, packageName.takeIf { it.isNotBlank() })
        val screen = model.snapshot
        if (screen.nodes.isEmpty()) return false
        if (packageName.isNotBlank() && screen.packageName.isNotBlank() &&
            screen.packageName != packageName
        ) return false

        val composer = screen.focusedNode ?: screen.firstEditable
        val decision = EmaSendControlResolver.resolve(screen, composer) ?: return false

        android.util.Log.i(
            "EMA",
            "submit control resolved: index=${decision.nodeIndex} score=${decision.score} via=${decision.reason}"
        )

        // A semantic click is preferred; a gesture at the control's centre is the
        // fallback for custom-drawn views that ignore ACTION_CLICK.
        val node = model.nodes.getOrNull(decision.clickTargetIndex)
            ?: model.nodes.getOrNull(decision.nodeIndex)
            ?: return false
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        clickableAncestor(node)?.let { ancestor ->
            if (ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        val target = screen.nodeAt(decision.nodeIndex) ?: return false
        return dispatchTap(target.centerX.toFloat(), target.centerY.toFloat(), 80L)
    }

    /** Use the soft keyboard's own action button, which many chat apps honour. */
    private fun tryImeActionSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: findEditable(root)
            ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }.getOrDefault(false)
    }

    private fun tapLikelySubmitControlInternal(packageName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (packageName.isNotBlank() && root.packageName?.toString() != packageName) return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(root)
            ?: return false
        val fieldBounds = Rect().also { field.getBoundsInScreen(it) }
        if (fieldBounds.isEmpty) return false

        data class Candidate(val node: AccessibilityNodeInfo, val score: Int)
        val candidates = mutableListOf<Candidate>()
        val excluded = setOf(
            "emoji", "sticker", "gif", "attach", "attachment", "camera", "gallery",
            "microphone", "voice message", "plus"
        )

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser || !node.isEnabled) return
            val actionable = node.isClickable || clickableAncestor(node) != null
            if (actionable && !node.isEditable) {
                val label = (
                    node.text?.toString().orEmpty() + " " +
                        node.contentDescription?.toString().orEmpty() + " " +
                        node.viewIdResourceName.orEmpty()
                    ).trim().lowercase()
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (!bounds.isEmpty && bounds.width() >= 24 && bounds.height() >= 24) {
                    if (excluded.none { label.contains(it) }) {
                        var score = 0
                        if (label.contains("send") || label.contains("submit")) score += 100
                        val verticalOverlap = minOf(bounds.bottom, fieldBounds.bottom) - maxOf(bounds.top, fieldBounds.top)
                        val overlapsVertically = verticalOverlap > 0
                        if (bounds.left >= fieldBounds.right - 20 && overlapsVertically) score += 65
                        if (bounds.centerX() > fieldBounds.centerX() && overlapsVertically) score += 25
                        if (bounds.bottom >= fieldBounds.top && bounds.top <= fieldBounds.bottom + 40) score += 10
                        if (score > 0) candidates.add(Candidate(node, score))
                    }
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }

        walk(root)
        val candidate = candidates.maxByOrNull { it.score } ?: return false
        if (candidate.score < 60) return false
        if (candidate.node.isClickable &&
            candidate.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) return true
        clickableAncestor(candidate.node)?.let { ancestor ->
            if (ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        val bounds = Rect().also { candidate.node.getBoundsInScreen(it) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || bounds.isEmpty) return false
        return dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat(), 80L)
    }

    // ------------------------------------------------------------- gestures

    private fun dispatchTap(x: Float, y: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (x <= 0f || y <= 0f) return false
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun tapCoordinatesInternal(x: Float, y: Float): Boolean = dispatchTap(x, y, 50L)

    // ---------------------------------------------------------------- text

    private fun setFocusedTextInternal(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(root)
            ?: return false
        if (!field.isEditable) return false
        return commitText(field, text)
    }

    private fun focusedEditableTextInternal(): String {
        val root = rootInActiveWindow ?: return ""
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return ""
        return if (field.isEditable) field.text?.toString().orEmpty() else ""
    }

    private fun composerHasFocusInternal(): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return field.isEditable
    }

    private fun clickSearchControl(root: AccessibilityNodeInfo): Boolean {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val label = (
                node.text?.toString().orEmpty() + " " +
                    node.contentDescription?.toString().orEmpty() + " " +
                    node.viewIdResourceName.orEmpty()
                ).trim().lowercase()

            val looksLikeSearch = label == "search" ||
                label == "go" ||
                label == "submit" ||
                label == "enter" ||
                label.contains(":id/search") ||
                label.contains("search_button") ||
                label.contains("search_voice") ||
                label.startsWith("search ")

            if (looksLikeSearch && node.isVisibleToUser) {
                if (node.isClickable) return node
                clickableAncestor(node)?.let { return it }
            }

            for (i in 0 until node.childCount) {
                val found = find(node.getChild(i))
                if (found != null) return found
            }
            return null
        }

        return find(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun scrollInternal(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findScrollable(root) ?: root
        val action = when (direction.lowercase()) {
            "up", "back", "top" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        return target.performAction(action)
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let(::findScrollable)
            if (found != null) return found
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let(::findEditable)
            if (found != null) return found
        }
        return null
    }

    private fun findSearchNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = (
            node.text?.toString().orEmpty() + " " +
                node.contentDescription?.toString().orEmpty() + " " +
                node.viewIdResourceName.orEmpty()
            ).lowercase()

        if (text.contains("search") && node.isVisibleToUser && (node.isFocusable || node.isClickable)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let(::findSearchNode)
            if (found != null) return found
        }
        return null
    }

    // --------------------------------------------------------------- vision

    private fun obtainVisualBitmap(callback: (Bitmap?) -> Unit) {
        EmaVoiceInteractionSession.withAssistantUiHidden {
            captureDisplayScreenshotInternal { live ->
                if (live != null) callback(live)
                else {
                    val copy = EmaVoiceInteractionSession.currentScreenScreenshotCopy()
                    callback(copy)
                }
            }
        }
    }

    private fun setAssistantScreenshotInternal(bitmap: Bitmap?) {
        assistantScreenshot?.recycle()
        assistantScreenshot = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun captureDisplayScreenshotInternal(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    }.getOrNull()
                    runCatching { screenshot.hardwareBuffer.close() }
                    callback(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    android.util.Log.w("EMA", "screenshot capture failed, code=$errorCode")
                    callback(null)
                }
            })
        }.onFailure {
            android.util.Log.e("EMA", "screenshot capture could not be started", it)
            callback(null)
        }
    }

    private fun captureAndTapTextVisuallyWithRetry(
        packageName: String,
        target: String,
        attempts: Int,
        callback: (Boolean) -> Unit
    ) {
        captureAndTapTextVisually(packageName, target) { ok ->
            if (ok || attempts <= 1) {
                callback(ok)
            } else {
                mainHandler.postDelayed({
                    captureAndTapTextVisuallyWithRetry(packageName, target, attempts - 1, callback)
                }, 350L)
            }
        }
    }

    private fun captureAndTapTextVisually(
        packageName: String,
        target: String,
        callback: (Boolean) -> Unit
    ) {
        val root = rootInActiveWindow
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(false)
            return
        }
        if (packageName.isNotBlank() && root?.packageName?.toString() != packageName) {
            callback(false)
            return
        }

        EmaVoiceInteractionSession.withAssistantUiHidden {
            captureDisplayScreenshotInternal { bitmap ->
                if (bitmap == null) {
                    callback(false)
                    return@captureDisplayScreenshotInternal
                }

                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        val wanted = target.trim().lowercase()
                        val match = result.textBlocks
                            .flatMap { it.lines }
                            .flatMap { it.elements }
                            .firstOrNull {
                                val text = it.text.trim().lowercase()
                                text == wanted || text.contains(wanted)
                            }

                        val activated = if (match?.boundingBox == null) {
                            false
                        } else {
                            val box = match.boundingBox!!
                            dispatchTap(box.centerX().toFloat(), box.centerY().toFloat(), 90L)
                        }
                        runCatching { recognizer.close() }
                        bitmap.recycle()
                        runCatching { callback(activated) }
                            .onFailure { android.util.Log.e("EMA", "Visual tap callback failed", it) }
                    }
                    .addOnFailureListener {
                        runCatching { recognizer.close() }
                        bitmap.recycle()
                        runCatching { callback(false) }
                            .onFailure { android.util.Log.e("EMA", "Visual tap failure callback failed", it) }
                    }
            }
        }
    }

    private fun captureAndLabel(callback: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback("")
            return
        }
        obtainVisualBitmap { bitmap ->
            if (bitmap == null) { callback(""); return@obtainVisualBitmap }
            val labeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder().setConfidenceThreshold(0.35f).build()
            )
            labeler.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { labels ->
                    val value = labels.take(20).joinToString(", ") { it.text }
                    runCatching { labeler.close() }
                    bitmap.recycle()
                    runCatching { callback(value) }
                        .onFailure { android.util.Log.e("EMA", "Image-label callback failed", it) }
                }
                .addOnFailureListener {
                    runCatching { labeler.close() }
                    bitmap.recycle()
                    runCatching { callback("") }
                        .onFailure { android.util.Log.e("EMA", "Image-label failure callback failed", it) }
                }
        }
    }

    private fun captureAndRecognize(callback: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback("")
            return
        }
        obtainVisualBitmap { bitmap ->
            if (bitmap == null) {
                callback("")
                return@obtainVisualBitmap
            }
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val value = result.text
                    runCatching { recognizer.close() }
                    bitmap.recycle()
                    runCatching { callback(value) }
                        .onFailure { android.util.Log.e("EMA", "OCR callback failed", it) }
                }
                .addOnFailureListener {
                    runCatching { recognizer.close() }
                    bitmap.recycle()
                    runCatching { callback("") }
                        .onFailure { android.util.Log.e("EMA", "OCR failure callback failed", it) }
                }
        }
    }
}
