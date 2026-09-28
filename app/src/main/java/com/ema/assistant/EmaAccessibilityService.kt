package com.ema.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference

class EmaAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: EmaAccessibilityService? = null
        private val pendingSearch = AtomicReference<String?>(null)

        fun requestTextSearch(query: String) {
            pendingSearch.set(query)
            instance?.performPendingSearch()
        }

        fun isConnected(): Boolean = instance != null

        fun currentScreenText(maxChars: Int = 12000): String {
            return instance?.readCurrentWindow(maxChars).orEmpty()
        }

        fun performGlobalAction(action: Int): Boolean =
            instance?.performGlobalAction(action) == true

        fun tapText(text: String): Boolean = instance?.tapTextInternal(text) == true

        fun setFocusedText(text: String): Boolean = instance?.setFocusedTextInternal(text) == true
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
                if (normalized.length <= 500) {
                    out.append(normalized).append('\\n')
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
                if (out.length >= maxChars) return
            }
        }
        walk(root)
        return out.toString().trim().take(maxChars)
    }

    private fun tapTextInternal(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val wanted = text.trim().lowercase()
        if (wanted.isBlank()) return false
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val label = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
                .trim().lowercase()
            if (label.contains(wanted) && node.isVisibleToUser && node.isClickable) return node
            for (i in 0 until node.childCount) {
                val found = find(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        return find(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun setFocusedTextInternal(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        if (!field.isEditable) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun performPendingSearch() {
        val query = pendingSearch.get() ?: return
        val root = rootInActiveWindow ?: return

        val fields = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(root)
            ?: findSearchNode(root)

        if (fields != null && fields.isEditable) {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    query
                )
            }
            if (fields.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                fields.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                pendingSearch.compareAndSet(query, null)
                return
            }
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { child ->
                findEditable(child)
            }
            if (found != null) return found
        }
        return null
    }

    private fun findSearchNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = (node.text ?: node.contentDescription ?: "").toString().lowercase()
        if (text.contains("search") && node.isFocusable) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { child -> findSearchNode(child) }
            if (found != null) return found
        }
        return null
    }
}
