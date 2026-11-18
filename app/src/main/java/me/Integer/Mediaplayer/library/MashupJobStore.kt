package me.Integer.Mediaplayer.library

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException

/**
 * Remembers the server's analysis job while it runs, so a job interrupted by the app being
 * killed can be followed again on the next launch instead of starting over.
 * Safe to call from any thread.
 */
internal class MashupJobStore(context: Context) {

    /** A job on [serverUrl] analysing the songs of [trackUris]. */
    data class Pending(val jobId: String, val serverUrl: String, val trackUris: List<String>)

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(pending: Pending) {
        if (!isJobId(pending.jobId) || pending.serverUrl.isEmpty() || pending.trackUris.isEmpty()) return
        val uris = JSONArray()
        for (uri in pending.trackUris.take(MAX_TRACKS)) uris.put(uri)
        prefs.edit()
            .putString(KEY_JOB, pending.jobId)
            .putString(KEY_URL, pending.serverUrl)
            .putString(KEY_TRACKS, uris.toString())
            .apply()
    }

    /** The remembered job, or null when there is none or the record is unusable. */
    fun load(): Pending? {
        val jobId = prefs.getString(KEY_JOB, null) ?: return null
        val url = prefs.getString(KEY_URL, null).orEmpty()
        val uris = readUris(prefs.getString(KEY_TRACKS, null))
        if (!isJobId(jobId) || url.isEmpty() || uris.isEmpty()) {
            clear()
            return null
        }
        return Pending(jobId, url, uris)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun readUris(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            List(minOf(array.length(), MAX_TRACKS)) { array.optString(it) }.filter { it.isNotEmpty() }
        } catch (e: JSONException) {
            Log.w(TAG, "dropping an unreadable pending job: ${e.message}")
            emptyList()
        }
    }

    private fun isJobId(id: String): Boolean {
        return id.isNotEmpty() && id.length <= MAX_JOB_ID_CHARS && id.all { it in '0'..'9' || it in 'a'..'z' }
    }

    private companion object {
        const val TAG = "MashupJobStore"
        const val FILE = "mashup_job"
        const val KEY_JOB = "job_id"
        const val KEY_URL = "server_url"
        const val KEY_TRACKS = "track_uris"
        const val MAX_TRACKS = 200
        const val MAX_JOB_ID_CHARS = 64
    }
}
