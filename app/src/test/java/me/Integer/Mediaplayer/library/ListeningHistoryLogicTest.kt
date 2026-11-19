package me.Integer.Mediaplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Calendar

class ListeningHistoryLogicTest {

    private fun track(uri: String, title: String, durationMs: Long): Track {
        return Track(
            uri = uri,
            title = title,
            artistTag = "Artist",
            album = "Album",
            durationMs = durationMs,
            folderUri = "content://folder",
            fileName = "$title.mp3",
            sizeBytes = 1000L,
            modifiedMs = 0L
        )
    }

    @Test
    fun requiredListenMs_requiresAtLeastHalfOfSongDuration() {
        assertEquals(120_000L, ListeningHistoryStore.requiredListenMs(240_000L))
        assertEquals(45_000L, ListeningHistoryStore.requiredListenMs(90_000L))
        // Very short clips still require at least MIN_HALF_LISTEN_MS (5s)
        assertEquals(5_000L, ListeningHistoryStore.requiredListenMs(6_000L))
    }

    @Test
    fun rankLifetime_ordersByTimeListenedCountTimesDuration() {
        // Song A: played 3 times, 2 minutes each -> 6 minutes total (360,000 ms)
        // Song B: played 2 times, 5 minutes each -> 10 minutes total (600,000 ms)
        val trackA = track("content://a", "Song A", 120_000L)
        val trackB = track("content://b", "Song B", 300_000L)
        val tracksByUri = mapOf(trackA.uri to trackA, trackB.uri to trackB)
        val entries = mapOf(
            trackA.uri to ListeningHistoryStore.Entry(trackA.uri, 3, listOf(10L, 20L, 30L)),
            trackB.uri to ListeningHistoryStore.Entry(trackB.uri, 2, listOf(15L, 25L))
        )

        val ranked = ListeningHistoryStore.rankLifetime(entries, tracksByUri)
        assertEquals(2, ranked.size)
        assertEquals(trackB.uri, ranked[0].uri)
        assertEquals(600_000L, ranked[0].listenedMs)
        assertEquals(trackA.uri, ranked[1].uri)
        assertEquals(360_000L, ranked[1].listenedMs)
    }

    @Test
    fun rankWindow_filtersToLast7And30DaysAndRanksByTimeListened() {
        val now = 1_800_000_000_000L
        val twoDaysAgo = now - 2L * 24L * 3600L * 1000L
        val fifteenDaysAgo = now - 15L * 24L * 3600L * 1000L
        val fortyDaysAgo = now - 40L * 24L * 3600L * 1000L

        val trackA = track("content://a", "Weekly Hit", 200_000L)
        val trackB = track("content://b", "Monthly Hit", 240_000L)
        val tracksByUri = mapOf(trackA.uri to trackA, trackB.uri to trackB)

        val entries = mapOf(
            trackA.uri to ListeningHistoryStore.Entry(trackA.uri, 2, listOf(twoDaysAgo, fortyDaysAgo)),
            trackB.uri to ListeningHistoryStore.Entry(trackB.uri, 3, listOf(fifteenDaysAgo, fifteenDaysAgo + 1000L, fortyDaysAgo))
        )

        val week = ListeningHistoryStore.rankWindow(
            entries,
            tracksByUri,
            now - ListeningHistoryStore.WEEK_MS,
            now
        )
        assertEquals(1, week.size)
        assertEquals(trackA.uri, week[0].uri)
        assertEquals(1, week[0].playCount)
        assertEquals(200_000L, week[0].listenedMs)

        val month = ListeningHistoryStore.rankWindow(
            entries,
            tracksByUri,
            now - ListeningHistoryStore.MONTH_MS,
            now
        )
        assertEquals(2, month.size)
        // trackB has 2 plays in the last 30 days (480,000 ms) vs trackA's 1 play (200,000 ms)
        assertEquals(trackB.uri, month[0].uri)
        assertEquals(480_000L, month[0].listenedMs)
        assertEquals(trackA.uri, month[1].uri)
    }

    @Test
    fun recapIdEncodingAndSeasonMapping_roundTripsCorrectly() {
        val monthId = ListeningHistoryStore.encodeMonthRecapId(2026, Calendar.JUNE)
        val decodedMonth = ListeningHistoryStore.decodeMonthRecapId(monthId)
        assertNotNull(decodedMonth)
        assertEquals(2026 to Calendar.JUNE, decodedMonth)

        val seasonPair = ListeningHistoryStore.seasonYearAndIndex(2026, Calendar.JUNE)
        assertEquals(2026 to ListeningHistoryStore.SEASON_SUMMER, seasonPair)

        val seasonId = ListeningHistoryStore.encodeSeasonRecapId(2026, ListeningHistoryStore.SEASON_SUMMER)
        val decodedSeason = ListeningHistoryStore.decodeSeasonRecapId(seasonId)
        assertNotNull(decodedSeason)
        assertEquals(2026 to ListeningHistoryStore.SEASON_SUMMER, decodedSeason)
    }
}
