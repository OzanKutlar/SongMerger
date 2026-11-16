package me.Integer.Mediaplayer.library

/**
 * Sort orders for the track list. Pure Kotlin, unit tested on the JVM.
 * Artist keys are computed once per track, since splitting artists is not free.
 */
object TrackSorter {

    const val BY_TITLE = 0
    const val BY_ARTIST = 1

    /** Newest file first; for imported files the modification date is when they arrived. */
    const val BY_RECENT = 2

    val MODES = BY_TITLE..BY_RECENT

    private val TEXT: Comparator<String> = String.CASE_INSENSITIVE_ORDER

    fun sort(tracks: List<Track>, mode: Int, artistOf: (Track) -> String): List<Track> {
        val byTitle = compareBy(TEXT) { track: Track -> track.title }
        return when (mode) {
            BY_ARTIST -> sortByArtist(tracks, artistOf)
            BY_RECENT -> tracks.sortedWith(compareByDescending<Track> { it.modifiedMs }.then(byTitle))
            else -> tracks.sortedWith(byTitle)
        }
    }

    /** Unknown artists go last; songs of one artist are ordered by title. */
    private fun sortByArtist(tracks: List<Track>, artistOf: (Track) -> String): List<Track> {
        val order = compareBy<Pair<Track, String>> { it.second.isEmpty() }
            .thenBy(TEXT) { it.second }
            .thenBy(TEXT) { it.first.title }
        return tracks.map { it to artistOf(it) }.sortedWith(order).map { it.first }
    }
}
