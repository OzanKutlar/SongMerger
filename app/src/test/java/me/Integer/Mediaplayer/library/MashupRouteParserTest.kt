package me.Integer.Mediaplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MashupRouteParserTest {

    private val songA = track("a", "Song A", 200_000L)
    private val songB = track("b", "Song B", 240_000L)
    private val songC = track("c", "Song C", 180_000L)
    private val library = listOf(songA, songB, songC)
    private val byUri = library.associateBy { it.uri }
    private val exampleRoute = "Song A,1:45,Song B,0:33,Song B,2:45,Song C,0:15"

    @Test
    fun parseTime_readsMinutesAndHours() {
        assertEquals(105_000L, MashupRouteParser.parseTime("1:45"))
        assertEquals(33_000L, MashupRouteParser.parseTime("0:33"))
        assertEquals(3_723_000L, MashupRouteParser.parseTime("1:02:03"))
        assertEquals(125_000L, MashupRouteParser.parseTime(" 2:05 "))
    }

    @Test
    fun parseTime_rejectsMalformedInput() {
        assertNull(MashupRouteParser.parseTime("1:5"))
        assertNull(MashupRouteParser.parseTime("1:60"))
        assertNull(MashupRouteParser.parseTime("abc"))
        assertNull(MashupRouteParser.parseTime(""))
        assertNull(MashupRouteParser.parseTime(null))
        assertNull(MashupRouteParser.parseTime("1234:00"))
        assertNull(MashupRouteParser.parseTime("1:2:3"))
        assertNull(MashupRouteParser.parseTime("-1:00"))
    }

    @Test
    fun parse_exampleRoute() {
        val expected = listOf(
            MixSegment(songA.uri, 0L, 105_000L, MixSegment.DEFAULT_CROSSFADE_MS),
            MixSegment(songB.uri, 33_000L, 165_000L, MixSegment.DEFAULT_CROSSFADE_MS),
            MixSegment(songC.uri, 15_000L, MixSegment.END_OF_TRACK, MixSegment.DEFAULT_CROSSFADE_MS)
        )
        assertEquals(expected, MashupRouteParser.parse(exampleRoute, library))
    }

    @Test
    fun parse_isCaseInsensitive() {
        val segments = MashupRouteParser.parse("song a,1:45,SONG B,0:33", library)
        assertNotNull(segments)
        assertEquals(listOf(songA.uri, songB.uri), segments!!.map { it.trackUri })
    }

    @Test
    fun parse_allowsCommasInTitles() {
        val hello = track("h", "Hello, World", 200_000L)
        val segments = MashupRouteParser.parse("Hello, World,1:00,Song B,0:10", listOf(hello, songB))
        assertNotNull(segments)
        assertEquals(hello.uri, segments!![0].trackUri)
        assertEquals(60_000L, segments[0].endMs)
        assertEquals(10_000L, segments[1].startMs)
    }

    @Test
    fun parse_rejectsMismatchedStep() {
        assertNull(MashupRouteParser.parse("Song A,1:45,Song B,0:33,Song C,2:45,Song A,0:15", library))
    }

    @Test
    fun parse_rejectsExitBeforeEntry() {
        assertNull(MashupRouteParser.parse("Song A,1:45,Song B,2:50,Song B,2:45,Song C,0:15", library))
    }

    @Test
    fun parse_rejectsIncompleteRoute() {
        assertNull(MashupRouteParser.parse("Song A,1:45,Song B", library))
        assertNull(MashupRouteParser.parse("Song A,1:45", library))
        assertNull(MashupRouteParser.parse("", library))
        assertNull(MashupRouteParser.parse(null, library))
    }

    @Test
    fun parse_rejectsUnknownSong() {
        assertNull(MashupRouteParser.parse("Song A,1:45,Missing,0:33", library))
    }

    @Test
    fun format_roundTripsTheExample() {
        val segments = MashupRouteParser.parse(exampleRoute, library)
        assertNotNull(segments)
        assertEquals(exampleRoute, MashupRouteParser.format(segments!!) { byUri[it] })
    }

    @Test
    fun segmentsOfSteps_lastStepPlaysToEnd() {
        val ids = mapOf("a" to songA, "b" to songB)
        val steps = listOf(
            MashupRouteParser.Step("a", 0L, 105_000L, 4000L, 1f),
            MashupRouteParser.Step("b", 33_000L, 230_000L, 4000L, 1.04f)
        )
        val segments = MashupRouteParser.segmentsOfSteps(steps) { ids[it] }
        assertNotNull(segments)
        assertEquals(105_000L, segments!![0].endMs)
        assertEquals(MixSegment.END_OF_TRACK, segments[1].endMs)
        assertEquals(4000L, segments[1].crossfadeMs)
        assertEquals(1.04f, segments[1].tempoRatio, 1e-4f)
    }

    @Test
    fun segmentsOfSteps_rejectsMissingSongAndSingleStep() {
        val ids = mapOf("a" to songA)
        val missing = listOf(
            MashupRouteParser.Step("a", 0L, 105_000L, 4000L),
            MashupRouteParser.Step("x", 0L, 100_000L, 4000L)
        )
        assertNull(MashupRouteParser.segmentsOfSteps(missing) { ids[it] })
        val single = listOf(MashupRouteParser.Step("a", 0L, 105_000L, 4000L))
        assertNull(MashupRouteParser.segmentsOfSteps(single) { ids[it] })
    }

    @Test
    fun create_cleansValues() {
        val nearEnd = MixSegment.create(songA.uri, 0L, 199_000L, 6000L, 200_000L)
        assertEquals(MixSegment.END_OF_TRACK, nearEnd!!.endMs)
        assertNull(MixSegment.create(songA.uri, 199_500L, MixSegment.END_OF_TRACK, 6000L, 200_000L))
        assertNull(MixSegment.create(songA.uri, 0L, 500L, 6000L))
        assertNull(MixSegment.create("", 0L, 10_000L, 6000L))
        assertNull(MixSegment.create(songA.uri, -1L, 10_000L, 6000L))
        val longFade = MixSegment.create(songA.uri, 0L, MixSegment.END_OF_TRACK, 60_000L, tempoRatio = 2.0f)
        assertEquals(MixSegment.MAX_CROSSFADE_MS, longFade!!.crossfadeMs)
        assertEquals(MixSegment.MAX_TEMPO_RATIO, longFade.tempoRatio, 1e-4f)
    }

    @Test
    fun formatTime_roundsToSeconds() {
        assertEquals("1:45", MixSegment.formatTime(105_000L))
        assertEquals("0:05", MixSegment.formatTime(5_000L))
        assertEquals("0:01", MixSegment.formatTime(600L))
        assertEquals("62:03", MixSegment.formatTime(3_723_000L))
        assertEquals("0:00", MixSegment.formatTime(-50L))
    }

    private fun track(id: String, title: String, durationMs: Long): Track {
        return Track(
            uri = "content://test/$id",
            title = title,
            artistTag = "Artist",
            album = "Album",
            durationMs = durationMs,
            folderUri = "content://test",
            fileName = "$id.mp3",
            sizeBytes = 1000L,
            modifiedMs = 0L
        )
    }
}
