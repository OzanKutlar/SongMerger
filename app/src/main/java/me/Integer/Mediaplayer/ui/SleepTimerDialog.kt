package me.Integer.Mediaplayer.ui

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.playback.PlayerConnection

/**
 * Lets the user set or cancel a sleep timer with a 5-second DJ fade-out.
 */
object SleepTimerDialog {

    private val MINUTES = intArrayOf(15, 30, 45, 60)

    /** [onChanged] runs after a choice was sent, so the caller can show the new state at once. */
    fun show(context: Context, connection: PlayerConnection, onChanged: () -> Unit = {}) {
        val remaining = connection.sleepTimerRemainingMs()
        val options = ArrayList<String>().apply {
            for (m in MINUTES) add(context.getString(R.string.sleep_option_minutes, m))
            add(context.getString(R.string.sleep_option_end_of_track))
            if (remaining != 0L) add(context.getString(R.string.sleep_option_off))
        }
        AlertDialog.Builder(context)
            .setTitle(titleOf(context, remaining))
            .setItems(options.toTypedArray()) { _, which ->
                choose(context, connection, which)
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun titleOf(context: Context, remaining: Long): String {
        val base = context.getString(R.string.menu_sleep_timer)
        val active = when {
            remaining == -1L -> context.getString(R.string.sleep_status_end_of_track)
            remaining > 0L -> context.getString(R.string.sleep_status_minutes, ((remaining + 59_999L) / 60_000L).toInt())
            else -> return base
        }
        return "$base ($active)"
    }

    private fun choose(context: Context, connection: PlayerConnection, which: Int) {
        val message = when {
            which in MINUTES.indices -> {
                connection.setSleepTimer(MINUTES[which], endOfTrack = false)
                context.getString(R.string.sleep_toast_set, MINUTES[which])
            }
            which == MINUTES.size -> {
                connection.setSleepTimer(0, endOfTrack = true)
                context.getString(R.string.sleep_toast_end_of_track)
            }
            else -> {
                connection.setSleepTimer(0, endOfTrack = false)
                context.getString(R.string.sleep_toast_off)
            }
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
