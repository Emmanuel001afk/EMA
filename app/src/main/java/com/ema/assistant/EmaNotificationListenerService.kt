package com.ema.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class EmaNotificationListenerService : NotificationListenerService() {
    companion object {
        @Volatile var latestNotifications: List<String> = emptyList()
        @Volatile private var service: EmaNotificationListenerService? = null

        fun isConnected(): Boolean = service != null

        fun describeActiveNotifications(): String {
            val activeService = service
            if (activeService == null) return "EMA notification access is not connected. Enable Notification access for EMA first."
            val current = activeService.getActiveNotifications().orEmpty()
            if (current.isEmpty()) return "Notification access is connected, but there are no active notifications."
            return current.take(20).joinToString("\n") { sbn ->
                val title = sbn.notification.extras?.getCharSequence("android.title")?.toString().orEmpty()
                val text = sbn.notification.extras?.getCharSequence("android.text")?.toString().orEmpty()
                val app = sbn.packageName.substringAfterLast('.')
                listOf(app, title, text).filter { it.isNotBlank() }.joinToString(": ")
            }
        }

        fun dismissMatching(query: String): Boolean {
            val target = query.trim().lowercase()
            val activeService = service ?: return false
            val current = activeService.getActiveNotifications().orEmpty()
            val match = current.firstOrNull { sbn ->
                val title = sbn.notification.extras?.getCharSequence("android.title")?.toString().orEmpty()
                val text = sbn.notification.extras?.getCharSequence("android.text")?.toString().orEmpty()
                (title + " " + text + " " + sbn.packageName).lowercase().contains(target)
            } ?: return false
            activeService.cancelNotification(match.key)
            return true
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        service = this
        refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        refresh()
    }

    override fun onListenerDisconnected() {
        if (service === this) service = null
        super.onListenerDisconnected()
    }

    private fun refresh() {
        latestNotifications = getActiveNotifications().take(50).map { sbn ->
            val title = sbn.notification.extras?.getCharSequence("android.title")?.toString().orEmpty()
            val text = sbn.notification.extras?.getCharSequence("android.text")?.toString().orEmpty()
            listOf(title, text).filter { it.isNotBlank() }.joinToString(": ")
        }
    }
}
