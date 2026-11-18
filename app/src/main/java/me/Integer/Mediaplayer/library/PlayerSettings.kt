package me.Integer.Mediaplayer.library

import android.content.Context
import java.util.Locale

/** User settings: the artist separator, the sort orders, crossfade, and the mashup server. */
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

    /** One of [AlbumSorter.MODES]; anything else reads as sorting by album name. */
    var albumSort: Int
        get() {
            val stored = prefs.getInt(KEY_ALBUM_SORT, AlbumSorter.BY_NAME)
            return if (stored in AlbumSorter.MODES) stored else AlbumSorter.BY_NAME
        }
        set(value) {
            if (value in AlbumSorter.MODES) prefs.edit().putInt(KEY_ALBUM_SORT, value).apply()
        }

    /** The address of the mashup server, such as http://192.168.1.50:8765; empty when not set. */
    var aiServerUrl: String
        get() = sanitiseServerUrl(prefs.getString(KEY_AI_SERVER, ""))
        set(value) {
            prefs.edit().putString(KEY_AI_SERVER, sanitiseServerUrl(value)).apply()
        }

    /** Shared secret sent to the mashup server; empty when the server runs without one. */
    var aiServerToken: String
        get() = sanitiseToken(prefs.getString(KEY_AI_TOKEN, ""))
        set(value) {
            prefs.edit().putString(KEY_AI_TOKEN, sanitiseToken(value)).apply()
        }

    /** Crossfade between normal songs in whole seconds; 0 turns it off. */
    var crossfadeSeconds: Int
        get() = prefs.getInt(KEY_CROSSFADE, 0).coerceIn(0, MAX_CROSSFADE_SECONDS)
        set(value) {
            prefs.edit().putInt(KEY_CROSSFADE, value.coerceIn(0, MAX_CROSSFADE_SECONDS)).apply()
        }

    val crossfadeMs: Long
        get() = crossfadeSeconds * MS_PER_SECOND

    /** Default playback speed multiplier in 0.5f..2.0f. */
    var playbackSpeed: Float
        get() {
            val raw = prefs.getFloat(KEY_PLAY_SPEED, 1.0f)
            return if (raw.isFinite()) raw.coerceIn(MIN_PLAY_SPEED, MAX_PLAY_SPEED) else 1.0f
        }
        set(value) {
            val clean = if (value.isFinite()) value.coerceIn(MIN_PLAY_SPEED, MAX_PLAY_SPEED) else 1.0f
            prefs.edit().putFloat(KEY_PLAY_SPEED, clean).apply()
        }

    /** Skips silent gaps inside and between tracks when enabled. */
    var skipSilence: Boolean
        get() = prefs.getBoolean(KEY_SKIP_SILENCE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SKIP_SILENCE, value).apply()
        }

    /** Prevents adding a song to the queue if it is already queued. */
    var preventDuplicatesInQueue: Boolean
        get() = prefs.getBoolean(KEY_PREVENT_DUPLICATES, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PREVENT_DUPLICATES, value).apply()
        }

    companion object {
        const val DEFAULT_SEPARATOR = ","
        const val MAX_SEPARATOR_LENGTH = 8
        const val MAX_SERVER_URL_LENGTH = 200
        const val MAX_TOKEN_LENGTH = 128
        const val MAX_CROSSFADE_SECONDS = 12
        const val MIN_PLAY_SPEED = 0.5f
        const val MAX_PLAY_SPEED = 2.0f
        private const val MS_PER_SECOND = 1000L
        private const val FILE = "player_settings"
        private const val KEY_SEPARATOR = "artist_separator"
        private const val KEY_SORT = "track_sort"
        private const val KEY_ARTIST_SORT = "artist_sort"
        private const val KEY_ALBUM_SORT = "album_sort"
        private const val KEY_AI_SERVER = "ai_server_url"
        private const val KEY_AI_TOKEN = "ai_server_token"
        private const val KEY_CROSSFADE = "crossfade_seconds"
        private const val KEY_PLAY_SPEED = "play_speed"
        private const val KEY_SKIP_SILENCE = "skip_silence"
        private const val KEY_PREVENT_DUPLICATES = "prevent_duplicates"
        private const val HTTP = "http://"
        private const val HTTPS = "https://"
        private const val SCHEME_MARK = "://"

        /** Printable ASCII only, so the token is always a valid header value. */
        fun sanitiseToken(raw: String?): String {
            return raw?.trim().orEmpty().take(MAX_TOKEN_LENGTH).filter { it in '!'..'~' }
        }

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
