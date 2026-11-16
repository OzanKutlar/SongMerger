package me.Integer.Mediaplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSorterTest {

    private fun track(title: String, artist: String, modified: Long): Track {
        return Track("uri:$title", title, artist, "", 0L, "folder", "$title.mp3", 1L, modified)
    }

    private val a = track("apple", "Zedd", 10L)
    private val b = track("Banana", "", 30L)
    private val c = track("cherry", "adele", 20L)

    private fun titles(mode: Int): List<String> {
        return TrackSorter.sort(listOf(b, c, a), mode) { it.artistTag }.map { it.title }
    }

    @Test
    fun titleOrderIgnoresCase() {
        assertEquals(listOf("apple", "Banana", "cherry"), titles(TrackSorter.BY_TITLE))
    }

    @Test
    fun artistOrderPutsUnknownLast() {
        assertEquals(listOf("cherry", "apple", "Banana"), titles(TrackSorter.BY_ARTIST))
    }

    @Test
    fun recentOrderIsNewestFirst() {
        assertEquals(listOf("Banana", "cherry", "apple"), titles(TrackSorter.BY_RECENT))
    }

    @Test
    fun sameDateFallsBackToTitle() {
        val x = track("b", "", 5L)
        val y = track("a", "", 5L)
        val sorted = TrackSorter.sort(listOf(x, y), TrackSorter.BY_RECENT) { it.artistTag }
        assertEquals(listOf("a", "b"), sorted.map { it.title })
    }

    @Test
    fun unknownModeSortsByTitle() {
        assertEquals(titles(TrackSorter.BY_TITLE), titles(99))
    }
}
