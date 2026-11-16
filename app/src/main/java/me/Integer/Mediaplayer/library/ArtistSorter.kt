package me.Integer.Mediaplayer.library

/**
 * Sort orders for the artist list. Pure Kotlin, unit tested on the JVM.
 */
object ArtistSorter {

    const val BY_NAME = 0
    const val BY_COUNT = 1

    val MODES = BY_NAME..BY_COUNT

    private val TEXT: Comparator<String> = String.CASE_INSENSITIVE_ORDER

    fun sort(artists: List<Artist>, mode: Int): List<Artist> {
        val byName = compareBy<Artist> { it.name.isEmpty() }.thenBy(TEXT) { it.name }
        return when (mode) {
            BY_COUNT -> artists.sortedWith(compareByDescending<Artist> { it.tracks.size }.then(byName))
            else -> artists.sortedWith(byName)
        }
    }
}
