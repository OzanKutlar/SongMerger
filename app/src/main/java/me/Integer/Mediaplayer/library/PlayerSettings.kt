package me.Integer.Mediaplayer.library

import android.content.Context

/** User settings: the artist separator, track sort order, and artist sort order. */
class PlayerSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Never blank: an unusable value falls back to the comma. */
    var artistSeparator: String
        get() = sanitise(prefs.getString(KEY_SEPARATOR, DEFAULT_SEPARATOR))
        set(value) {
            prefs.edit().putString(KEY_SEPARATOR, sanitise(value)).apply()
        }

    /** One of [TrackSorter.MODES]; anything else reads as sorting by title. */
    var trackSort: Int
        get() {
            val stored = prefs.getInt(KEY_SORT, TrackSorter.BY_TITLE)
            return if (stored in TrackSorter.MODES) stored else TrackSorter.BY_TITLE
        }
        set(value) {
            if (value in TrackSorter.MODES) prefs.edit().putInt(KEY_SORT, value).apply()
        }

    /** One of [ArtistSorter.MODES]; anything else reads as sorting by name. */
    var artistSort: Int
        get() {
            val stored = prefs.getInt(KEY_ARTIST_SORT, ArtistSorter.BY_NAME)
            return if (stored in ArtistSorter.MODES) stored else ArtistSorter.BY_NAME
        }
        set(value) {
            if (value in ArtistSorter.MODES) prefs.edit().putInt(KEY_ARTIST_SORT, value).apply()
        }

    companion object {
        const val DEFAULT_SEPARATOR = ","
        const val MAX_SEPARATOR_LENGTH = 8
        private const val FILE = "player_settings"
        private const val KEY_SEPARATOR = "artist_separator"
        private const val KEY_SORT = "track_sort"
        private const val KEY_ARTIST_SORT = "artist_sort"

        /** Trims the input and bounds its length; blank input gives the default. */
        fun sanitise(raw: String?): String {
            val trimmed = raw?.trim().orEmpty().take(MAX_SEPARATOR_LENGTH)
            return trimmed.ifEmpty { DEFAULT_SEPARATOR }
        }
    }
}
