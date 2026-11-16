package me.Integer.Mediaplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistGrouperTest {

    private fun track(title: String, artistTag: String): Track {
        return Track("uri:$title", title, artistTag, "", 0L, "folder", "$title.mp3", 1L, 1L)
    }

    private fun group(tracks: List<Track>): List<Artist> {
        return ArtistGrouper.group(tracks) { ArtistSplitter.split(it.artistTag, ",") }
    }

    @Test
    fun sharedSongIsListedUnderEveryArtist() {
        val shared = track("Shared", "Metro Boomin, Future")
        val solo = track("Solo", "Future")
        val artists = group(listOf(shared, solo))
        assertEquals(listOf("Future", "Metro Boomin"), artists.map { it.name })
        assertEquals(listOf(shared, solo), artists[0].tracks)
        assertEquals(listOf(shared), artists[1].tracks)
    }

    @Test
    fun namesDifferingOnlyInCaseAreOneArtist() {
        val artists = group(listOf(track("One", "Drake"), track("Two", "DRAKE")))
        assertEquals(1, artists.size)
        assertEquals("Drake", artists[0].name)
        assertEquals(2, artists[0].tracks.size)
    }

    @Test
    fun songsWithoutArtistGoToTheUnknownArtist() {
        val artists = group(listOf(track("Mystery", ""), track("Known", "Adele")))
        assertEquals(listOf("", "Adele"), artists.map { it.name })
    }

    @Test
    fun artistsAreSortedIgnoringCase() {
        val artists = group(listOf(track("One", "zedd"), track("Two", "Adele"), track("Three", "blur")))
        assertEquals(listOf("Adele", "blur", "zedd"), artists.map { it.name })
    }

    @Test
    fun emptyLibraryHasNoArtists() {
        assertEquals(emptyList<Artist>(), group(emptyList()))
    }
}
