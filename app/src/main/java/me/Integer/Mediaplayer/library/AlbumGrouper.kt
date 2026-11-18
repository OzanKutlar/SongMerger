package me.Integer.Mediaplayer.library

import java.util.Locale

/**
 * Groups tracks into albums. Album names differing only in case are merged into one.
 * Pure Kotlin, unit-testable on the JVM.
 */
object AlbumGrouper {

    fun group(tracks: List<Track>, artistOf: (Track) -> String): List<Album> {
        val names = LinkedHashMap<String, String>()
        val buckets = LinkedHashMap<String, MutableList<Track>>()
        for (track in tracks) {
            val raw = track.album.trim()
            val key = raw.lowercase(Locale.ROOT)
            names.putIfAbsent(key, raw)
            buckets.getOrPut(key) { ArrayList() }.add(track)
        }
        return buckets.map { (key, grouped) ->
            val primaryArtist = grouped.firstNotNullOfOrNull { artistOf(it).takeIf { a -> a.isNotEmpty() } }.orEmpty()
            Album(names.getValue(key), primaryArtist, grouped)
        }
    }
}
