package me.Integer.Mediaplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMatcherTest {

    private val library = listOf(
        track("content://1", "Midnight City", "M83", "Hurry Up, We're Dreaming"),
        track("content://2", "Get Lucky", "Daft Punk, Pharrell Williams", "Random Access Memories"),
        track("content://3", "Starboy", "The Weeknd", "Starboy")
    )

    @Test
    fun matchesExactTitleAndArtist() {
        val matched = TrackMatcher.match("Midnight City", "M83", library)
        assertNotNull(matched)
        assertEquals("content://1", matched?.uri)
    }

    @Test
    fun matchesTitleWithFeatAndRemasterBrackets() {
        val matched = TrackMatcher.match(
            "Get Lucky (feat. Pharrell Williams) [Official Audio]",
            "Daft Punk",
            library
        )
        assertNotNull(matched)
        assertEquals("content://2", matched?.uri)
    }

    @Test
    fun matchesCombinedArtistDashTitle() {
        val matched = TrackMatcher.match("The Weeknd - Starboy (Official Video)", "TheWeekndVEVO", library)
        assertNotNull(matched)
        assertEquals("content://3", matched?.uri)
    }

    @Test
    fun returnsNullForUnknownSong() {
        val matched = TrackMatcher.match("Nonexistent Song", "Nobody", library)
        assertNull(matched)
    }

    @Test
    fun fingerprintNormalizesCaseAndPunctuation() {
        val first = TrackMatcher.fingerprint("Midnight City!", "M83")
        val second = TrackMatcher.fingerprint("  midnight   city ", "m83")
        assertEquals(first, second)
    }

    private fun track(uri: String, title: String, artist: String, album: String): Track {
        return Track(
            uri = uri,
            title = title,
            artistTag = artist,
            album = album,
            durationMs = 200_000L,
            folderUri = "content://folder",
            fileName = "$artist - $title.mp3",
            sizeBytes = 1000L,
            modifiedMs = 1L
        )
    }
}
