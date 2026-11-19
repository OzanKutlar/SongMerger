package me.Integer.Mediaplayer.library

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import me.Integer.Mediaplayer.R
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Tracks every song listen once the user has listened to at least half of the song.
 * Rankings for Most Listened (Lifetime, 7-day, 30-day) and Seasonal/Monthly Recaps are
 * ordered by total time listened (`playCount * track.durationMs`).
 */
class ListeningHistoryStore(context: Context, private val onChanged: () -> Unit) {

    data class Entry(
        val uri: String,
        val lifetimeCount: Int,
        val timestamps: List<Long>
    )

    data class TrackListenStats(
        val uri: String,
        val playCount: Int,
        val listenedMs: Long
    )

    data class SystemPlaylistDetails(
        val playlist: Playlist,
        val statsByUri: Map<String, TrackListenStats>
    )

    data class FavoritesShowcase(
        val pinned: List<Playlist>,
        val recaps: List<Playlist>
    )

    private val appContext = context.applicationContext
    private val file = AtomicFile(File(appContext.filesDir, FILE_NAME))
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Mediaplayer-History").apply { isDaemon = true }
    }
    private var loaded: MutableMap<String, Entry>? = null

    /** Warms the in-memory history cache off the main thread. */
    fun preload() {
        synchronized(lock) { entries() }
    }

    /** Records one completed (>= 50% duration) listen of [trackUri] at [timestampMs]. */
    fun recordPlay(trackUri: String, timestampMs: Long = System.currentTimeMillis()) {
        if (trackUri.isEmpty() || timestampMs <= 0L) return
        val snapshot: List<Entry> = synchronized(lock) {
            val map = entries()
            val old = map[trackUri]
            val updatedStamps = if (old == null) {
                listOf(timestampMs)
            } else {
                (old.timestamps + timestampMs).takeLast(MAX_TIMESTAMPS_PER_TRACK)
            }
            val nextCount = (old?.lifetimeCount ?: 0) + 1
            map[trackUri] = Entry(trackUri, nextCount, updatedStamps)
            if (map.size > MAX_TRACKED_SONGS) {
                trimExcessLocked(map)
            }
            ArrayList(map.values)
        }
        scheduleWrite(snapshot)
        onChanged()
    }

    /** Builds the 4 pinned system playlists and all available seasonal/monthly Recaps. */
    fun buildShowcase(
        favoriteTracks: List<Track>,
        allTracks: List<Track>,
        nowMs: Long = System.currentTimeMillis()
    ): FavoritesShowcase {
        val snapshot = snapshotEntries()
        val tracksByUri = allTracks.associateBy { it.uri }
        val pinnedIds = longArrayOf(ID_FAVORITES, ID_MOST_LISTENED_ALL, ID_MOST_LISTENED_WEEK, ID_MOST_LISTENED_MONTH)
        val pinned = pinnedIds.mapNotNull { buildPinnedDetails(it, favoriteTracks, snapshot, tracksByUri, nowMs)?.playlist }
        return FavoritesShowcase(
            pinned = pinned,
            recaps = buildRecapPlaylists(snapshot, tracksByUri, nowMs)
        )
    }

    /** Resolves a system or recap playlist by its negative [id], along with per-song listen stats. */
    fun resolveSystemPlaylist(
        id: Long,
        favoriteTracks: List<Track>,
        allTracks: List<Track>,
        nowMs: Long = System.currentTimeMillis()
    ): SystemPlaylistDetails? {
        if (id >= 0L) return null
        val snapshot = snapshotEntries()
        val tracksByUri = allTracks.associateBy { it.uri }
        return buildPinnedDetails(id, favoriteTracks, snapshot, tracksByUri, nowMs)
            ?: resolveRecapById(id, snapshot, tracksByUri)
    }

    private fun buildPinnedDetails(
        id: Long,
        favoriteTracks: List<Track>,
        snapshot: Map<String, Entry>,
        tracksByUri: Map<String, Track>,
        nowMs: Long
    ): SystemPlaylistDetails? {
        if (id == ID_FAVORITES) {
            val stats = HashMap<String, TrackListenStats>()
            for (track in favoriteTracks) {
                val count = snapshot[track.uri]?.lifetimeCount ?: 0
                if (count > 0) {
                    stats[track.uri] = TrackListenStats(track.uri, count, count.toLong() * effectiveDurationMs(track))
                }
            }
            val playlist = Playlist(ID_FAVORITES, appContext.getString(R.string.sys_playlist_favorites), favoriteTracks.map { it.uri })
            return SystemPlaylistDetails(playlist, stats)
        }
        val (titleRes, ranked) = when (id) {
            ID_MOST_LISTENED_ALL -> R.string.sys_playlist_most_listened to rankLifetime(snapshot, tracksByUri, TOP_LIMIT)
            ID_MOST_LISTENED_WEEK -> R.string.sys_playlist_most_week to rankWindow(snapshot, tracksByUri, nowMs - WEEK_MS, nowMs, TOP_LIMIT)
            ID_MOST_LISTENED_MONTH -> R.string.sys_playlist_most_month to rankWindow(snapshot, tracksByUri, nowMs - MONTH_MS, nowMs, TOP_LIMIT)
            else -> return null
        }
        return SystemPlaylistDetails(
            Playlist(id, appContext.getString(titleRes), ranked.map { it.uri }),
            ranked.associateBy { it.uri }
        )
    }

    private fun resolveRecapById(
        id: Long,
        snapshot: Map<String, Entry>,
        tracksByUri: Map<String, Track>
    ): SystemPlaylistDetails? {
        val seasonSpec = decodeSeasonRecapId(id)
        if (seasonSpec != null) {
            val (year, season) = seasonSpec
            val ranked = rankPeriod(snapshot, tracksByUri, TOP_LIMIT) { ts ->
                val ym = yearMonthOf(ts)
                seasonYearAndIndex(ym.first, ym.second) == (year to season)
            }
            val title = seasonRecapTitle(year, season)
            return SystemPlaylistDetails(
                Playlist(id, title, ranked.map { it.uri }),
                ranked.associateBy { it.uri }
            )
        }
        val monthSpec = decodeMonthRecapId(id)
        if (monthSpec != null) {
            val (year, month) = monthSpec
            val ranked = rankPeriod(snapshot, tracksByUri, TOP_LIMIT) { ts ->
                yearMonthOf(ts) == (year to month)
            }
            val title = monthRecapTitle(year, month)
            return SystemPlaylistDetails(
                Playlist(id, title, ranked.map { it.uri }),
                ranked.associateBy { it.uri }
            )
        }
        return null
    }

    private fun buildRecapPlaylists(
        snapshot: Map<String, Entry>,
        tracksByUri: Map<String, Track>,
        nowMs: Long
    ): List<Playlist> {
        val monthsSeen = HashSet<Pair<Int, Int>>()
        val seasonsSeen = HashSet<Pair<Int, Int>>()

        val (nowYear, nowMonth) = yearMonthOf(nowMs)
        monthsSeen.add(nowYear to nowMonth)
        seasonsSeen.add(seasonYearAndIndex(nowYear, nowMonth))

        for ((uri, entry) in snapshot) {
            if (uri !in tracksByUri) continue
            for (ts in entry.timestamps) {
                val (y, m) = yearMonthOf(ts)
                monthsSeen.add(y to m)
                seasonsSeen.add(seasonYearAndIndex(y, m))
            }
        }

        val recaps = ArrayList<Playlist>()

        val sortedSeasons = seasonsSeen.sortedWith(
            compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second }
        )
        for ((year, season) in sortedSeasons.take(MAX_SEASON_RECAPS)) {
            val ranked = rankPeriod(snapshot, tracksByUri, TOP_LIMIT) { ts ->
                val (y, m) = yearMonthOf(ts)
                seasonYearAndIndex(y, m) == (year to season)
            }
            if (ranked.isNotEmpty() || (year to season) == seasonYearAndIndex(nowYear, nowMonth)) {
                recaps.add(
                    Playlist(
                        id = encodeSeasonRecapId(year, season),
                        name = seasonRecapTitle(year, season),
                        trackUris = ranked.map { it.uri }
                    )
                )
            }
        }

        val sortedMonths = monthsSeen.sortedWith(
            compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second }
        )
        for ((year, month) in sortedMonths.take(MAX_MONTH_RECAPS)) {
            val ranked = rankPeriod(snapshot, tracksByUri, TOP_LIMIT) { ts ->
                yearMonthOf(ts) == (year to month)
            }
            if (ranked.isNotEmpty() || (year == nowYear && month == nowMonth)) {
                recaps.add(
                    Playlist(
                        id = encodeMonthRecapId(year, month),
                        name = monthRecapTitle(year, month),
                        trackUris = ranked.map { it.uri }
                    )
                )
            }
        }

        return recaps
    }

    private fun seasonRecapTitle(year: Int, season: Int): String {
        val res = when (season) {
            SEASON_WINTER -> R.string.recap_winter
            SEASON_SPRING -> R.string.recap_spring
            SEASON_SUMMER -> R.string.recap_summer
            else -> R.string.recap_autumn
        }
        return appContext.getString(res, year)
    }

    private fun monthRecapTitle(year: Int, month: Int): String {
        val months = DateFormatSymbols.getInstance(Locale.getDefault()).months
        val monthName = if (month in months.indices && months[month].isNotEmpty()) {
            months[month]
        } else {
            FALLBACK_MONTHS[month.coerceIn(0, 11)]
        }
        return appContext.getString(R.string.recap_month_format, monthName, year)
    }

    private fun snapshotEntries(): Map<String, Entry> {
        return synchronized(lock) { HashMap(entries()) }
    }

    /** Must be called with [lock] held. */
    private fun entries(): MutableMap<String, Entry> {
        loaded?.let { return it }
        val read = read()
        loaded = read
        return read
    }

    private fun trimExcessLocked(map: MutableMap<String, Entry>) {
        val victim = map.values.minByOrNull { entry ->
            entry.timestamps.maxOrNull() ?: 0L
        }
        if (victim != null) map.remove(victim.uri)
    }

    private fun scheduleWrite(snapshot: List<Entry>) {
        try {
            writer.execute { write(snapshot) }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "history writer unavailable, saving inline: ${e.message}")
            write(snapshot)
        }
    }

    private fun read(): MutableMap<String, Entry> {
        if (!file.baseFile.exists()) return LinkedHashMap()
        return try {
            parse(String(file.readFully(), Charsets.UTF_8))
        } catch (e: IOException) {
            Log.w(TAG, "cannot read listening history: ${e.message}")
            LinkedHashMap()
        } catch (e: JSONException) {
            Log.w(TAG, "listening history file damaged: ${e.message}")
            LinkedHashMap()
        }
    }

    private fun parse(text: String): MutableMap<String, Entry> {
        val root = JSONObject(text)
        val array = root.optJSONArray(KEY_ENTRIES) ?: return LinkedHashMap()
        val count = minOf(array.length(), MAX_TRACKED_SONGS)
        val map = LinkedHashMap<String, Entry>(count)
        for (i in 0 until count) {
            val obj = array.optJSONObject(i) ?: continue
            val uri = obj.optString("u")
            if (uri.isEmpty()) continue
            val lifetime = obj.optInt("c", 0).coerceAtLeast(0)
            val tsArray = obj.optJSONArray("t")
            val stamps = if (tsArray == null) {
                emptyList()
            } else {
                val limit = minOf(tsArray.length(), MAX_TIMESTAMPS_PER_TRACK)
                val list = ArrayList<Long>(limit)
                for (j in 0 until limit) {
                    val ts = tsArray.optLong(j, 0L)
                    if (ts > 0L) list.add(ts)
                }
                list
            }
            val effectiveLifetime = maxOf(lifetime, stamps.size)
            if (effectiveLifetime > 0) {
                map[uri] = Entry(uri, effectiveLifetime, stamps)
            }
        }
        return map
    }

    private fun write(entries: List<Entry>) {
        var stream: FileOutputStream? = null
        try {
            val bytes = serialise(entries).toByteArray(Charsets.UTF_8)
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            Log.w(TAG, "cannot save listening history: ${e.message}")
        } catch (e: JSONException) {
            file.failWrite(stream)
            Log.w(TAG, "cannot encode listening history: ${e.message}")
        }
    }

    private fun serialise(entries: List<Entry>): String {
        val array = JSONArray()
        for (entry in entries.take(MAX_TRACKED_SONGS)) {
            val stamps = JSONArray()
            for (ts in entry.timestamps.takeLast(MAX_TIMESTAMPS_PER_TRACK)) {
                stamps.put(ts)
            }
            array.put(
                JSONObject()
                    .put("u", entry.uri)
                    .put("c", entry.lifetimeCount)
                    .put("t", stamps)
            )
        }
        return JSONObject().put(KEY_VERSION, VERSION).put(KEY_ENTRIES, array).toString()
    }

    companion object {
        private const val TAG = "ListeningHistoryStore"
        private const val FILE_NAME = "listening_history.json"
        private const val VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_TRACKED_SONGS = 10_000
        private const val MAX_TIMESTAMPS_PER_TRACK = 1_000
        private const val MAX_SEASON_RECAPS = 8
        private const val MAX_MONTH_RECAPS = 12

        const val TOP_LIMIT = 100
        const val WEEK_MS = 7L * 24L * 60L * 60L * 1000L
        const val MONTH_MS = 30L * 24L * 60L * 60L * 1000L
        const val FALLBACK_DURATION_MS = 180_000L
        const val MIN_HALF_LISTEN_MS = 5_000L

        const val ID_FAVORITES = -1L
        const val ID_MOST_LISTENED_ALL = -2L
        const val ID_MOST_LISTENED_WEEK = -3L
        const val ID_MOST_LISTENED_MONTH = -4L

        private const val MONTH_RECAP_BASE = -1_000_000L
        private const val SEASON_RECAP_BASE = -2_000_000L

        const val SEASON_WINTER = 0
        const val SEASON_SPRING = 1
        const val SEASON_SUMMER = 2
        const val SEASON_AUTUMN = 3

        private val FALLBACK_MONTHS = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )

        fun isSystemPlaylist(id: Long): Boolean = id < 0L

        /** Minimum active listening time required to count as a listen: at least half the song. */
        fun requiredListenMs(durationMs: Long): Long {
            if (durationMs <= 0L) return 30_000L
            return maxOf(MIN_HALF_LISTEN_MS, durationMs / 2L)
        }

        fun effectiveDurationMs(track: Track): Long {
            return if (track.durationMs > 0L) track.durationMs else FALLBACK_DURATION_MS
        }

        /**
         * Ranks tracks by total lifetime time listened (`lifetimeCount * durationMs`) descending,
         * breaking ties by play count and title.
         */
        fun rankLifetime(
            entries: Map<String, Entry>,
            tracksByUri: Map<String, Track>,
            limit: Int = TOP_LIMIT
        ): List<TrackListenStats> {
            return rankByCount(entries, tracksByUri, limit) { it.lifetimeCount }
        }

        /**
         * Ranks tracks within `[startMs, endMs]` by time listened (`windowCount * durationMs`) descending.
         */
        fun rankWindow(
            entries: Map<String, Entry>,
            tracksByUri: Map<String, Track>,
            startMs: Long,
            endMs: Long,
            limit: Int = TOP_LIMIT
        ): List<TrackListenStats> {
            return rankPeriod(entries, tracksByUri, limit) { ts -> ts in startMs..endMs }
        }

        /**
         * Ranks tracks whose timestamps match [predicate] by time listened (`count * durationMs`) descending.
         */
        fun rankPeriod(
            entries: Map<String, Entry>,
            tracksByUri: Map<String, Track>,
            limit: Int = TOP_LIMIT,
            predicate: (Long) -> Boolean
        ): List<TrackListenStats> {
            return rankByCount(entries, tracksByUri, limit) { entry ->
                entry.timestamps.count(predicate)
            }
        }

        private inline fun rankByCount(
            entries: Map<String, Entry>,
            tracksByUri: Map<String, Track>,
            limit: Int,
            countOf: (Entry) -> Int
        ): List<TrackListenStats> {
            val stats = ArrayList<TrackListenStats>()
            for ((uri, entry) in entries) {
                val track = tracksByUri[uri] ?: continue
                val count = countOf(entry)
                if (count > 0) {
                    stats.add(TrackListenStats(uri, count, count.toLong() * effectiveDurationMs(track)))
                }
            }
            stats.sortWith(
                compareByDescending<TrackListenStats> { it.listenedMs }
                    .thenByDescending { it.playCount }
                    .thenBy { tracksByUri[it.uri]?.title.orEmpty() }
            )
            return stats.take(limit)
        }

        fun yearMonthOf(timestampMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Pair<Int, Int> {
            val cal = Calendar.getInstance(timeZone)
            cal.timeInMillis = timestampMs
            return cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH)
        }

        /**
         * Maps a (year, 0-based month) to its meteorological season:
         * Spring = Mar-May, Summer = Jun-Aug, Autumn = Sep-Nov, Winter = Dec-Feb.
         */
        fun seasonYearAndIndex(year: Int, month: Int): Pair<Int, Int> {
            return when (month) {
                Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> year to SEASON_SPRING
                Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> year to SEASON_SUMMER
                Calendar.SEPTEMBER, Calendar.OCTOBER, Calendar.NOVEMBER -> year to SEASON_AUTUMN
                Calendar.DECEMBER -> year to SEASON_WINTER
                else -> year to SEASON_WINTER
            }
        }

        fun encodeMonthRecapId(year: Int, month: Int): Long {
            return MONTH_RECAP_BASE - (year.toLong() * 100L + month.coerceIn(0, 11).toLong())
        }

        fun decodeMonthRecapId(id: Long): Pair<Int, Int>? {
            if (id > MONTH_RECAP_BASE || id <= SEASON_RECAP_BASE) return null
            val code = (MONTH_RECAP_BASE - id).toInt()
            val year = code / 100
            val month = code % 100
            if (year !in 1970..9999 || month !in 0..11) return null
            return year to month
        }

        fun encodeSeasonRecapId(year: Int, season: Int): Long {
            return SEASON_RECAP_BASE - (year.toLong() * 10L + season.coerceIn(0, 3).toLong())
        }

        fun decodeSeasonRecapId(id: Long): Pair<Int, Int>? {
            if (id > SEASON_RECAP_BASE) return null
            val code = (SEASON_RECAP_BASE - id).toInt()
            val year = code / 10
            val season = code % 10
            if (year !in 1970..9999 || season !in 0..3) return null
            return year to season
        }
    }
}
