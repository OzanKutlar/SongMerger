package me.Integer.Mediaplayer.library

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adds one new song to the library without a rescan. Other apps, such as a Termux download
 * script, send the song's file name and the app looks for it in the folders it already reads:
 *
 *     am broadcast -n me.Integer.Mediaplayer/.library.AddTrackReceiver -a me.Integer.Mediaplayer.ADD_TRACK --es name "Artist - Title.mp3"
 *
 * The broadcast result tells the sender what happened: "added: ...", "updated: ...",
 * "not found in the music folders: ...", "no music folders set in Media Player",
 * "invalid file name", or "queued: ..." when the library was too busy to answer in time.
 */
class AddTrackReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADD_TRACK) return
        val ordered = isOrderedBroadcast
        val name = cleanName(intent.getStringExtra(EXTRA_NAME))
        if (name == null) {
            Log.w(TAG, "rejected an unusable file name")
            if (ordered) setResult(RESULT_FAILED, MESSAGE_INVALID, null)
            return
        }
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        val timeout = Runnable { finish(pending, finished, ordered, Activity.RESULT_OK, "queued: $name") }
        main.postDelayed(timeout, TIMEOUT_MS)
        try {
            LibraryRepository.get(context).importByName(name) { outcome ->
                main.removeCallbacks(timeout)
                finish(pending, finished, ordered, codeOf(outcome), messageOf(outcome, name))
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot import $name", e)
            main.removeCallbacks(timeout)
            finish(pending, finished, ordered, RESULT_FAILED, "failed: $name")
        }
    }

    /** Reports once and lets the system go; a late second report is ignored. */
    private fun finish(
        pending: PendingResult,
        finished: AtomicBoolean,
        ordered: Boolean,
        code: Int,
        message: String
    ) {
        if (!finished.compareAndSet(false, true)) return
        Log.i(TAG, message)
        try {
            if (ordered) pending.setResult(code, message, null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot report the result: ${e.message}")
        } finally {
            pending.finish()
        }
    }

    private fun codeOf(outcome: LibraryRepository.ImportOutcome): Int {
        return when (outcome) {
            LibraryRepository.ImportOutcome.ADDED, LibraryRepository.ImportOutcome.UPDATED -> Activity.RESULT_OK
            else -> RESULT_FAILED
        }
    }

    private fun messageOf(outcome: LibraryRepository.ImportOutcome, name: String): String {
        return when (outcome) {
            LibraryRepository.ImportOutcome.ADDED -> "added: $name"
            LibraryRepository.ImportOutcome.UPDATED -> "updated: $name"
            LibraryRepository.ImportOutcome.NOT_FOUND -> "not found in the music folders: $name"
            LibraryRepository.ImportOutcome.NO_FOLDERS -> "no music folders set in Media Player"
        }
    }

    /** A plain file name, or null for anything that is empty, too long or a path. */
    private fun cleanName(raw: String?): String? {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return null
        if (name == "." || name == "..") return null
        if (name.any { it == '/' || it == '\\' || it.code < FIRST_PRINTABLE }) return null
        return name
    }

    companion object {
        const val ACTION_ADD_TRACK = "me.Integer.Mediaplayer.ADD_TRACK"
        const val EXTRA_NAME = "name"

        private const val TAG = "AddTrackReceiver"
        private const val RESULT_FAILED = 1
        private const val MESSAGE_INVALID = "invalid file name"
        private const val MAX_NAME_LENGTH = 255
        private const val FIRST_PRINTABLE = 32

        /** Well inside the time Android gives a broadcast receiver. */
        private const val TIMEOUT_MS = 8000L
    }
}
