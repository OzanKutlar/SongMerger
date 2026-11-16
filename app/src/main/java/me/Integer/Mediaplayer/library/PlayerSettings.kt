package me.Integer.Mediaplayer.library

import android.content.Context
import java.util.Locale

/** User settings: the artist separator, the sort orders, and the mashup server address. */
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

    /** The address of the mashup server, such as http://192.168.1.50:8765; empty when not set. */
    var aiServerUrl: String
        get() = sanitiseServerUrl(prefs.getString(KEY_AI_SERVER, ""))
        set(value) {
            prefs.edit().putString(KEY_AI_SERVER, sanitiseServerUrl(value)).apply()
        }

    companion object {
        const val DEFAULT_SEPARATOR = ","
        const val MAX_SEPARATOR_LENGTH = 8
        const val MAX_SERVER_URL_LENGTH = 200
        private const val FILE = "player_settings"
        private const val KEY_SEPARATOR = "artist_separator"
        private const val KEY_SORT = "track_sort"
        private const val KEY_ARTIST_SORT = "artist_sort"
        private const val KEY_AI_SERVER = "ai_server_url"
        private const val HTTP = "http://"
        private const val HTTPS = "https://"
        private const val SCHEME_MARK = "://"

        /** Trims the input and bounds its length; blank input gives the default. */
        fun sanitise(raw: String?): String {
            val trimmed = raw?.trim().orEmpty().take(MAX_SEPARATOR_LENGTH)
            return trimmed.ifEmpty { DEFAULT_SEPARATOR }
        }

        /**
         * An http or https address without a trailing slash. A bare host such as
         * 192.168.1.50:8765 gets http:// in front. Unusable input gives an empty string.
         */
        fun sanitiseServerUrl(raw: String?): String {
            val trimmed = raw?.trim().orEmpty().take(MAX_SERVER_URL_LENGTH)
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return ""
            val lower = trimmed.lowercase(Locale.ROOT)
            val hasScheme = lower.startsWith(HTTP) || lower.startsWith(HTTPS)
            if (!hasScheme && trimmed.contains(SCHEME_MARK)) return ""
            val clean = (if (hasScheme) trimmed else HTTP + trimmed).trimEnd('/')
            return if (clean.substringAfter(SCHEME_MARK).isEmpty()) "" else clean
        }
    }
}
