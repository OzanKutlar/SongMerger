package me.Integer.Mediaplayer.library

import java.util.Locale

/**
 * Turns an artist tag such as "Metro Boomin, A$AP Rocky, Roisee" into separate artists.
 * Pure Kotlin, so it is unit tested on the JVM.
 */
object ArtistSplitter {

    /** More names than this in one tag is treated as noise and cut off. */
    const val MAX_ARTISTS = 32

    /**
     * Splits [tag] at every [separator]. Names are trimmed, empty pieces dropped and
     * repeated names (ignoring case) kept once, in their original order.
     */
    fun split(tag: String?, separator: String): List<String> {
        if (tag.isNullOrBlank()) return emptyList()
        if (separator.isEmpty()) return listOf(tag.trim())
        val unique = LinkedHashMap<String, String>()
        for (piece in tag.split(separator)) {
            val name = piece.trim()
            if (name.isEmpty()) continue
            unique.putIfAbsent(name.lowercase(Locale.ROOT), name)
            if (unique.size >= MAX_ARTISTS) break
        }
        return unique.values.toList()
    }

    /** Joins artists for display: the separator followed by one space. */
    fun join(artists: List<String>, separator: String): String {
        return artists.joinToString(separator.trim() + " ")
    }
}
