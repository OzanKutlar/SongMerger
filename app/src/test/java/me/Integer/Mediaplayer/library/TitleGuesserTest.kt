package me.Integer.Mediaplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleGuesserTest {

    @Test
    fun artistAndTitleAreReadFromTheName() {
        val guess = TitleGuesser.fromFileName("Metro Boomin, Future - Superhero.mp3")
        assertEquals("Superhero", guess.title)
        assertEquals("Metro Boomin, Future", guess.artistTag)
    }

    @Test
    fun leadingTrackNumberIsDropped() {
        val guess = TitleGuesser.fromFileName("03 - Daft Punk - One More Time.flac")
        assertEquals("One More Time", guess.title)
        assertEquals("Daft Punk", guess.artistTag)
        assertEquals("Intro", TitleGuesser.fromFileName("01. Intro.mp3").title)
    }

    @Test
    fun numbersThatBelongToTheTitleAreKept() {
        val guess = TitleGuesser.fromFileName("99 Problems.mp3")
        assertEquals("99 Problems", guess.title)
        assertEquals("", guess.artistTag)
    }

    @Test
    fun underscoresReadAsSpaces() {
        assertEquals("Song Name", TitleGuesser.fromFileName("Song_Name.m4a").title)
    }

    @Test
    fun nameWithoutDividerIsOnlyATitle() {
        val guess = TitleGuesser.fromFileName("Untitled.ogg")
        assertEquals("Untitled", guess.title)
        assertEquals("", guess.artistTag)
    }

    @Test
    fun nameWithoutExtensionIsKeptWhole() {
        assertEquals("Live at Wembley", TitleGuesser.fromFileName("Live at Wembley").title)
    }

    @Test
    fun missingNameGivesAnEmptyGuess() {
        assertEquals(TitleGuesser.Guess("", ""), TitleGuesser.fromFileName(null))
        assertEquals(TitleGuesser.Guess("", ""), TitleGuesser.fromFileName("  "))
    }
}
