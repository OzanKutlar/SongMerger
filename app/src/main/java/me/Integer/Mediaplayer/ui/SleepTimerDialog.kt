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

    fun show(context: Context, connection: PlayerConnection) {
        val remaining = connection.sleepTimerRemainingMs()
        val activeLabel = when {
            remaining == -1L -> context.getString(R.string.sleep_status_end_of_track)
            remaining > 0L -> {
                val mins = ((remaining + 59_999L) / 60_000L).toInt()
                context.getString(R.string.sleep_status_minutes, mins)
            }
            else -> ""
        }
        val options = ArrayList<String>().apply {
            for (m in MINUTES) add(context.getString(R.string.sleep_option_minutes, m))
            add(context.getString(R.string.sleep_option_end_of_track))
            if (remaining != 0L) add(context.getString(R.string.sleep_option_off))
        }
        val title = if (activeLabel.isEmpty()) {
            context.getString(R.string.menu_sleep_timer)
        } else {
            "${context.getString(R.string.menu_sleep_timer)} ($activeLabel)"
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which in MINUTES.indices -> {
                        val mins = MINUTES[which]
                        connection.setSleepTimer(mins, endOfTrack = false)
                        Toast.makeText(
                            context,
                            context.getString(R.string.sleep_toast_set, mins),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    which == MINUTES.size -> {
                        connection.setSleepTimer(0, endOfTrack = true)
                        Toast.makeText(
                            context,
                            context.getString(R.string.sleep_toast_end_of_track),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> {
                        connection.setSleepTimer(0, endOfTrack = false)
                        Toast.makeText(
                            context,
                            context.getString(R.string.sleep_toast_off),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
