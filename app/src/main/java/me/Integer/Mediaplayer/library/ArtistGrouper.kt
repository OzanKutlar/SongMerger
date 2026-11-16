package me.Integer.Mediaplayer.library

import java.util.Locale

/**
 * Builds the artist list: a song with three artists appears under all three.
 * Names that differ only in case are one artist. Pure Kotlin, unit tested on the JVM.
 */
object ArtistGrouper {

    /**
     * @param artistsOf the individual artists of a track; an empty list files the track
     * under the unknown artist (empty name)
     */
    fun group(tracks: List<Track>, artistsOf: (Track) -> List<String>): List<Artist> {
        val names = LinkedHashMap<String, String>()
        val buckets = LinkedHashMap<String, MutableList<Track>>()
        for (track in tracks) {
            for (name in artistsOf(track).ifEmpty { listOf("") }) {
                val key = name.lowercase(Locale.ROOT)
                names.putIfAbsent(key, name)
                buckets.getOrPut(key) { ArrayList() }.add(track)
            }
        }
        return buckets
            .map { (key, grouped) -> Artist(names.getValue(key), grouped) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
