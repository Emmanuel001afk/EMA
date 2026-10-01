package com.ema.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.view.KeyEvent
import android.os.Build
import android.os.Looper
import android.os.Handler
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference

class EmaAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var assistantScreenshot: Bitmap? = null
    companion object {
        @Volatile private var instance: EmaAccessibilityService? = null
        private val pendingSearch = AtomicReference<PendingSearch?>(null)

        private data class PendingSearch(
            val packageName: String?,
            val query: String,
            val callback: (Boolean) -> Unit,
            val attempts: Int = 0
        )

        fun requestTextSearch(query: String) {
            pendingSearch.set(PendingSearch(null, query, {}))
            instance?.performPendingSearch()
        }

        fun searchInPackage(packageName: String, query: String, callback: (Boolean) -> Unit) {
            pendingSearch.set(PendingSearch(packageName, query, callback))
            instance?.performPendingSearch()
        }

        fun isConnected(): Boolean = instance != null

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

        fun currentScreenText(maxChars: Int = 12000): String {
            return instance?.readCurrentWindow(maxChars).orEmpty()
        }

        fun performGlobalAction(action: Int): Boolean =
            instance?.performGlobalAction(action) == true

        fun tapText(text: String): Boolean =
            instance?.tapTextInternal(text) == true

        fun tapTextExact(text: String): Boolean =
            instance?.tapTextExactInternal(text) == true

        fun setFocusedText(text: String): Boolean =
            instance?.setFocusedTextInternal(text) == true

        fun focusedEditableText(): String = instance?.focusedEditableTextInternal().orEmpty()

        fun scroll(direction: String): Boolean =
            instance?.scrollInternal(direction) == true

        /** Generic UI inspection for the currently visible application. */
        fun inspectCurrentUi(maxChars: Int = 16000): String =
            instance?.inspectCurrentUiInternal(maxChars).orEmpty()

        /** Generic control operation: finds the best accessible visible match. */
        fun activate(text: String): Boolean =
            instance?.activateTextInternal(text) == true

        /**
         * Generic action route: Accessibility semantics first, then visual OCR
         * coordinates when the visible control is missing from the node tree.
         */
        fun activateWithVisualFallback(
            text: String,
            callback: (Boolean) -> Unit
        ) {
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

        fun longPressText(text: String): Boolean =
            instance?.longPressTextInternal(text) == true

        fun pressBack(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_BACK) == true

        fun pressHome(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_HOME) == true

        fun tapCoordinates(x: Float, y: Float): Boolean =
            instance?.tapCoordinatesInternal(x, y) == true

        fun tapFirstResult(query: String = ""): Boolean =
            instance?.tapFirstResultInternal(query) == true

        fun tapSendButton(packageName: String): Boolean =
            instance?.tapSendButtonInternal(packageName) == true

        /**
         * Try the semantic Accessibility route first. If the target app does
         * not expose its Send control, fall back to OCR over the current
         * screenshot and tap the visual location of the Send label.
         */
        fun tapSendButtonWithVisualFallback(
            packageName: String,
            callback: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null) {
                callback(false)
                return
            }
            if (service.tapSendButtonInternal(packageName) ||
                service.tapLikelySubmitControlInternal(packageName)) {
                callback(true)
                return
            }
            service.captureAndTapTextVisually(
                packageName = packageName,
                target = "send",
                callback = callback
            )
        }

        fun toggleMediaPlayback(): Boolean =
            instance?.toggleMediaPlaybackInternal() == true

        fun describeScreenImage(callback: (String) -> Unit) {
            instance?.captureAndLabel(callback) ?: callback("")
        }

        fun readScreenWithOcr(callback: (String) -> Unit) {
            instance?.captureAndRecognize(callback) ?: callback("")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        performPendingSearch()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        performPendingSearch()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        pendingSearch.getAndSet(null)?.callback?.invoke(false)
        if (instance === this) instance = null
        super.onDestroy()
    }

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
                    .append(" | bounds=").append(bounds)
                    .append('\n')
            }

            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        return out.toString().trim().take(maxChars)
    }

    private fun activateTextInternal(text: String): Boolean {
        val node = findBestVisibleNode(text) ?: return false
        return when {
            node.isClickable -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.isFocusable -> node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            else -> node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
        }
    }

    private fun longPressTextInternal(text: String): Boolean {
        val node = findBestVisibleNode(text) ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return false
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 900))
            .build()
        return dispatchGesture(gesture, null, null)
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
            } else if (contains == null &&
                (text.contains(wanted) || desc.contains(wanted))) {
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

        fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var parent = node.parent
            while (parent != null) {
                if (parent.isVisibleToUser && parent.isEnabled && parent.isClickable) return parent
                parent = parent.parent
            }
            return null
        }

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

        fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var parent = node.parent
            while (parent != null) {
                if (parent.isVisibleToUser && parent.isEnabled && parent.isClickable) return parent
                parent = parent.parent
            }
            return null
        }

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

    private fun toggleMediaPlaybackInternal(): Boolean {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return false
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        audioManager.dispatchMediaKeyEvent(down)
        audioManager.dispatchMediaKeyEvent(up)
        return true
    }

    private fun tapFirstResultInternal(query: String = ""): Boolean {
        val root = rootInActiveWindow ?: return false
        data class Candidate(val node: AccessibilityNodeInfo, val label: String, val score: Int)
        val candidates = mutableListOf<Candidate>()
        val ignored = setOf("back", "home", "search", "menu", "more options", "settings")

        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser || !node.isEnabled) return
            if (node.isClickable) {
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
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { 
                Rect().also { r -> it.node.getBoundsInScreen(r) }.top
            })
            .firstOrNull() ?: return false
        return candidate.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun tapSendButtonInternal(packageName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != packageName) return false

        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser || !node.isEnabled) return null
            val text = node.text?.toString().orEmpty().trim().lowercase()
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
            val resource = node.viewIdResourceName?.lowercase().orEmpty()
            val label = "$text $desc $resource"

            val isSend = text == "send" ||
                desc == "send" ||
                desc.contains("send message") ||
                desc.contains("send") ||
                resource.contains(":id/send") ||
                resource.endsWith("/send") ||
                resource.endsWith("/send_button")

            if (isSend && node.isClickable) return node
            for (i in 0 until node.childCount) {
                val found = find(node.getChild(i))
                if (found != null) return found
            }
            return null
        }

        val button = find(root) ?: return false
        if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val bounds = Rect().also { button.getBoundsInScreen(it) }
            if (!bounds.isEmpty) {
                val x = bounds.centerX().toFloat()
                val y = bounds.centerY().toFloat()
                val path = android.graphics.Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                    .build()
                return dispatchGesture(gesture, null, null)
            }
        }
        return false
    }

    private fun tapLikelySubmitControlInternal(packageName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != packageName) return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(root)
            ?: return false
        val fieldBounds = Rect().also { field.getBoundsInScreen(it) }
        if (fieldBounds.isEmpty) return false

        data class Candidate(val node: AccessibilityNodeInfo, val score: Int)
        val candidates = mutableListOf<Candidate>()
        val excluded = setOf("emoji", "sticker", "gif", "attach", "attachment", "camera", "gallery", "microphone", "voice message", "plus")

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser || !node.isEnabled) return
            if (node.isClickable) {
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
        return candidate.node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
            run {
                val bounds = Rect().also { candidate.node.getBoundsInScreen(it) }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || bounds.isEmpty) false
                else {
                    val path = android.graphics.Path().apply {
                        moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    }
                    dispatchGesture(
                        GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                            .build(),
                        null,
                        null
                    )
                }
            }
    }

    private fun obtainVisualBitmap(callback: (Bitmap?) -> Unit) {
        EmaVoiceInteractionSession.withAssistantUiHidden {
            captureDisplayScreenshotInternal { live ->
                if (live != null) callback(live)
                else EmaVoiceInteractionSession.currentScreenScreenshotCopy()?.let { callback(it) } ?: callback(null)
            }
        }
    }

    private fun setFocusedTextInternal(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(root)
            ?: return false

        if (!field.isEditable) return false

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }

        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun focusedEditableTextInternal(): String {
        val root = rootInActiveWindow ?: return ""
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return ""
        return if (field.isEditable) field.text?.toString().orEmpty() else ""
    }

    private fun performPendingSearch() {
        val pending = pendingSearch.get() ?: return
        val root = rootInActiveWindow ?: return

        if (pending.packageName != null && root.packageName?.toString() != pending.packageName) {
            return
        }

        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(root)
            ?: findSearchNode(root)

        if (field == null || !field.isEditable) {
            // Many apps (including mail/messaging apps) hide the search field
            // behind a Search icon/button. Open that control first, then let the
            // resulting accessibility event retry the same generic search.
            val opened = clickSearchControl(root)
            if (opened && pending.attempts < 6) {
                val retry = pending.copy(attempts = pending.attempts + 1)
                pendingSearch.set(retry)
                mainHandler.postDelayed({ performPendingSearch() }, 350L)
            }
            return
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                pending.query
            )
        }

        if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            clickSearchControl(root)
            pendingSearch.compareAndSet(pending, null)
            pending.callback(true)
        }
    }

    private fun clickSearchControl(root: AccessibilityNodeInfo): Boolean {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val label = (
                node.text?.toString().orEmpty() + " " +
                    node.contentDescription?.toString().orEmpty()
            ).trim().lowercase()

            val looksLikeSearch = label == "search" ||
                label == "go" ||
                label == "submit" ||
                label == "enter" ||
                label.contains("search")

            if (looksLikeSearch && node.isVisibleToUser && node.isClickable) return node

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

    private fun tapCoordinatesInternal(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Visual action fallback for controls that are visible in the screenshot
     * but omitted from the Accessibility node tree. ML Kit OCR supplies the
     * text bounds, then EMA dispatches a normal accessibility gesture to that
     * visual location.
     */
    private fun setAssistantScreenshotInternal(bitmap: Bitmap?) {
        assistantScreenshot?.recycle()
        assistantScreenshot = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun captureDisplayScreenshotInternal(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = runCatching {
                    Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                }.getOrNull()
                screenshot.hardwareBuffer.close()
                callback(bitmap)
            }

            override fun onFailure(errorCode: Int) {
                callback(null)
            }
        })
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
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
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
        if (root?.packageName?.toString() != packageName || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
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
                            val path = android.graphics.Path().apply {
                                moveTo(box.centerX().toFloat(), box.centerY().toFloat())
                            }
                            val gesture = GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(path, 0, 90))
                                .build()
                            dispatchGesture(gesture, null, null)
                        }
                        recognizer.close()
                        bitmap.recycle()
                        runCatching { callback(activated) }
                            .onFailure { android.util.Log.e("EMA", "Visual tap callback failed", it) }
                    }
                    .addOnFailureListener {
                        recognizer.close()
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
                    labeler.close()
                    bitmap.recycle()
                    runCatching { callback(value) }
                        .onFailure { android.util.Log.e("EMA", "Image-label callback failed", it) }
                }
                .addOnFailureListener {
                    labeler.close()
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
                    recognizer.close()
                    bitmap.recycle()
                    runCatching { callback(value) }
                        .onFailure { android.util.Log.e("EMA", "OCR callback failed", it) }
                }
                .addOnFailureListener {
                    recognizer.close()
                    bitmap.recycle()
                    runCatching { callback("") }
                        .onFailure { android.util.Log.e("EMA", "OCR failure callback failed", it) }
                }
        }
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
                node.contentDescription?.toString().orEmpty()
        ).lowercase()

        if (text.contains("search") && node.isFocusable && node.isVisibleToUser) {
            return node
        }

        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let(::findSearchNode)
            if (found != null) return found
        }

        return null
    }
}
