package me.Integer.Mediaplayer.playback

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService

/**
 * Required by Android's [MediaSessionManager.getActiveSessions] so the playlist importer
 * can observe another music app's active session and skip through its queue.
 */
class MediaCaptureService : NotificationListenerService() {

    companion object {
        fun component(context: Context): ComponentName {
            return ComponentName(context.applicationContext, MediaCaptureService::class.java)
        }

        /** True when the user has granted Notification Access to this app. */
        fun hasAccess(context: Context): Boolean {
            val app = context.applicationContext
            val manager = app.getSystemService(MediaSessionManager::class.java) ?: return false
            return try {
                manager.getActiveSessions(component(app))
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
