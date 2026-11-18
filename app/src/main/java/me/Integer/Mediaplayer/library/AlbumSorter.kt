package me.Integer.Mediaplayer.library

/**
 * Sort orders for the album list. Pure Kotlin.
 */
object AlbumSorter {

    const val BY_NAME = 0
    const val BY_ARTIST = 1
    const val BY_COUNT = 2

    val MODES = BY_NAME..BY_COUNT

    private val TEXT: Comparator<String> = String.CASE_INSENSITIVE_ORDER

    fun sort(albums: List<Album>, mode: Int): List<Album> {
        val byName = compareBy<Album> { it.name.isEmpty() }.thenBy(TEXT) { it.name }
        return when (mode) {
            BY_ARTIST -> albums.sortedWith(
                compareBy<Album> { it.artist.isEmpty() }
                    .thenBy(TEXT) { it.artist }
                    .then(byName)
            )
            BY_COUNT -> albums.sortedWith(compareByDescending<Album> { it.tracks.size }.then(byName))
            else -> albums.sortedWith(byName)
        }
    }
}
