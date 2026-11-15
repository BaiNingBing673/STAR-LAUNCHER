package com.star.launcher.lyrics.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Minimal NotificationListenerService required solely so Android's [android.media.session.MediaSessionManager]
 * can observe active external media sessions.
 *
 * Privacy guarantee:
 * - Never reads, logs, stores, or transmits any notification text, metadata, or packages.
 * - [onNotificationPosted] and [onNotificationRemoved] are strict no-ops.
 */
class StarMediaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Intentionally ignored: STAR Live Lyrics never collects or inspects notifications.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally ignored.
    }

    companion object {
        @JvmStatic
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, StarMediaNotificationListener::class.java)

        @JvmStatic
        fun hasNotificationAccess(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.applicationContext.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val target = componentName(context).flattenToString()
            val shortTarget = componentName(context).flattenToShortString()
            return flat.split(':').any { entry ->
                val trimmed = entry.trim()
                trimmed == target || trimmed == shortTarget
            }
        }

        @JvmStatic
        fun createSettingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
