package me.Integer.Mediaplayer.library

/**
 * A user playlist. Songs are stored by URI, in playing order.
 * [coverRef] is a song URI for [COVER_TRACK] and a file path inside the app for [COVER_IMAGE].
 */
data class Playlist(
    val id: Long,
    val name: String,
    val trackUris: List<String>,
    val coverType: Int = COVER_AUTO,
    val coverRef: String = ""
) {
    companion object {
        /** Composed from the covers of the first songs. */
        const val COVER_AUTO = 0

        /** The cover of one chosen song. */
        const val COVER_TRACK = 1

        /** An image the user picked from storage, copied into the app. */
        const val COVER_IMAGE = 2

        const val MAX_NAME_LENGTH = 80

        /** Trimmed and bounded; empty when the input is unusable. */
        fun cleanName(raw: String?): String {
            return raw?.trim().orEmpty().take(MAX_NAME_LENGTH)
        }
    }
}
