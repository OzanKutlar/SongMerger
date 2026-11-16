package me.Integer.Mediaplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistSorterTest {

    private fun artist(name: String, count: Int): Artist {
        val tracks = (1..count).map { index ->
            Track("uri:$name:$index", "Song $index", name, "", 0L, "folder", "s.mp3", 1L, 1L)
        }
        return Artist(name, tracks)
    }

    private val zedd = artist("Zedd", 5)
    private val adele = artist("Adele", 2)
    private val future = artist("Future", 5)
    private val unknown = artist("", 10)

    @Test
    fun sortByNameIsAlphabeticalWithUnknownLast() {
        val sorted = ArtistSorter.sort(listOf(zedd, unknown, adele, future), ArtistSorter.BY_NAME)
        assertEquals(listOf("Adele", "Future", "Zedd", ""), sorted.map { it.name })
    }

    @Test
    fun sortByCountIsDescendingWithAlphabeticalTieBreaker() {
        val sorted = ArtistSorter.sort(listOf(zedd, adele, future), ArtistSorter.BY_COUNT)
        assertEquals(listOf("Future", "Zedd", "Adele"), sorted.map { it.name })
    }

    @Test
    fun unknownModeFallsBackToName() {
        val sorted = ArtistSorter.sort(listOf(zedd, adele), 99)
        assertEquals(listOf("Adele", "Zedd"), sorted.map { it.name })
    }
}
