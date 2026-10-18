package com.ema.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class EmaNotificationListenerService : NotificationListenerService() {
    companion object {
        @Volatile var latestNotifications: List<String> = emptyList()
        @Volatile private var service: EmaNotificationListenerService? = null

        fun requestedMediaPlaying(context: android.content.Context, packageName: String, query: String): Boolean {
            if (service == null) return false
            return runCatching {
                val manager = context.getSystemService(android.media.session.MediaSessionManager::class.java)
                val component = android.content.ComponentName(context, EmaNotificationListenerService::class.java)
                manager.getActiveSessions(component).any { controller ->
                    val metadata = controller.metadata
                    EmaActionEvidence.requestedMediaPlaying(packageName, controller.packageName, query,
                        metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                        metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                        controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING)
                }
            }.getOrDefault(false)
        }

        /** What the app's media session reports, or null when not observable (no notification access, no session). */
        data class MediaState(val packageName: String, val title: String, val playing: Boolean)

        fun activeMedia(context: android.content.Context, packageName: String): MediaState? {
            if (service == null) return null
            return runCatching {
                val manager = context.getSystemService(android.media.session.MediaSessionManager::class.java)
                val component = android.content.ComponentName(context, EmaNotificationListenerService::class.java)
                manager.getActiveSessions(component).filter { it.packageName == packageName }.map { controller ->
                    MediaState(
                        controller.packageName,
                        controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                        controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                    )
                }.sortedByDescending { it.playing }.firstOrNull()
            }.getOrNull()
        }

        /**
         * Playing state of the app's media session, or null when it cannot be
         * observed. Never guess: pressing Play while already playing toggles
         * pause, so callers must distinguish "not playing" from "unknown".
         */
        fun mediaPlaying(context: android.content.Context, packageName: String): Boolean? =
            activeMedia(context, packageName)?.playing

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
