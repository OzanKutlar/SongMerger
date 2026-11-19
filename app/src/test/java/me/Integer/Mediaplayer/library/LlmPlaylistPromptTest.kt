package me.Integer.Mediaplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPlaylistPromptTest {

    private val daftOne = track("1", "Around the World", "Daft Punk", "Homework")
    private val daftTwo = track("2", "One More Time", "Daft Punk", "Discovery")
    private val ninetiesOne = track("3", "Smells Like Teen Spirit", "Nirvana", "Nevermind")
    private val ninetiesTwo = track("4", "Wonderwall", "Oasis", "(What's the Story) Morning Glory?")
    private val library = listOf(daftOne, daftTwo, ninetiesOne, ninetiesTwo)

    @Test
    fun buildPrompt_includesGenreMoodNoEmojiAndExampleNames() {
        val result = LlmPlaylistPrompt.buildPrompt(library)

        assertEquals(4, result.includedTracks)
        assertEquals(4, result.totalTracks)
        assertFalse(result.truncated)
        assertTrue(result.prompt.contains("genre", ignoreCase = true))
        assertTrue(result.prompt.contains("mood", ignoreCase = true))
        assertTrue(result.prompt.contains("Do NOT use any emojis"))
        assertTrue(result.prompt.contains("A Streak through the 90s"))
        assertTrue(result.prompt.contains("Best of Daft Punk"))
        assertTrue(result.prompt.contains("\"Around the World\" — Daft Punk [Album: Homework]"))
        assertTrue(result.prompt.contains("\"Smells Like Teen Spirit\" — Nirvana [Album: Nevermind]"))
    }

    @Test
    fun buildPrompt_respectsRequestedPlaylistCount() {
        val result = LlmPlaylistPrompt.buildPrompt(library, playlistCount = 4)
        assertTrue(result.prompt.contains("Create exactly 4 cohesive playlists."))
    }

    @Test
    fun buildPrompt_emptyLibraryReturnsEmptyPrompt() {
        val result = LlmPlaylistPrompt.buildPrompt(emptyList())
        assertEquals("", result.prompt)
        assertEquals(0, result.includedTracks)
        assertEquals(0, result.totalTracks)
    }

    @Test
    fun stripEmojis_removesEmojisAndPreservesPlainText() {
        assertEquals(
            "A Streak through the 90s",
            LlmPlaylistPrompt.stripEmojis("🔥 A Streak through the 90s 🎸✨")
        )
        assertEquals(
            "Best of Daft Punk",
            LlmPlaylistPrompt.stripEmojis("🤖 Best of Daft Punk 🎵")
        )
    }

    @Test
    fun parsePlaylists_parsesMultipleStandaloneJsonObjectsInChatOutput() {
        val llmReply = """
            Here are a few playlists based on the genres and moods of your songs:

            ```json
            {
              "name": "A Streak through the 90s",
              "tracks": [
                { "title": "Smells Like Teen Spirit", "artist": "Nirvana" },
                { "title": "Wonderwall", "artist": "Oasis" }
              ]
            }
            ```

            And here is a second one:

            ```json
            {
              "name": "Best of Daft Punk",
              "tracks": [
                { "title": "Around the World", "artist": "Daft Punk" },
                { "title": "One More Time", "artist": "Daft Punk" }
              ]
            }
            ```
        """.trimIndent()

        val parsed = LlmPlaylistPrompt.parsePlaylists(llmReply, library)
        assertEquals(2, parsed.size)

        assertEquals("A Streak through the 90s", parsed[0].name)
        assertEquals(listOf(ninetiesOne.uri, ninetiesTwo.uri), parsed[0].tracks.map { it.uri })

        assertEquals("Best of Daft Punk", parsed[1].name)
        assertEquals(listOf(daftOne.uri, daftTwo.uri), parsed[1].tracks.map { it.uri })
    }

    @Test
    fun parsePlaylists_stripsEmojisFromNamesAndSupportsArrayAndStringTracks() {
        val llmReply = """
            [
              {
                "name": "🔥 Best of Daft Punk 🎧",
                "tracks": [
                  "Around the World - Daft Punk",
                  "One More Time - Daft Punk"
                ]
              }
            ]
        """.trimIndent()

        val parsed = LlmPlaylistPrompt.parsePlaylists(llmReply, library)
        assertEquals(1, parsed.size)
        assertEquals("Best of Daft Punk", parsed[0].name)
        assertEquals(listOf(daftOne.uri, daftTwo.uri), parsed[0].tracks.map { it.uri })
    }

    @Test
    fun parsePlaylists_handlesPlaylistsWrapperAndMalformedInput() {
        val wrapped = """
            {
              "playlists": [
                {
                  "name": "A Streak through the 90s",
                  "tracks": [
                    { "title": "Wonderwall", "artist": "Oasis" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val parsed = LlmPlaylistPrompt.parsePlaylists(wrapped, library)
        assertEquals(1, parsed.size)
        assertEquals("A Streak through the 90s", parsed[0].name)
        assertEquals(listOf(ninetiesTwo.uri), parsed[0].tracks.map { it.uri })

        assertTrue(LlmPlaylistPrompt.parsePlaylists("", library).isEmpty())
        assertTrue(LlmPlaylistPrompt.parsePlaylists("not json at all { broken", library).isEmpty())
    }

    private fun track(id: String, title: String, artist: String, album: String): Track {
        return Track(
            uri = "content://test/$id",
            title = title,
            artistTag = artist,
            album = album,
            durationMs = 200_000L,
            folderUri = "content://test",
            fileName = "$id.mp3",
            sizeBytes = 1000L,
            modifiedMs = 0L
        )
    }
}
