package com.ema.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class EmaNotificationListenerService : NotificationListenerService() {
    companion object {
        @Volatile var latestNotifications: List<String> = emptyList()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val title = sbn.notification.extras?.getCharSequence("android.title")?.toString().orEmpty()
        val text = sbn.notification.extras?.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isNotBlank() || text.isNotBlank()) {
            latestNotifications = (listOf(title to text) + latestNotifications.map { it to "" })
                .take(50)
                .map { pair -> listOf(pair.first, pair.second).filter { it.isNotBlank() }.joinToString(": ") }
        }
    }
}
