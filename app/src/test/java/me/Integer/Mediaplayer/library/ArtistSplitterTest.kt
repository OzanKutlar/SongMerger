package me.Integer.Mediaplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistSplitterTest {

    @Test
    fun commaSeparatedTagBecomesSeparateArtists() {
        assertEquals(
            listOf("Metro Boomin", "A\$AP Rocky", "Roisee"),
            ArtistSplitter.split("Metro Boomin, A\$AP Rocky, Roisee", ",")
        )
    }

    @Test
    fun namesAreTrimmedAndEmptyPiecesDropped() {
        assertEquals(listOf("A", "B"), ArtistSplitter.split("  A ,, B , ", ","))
    }

    @Test
    fun repeatedNamesAreKeptOnceIgnoringCase() {
        assertEquals(listOf("Future", "Drake"), ArtistSplitter.split("Future, future, Drake", ","))
    }

    @Test
    fun missingTagGivesNoArtists() {
        assertEquals(emptyList<String>(), ArtistSplitter.split(null, ","))
        assertEquals(emptyList<String>(), ArtistSplitter.split("   ", ","))
    }

    @Test
    fun singleArtistStaysWhole() {
        assertEquals(listOf("Daft Punk"), ArtistSplitter.split("Daft Punk", ","))
    }

    @Test
    fun anotherSeparatorLeavesCommasAlone() {
        assertEquals(
            listOf("Tyler, The Creator", "Kali Uchis"),
            ArtistSplitter.split("Tyler, The Creator; Kali Uchis", ";")
        )
    }

    @Test
    fun emptySeparatorDoesNotSplit() {
        assertEquals(listOf("A, B"), ArtistSplitter.split(" A, B ", ""))
    }

    @Test
    fun artistCountIsBounded() {
        val tag = (1..100).joinToString(",") { "Artist $it" }
        assertEquals(ArtistSplitter.MAX_ARTISTS, ArtistSplitter.split(tag, ",").size)
    }

    @Test
    fun joinPutsOneSpaceAfterTheSeparator() {
        assertEquals("Metro Boomin, Future", ArtistSplitter.join(listOf("Metro Boomin", "Future"), ","))
        assertEquals("A; B", ArtistSplitter.join(listOf("A", "B"), " ; "))
        assertEquals("", ArtistSplitter.join(emptyList(), ","))
    }
}
