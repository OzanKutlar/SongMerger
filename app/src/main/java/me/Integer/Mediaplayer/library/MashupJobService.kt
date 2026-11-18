package me.Integer.Mediaplayer.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.Integer.Mediaplayer.MainActivity
import me.Integer.Mediaplayer.R

/**
 * Keeps the app process alive while an AI mashup job uploads and analyses songs, which can
 * take many minutes, and shows its progress in a notification. It does no work itself:
 * [AiMashupGenerator] runs the job, and this service stops as soon as the job is not running.
 * Main thread only.
 */
class MashupJobService : Service() {

    private var listening = false
    private val listener = AiMashupGenerator.Listener { state -> onStateChanged(state) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val generator = AiMashupGenerator.get(this)
        // A service started with startForegroundService must promote itself, even to stop at once.
        if (!promote(notificationFor(generator.state))) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!generator.state.isRunning) {
            finish()
            return START_NOT_STICKY
        }
        if (!listening) {
            generator.addListener(listener)
            listening = true
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (listening) AiMashupGenerator.get(this).removeListener(listener)
        listening = false
        super.onDestroy()
    }

    private fun onStateChanged(state: AiMashupGenerator.State) {
        if (!state.isRunning) {
            finish()
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, notificationFor(state))
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot update the mashup notification: ${e.message}")
        }
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promote(notification: Notification): Boolean {
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot run the mashup job in the foreground: ${e.message}")
            false
        }
    }

    private fun notificationFor(state: AiMashupGenerator.State): Notification {
        ensureChannel()
        val counted = state.total > 0 &&
            (state.phase == AiMashupGenerator.Phase.UPLOADING || state.phase == AiMashupGenerator.Phase.ANALYSING)
        val max = if (counted) state.total else 0
        val progress = if (counted) state.done.coerceIn(0, state.total) else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.mashup_title))
            .setContentText(textFor(state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(max, progress, !counted)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun textFor(state: AiMashupGenerator.State): String {
        return when (state.phase) {
            AiMashupGenerator.Phase.UPLOADING -> getString(R.string.mashup_uploading, state.done, state.total)
            AiMashupGenerator.Phase.ANALYSING -> getString(R.string.mashup_analysing, state.done, state.total)
            AiMashupGenerator.Phase.ROUTING -> getString(R.string.mashup_routing)
            else -> getString(R.string.mashup_connecting)
        }
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, OPEN_APP_REQUEST, intent, flags)
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mashup_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MashupJobService"
        private const val CHANNEL_ID = "mashup_jobs"
        private const val NOTIFICATION_ID = 7201
        private const val OPEN_APP_REQUEST = 2

        /** Asks for the service; a refusal is logged and the job simply runs without it. */
        fun start(context: Context) {
            val app = context.applicationContext
            try {
                ContextCompat.startForegroundService(app, Intent(app, MashupJobService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "cannot start the mashup job service: ${e.message}")
            }
        }
    }
}
