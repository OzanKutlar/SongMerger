package me.Integer.Mediaplayer.library

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException

/**
 * Manual artist lists for single songs ("Manually split artists"). They win over the
 * automatic split and are stored in the app, never written into the audio file.
 */
class ArtistOverrides(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The manual artists of a song, or null when it uses the automatic split. */
    fun get(trackUri: String): List<String>? {
        val raw = prefs.getString(trackUri, null) ?: return null
        return try {
            val array = JSONArray(raw)
            val count = minOf(array.length(), ArtistSplitter.MAX_ARTISTS)
            clean(List(count) { array.optString(it) })
        } catch (e: JSONException) {
            Log.w(TAG, "dropping unreadable artist override for $trackUri: ${e.message}")
            null
        }
    }

    fun set(trackUri: String, artists: List<String>) {
        if (trackUri.isEmpty()) return
        val names = clean(artists.take(ArtistSplitter.MAX_ARTISTS))
        prefs.edit().putString(trackUri, JSONArray(names).toString()).apply()
    }

    /** Back to the automatic split. */
    fun clear(trackUri: String) {
        prefs.edit().remove(trackUri).apply()
    }

    private fun clean(names: List<String>): List<String> {
        return names.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private companion object {
        const val TAG = "ArtistOverrides"
        const val FILE = "artist_overrides"
    }
}
