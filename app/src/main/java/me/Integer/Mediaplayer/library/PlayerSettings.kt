package me.Integer.Mediaplayer.library

import android.content.Context

/** User settings. For now only the text that separates artists inside one tag. */
class PlayerSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Never blank: an unusable value falls back to the comma. */
    var artistSeparator: String
        get() = sanitise(prefs.getString(KEY_SEPARATOR, DEFAULT_SEPARATOR))
        set(value) {
            prefs.edit().putString(KEY_SEPARATOR, sanitise(value)).apply()
        }

    companion object {
        const val DEFAULT_SEPARATOR = ","
        const val MAX_SEPARATOR_LENGTH = 8
        private const val FILE = "player_settings"
        private const val KEY_SEPARATOR = "artist_separator"

        /** Trims the input and bounds its length; blank input gives the default. */
        fun sanitise(raw: String?): String {
            val trimmed = raw?.trim().orEmpty().take(MAX_SEPARATOR_LENGTH)
            return trimmed.ifEmpty { DEFAULT_SEPARATOR }
        }
    }
}
