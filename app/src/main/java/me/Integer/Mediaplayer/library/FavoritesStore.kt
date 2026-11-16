package me.Integer.Mediaplayer.library

import android.content.Context

/**
 * Stores the URIs of favorited songs in SharedPreferences.
 */
class FavoritesStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isFavorite(trackUri: String): Boolean {
        if (trackUri.isEmpty()) return false
        return trackUri in stored()
    }

    /** Toggles the favorite state of [trackUri] and returns the new state. */
    fun toggle(trackUri: String): Boolean {
        if (trackUri.isEmpty()) return false
        val current = stored()
        val nowFavorite = trackUri !in current
        val updated = if (nowFavorite) current + trackUri else current - trackUri
        save(updated)
        return nowFavorite
    }

    /** Adds all [trackUris] in a single write and returns how many new tracks were added. */
    fun addAll(trackUris: Collection<String>): Int {
        val valid = trackUris.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return 0
        val current = stored()
        val updated = current + valid
        val added = updated.size - current.size
        if (added > 0) save(updated)
        return added
    }

    /** Removes all [trackUris] in a single write and returns how many were favorites. */
    fun removeAll(trackUris: Collection<String>): Int {
        val current = stored()
        val doomed = trackUris.filter { it.isNotEmpty() && it in current }.toSet()
        if (doomed.isEmpty()) return 0
        save(current - doomed)
        return doomed.size
    }

    fun all(): Set<String> {
        return stored()
    }

    private fun stored(): Set<String> {
        return prefs.getStringSet(KEY_URIS, null)?.toSet() ?: emptySet()
    }

    private fun save(uris: Set<String>) {
        prefs.edit().putStringSet(KEY_URIS, uris).apply()
    }

    private companion object {
        const val FILE = "favorite_tracks"
        const val KEY_URIS = "uris"
    }
}
