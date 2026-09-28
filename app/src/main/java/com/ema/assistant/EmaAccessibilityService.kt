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
