package me.Integer.Mediaplayer.library

import java.util.Locale

/**
 * Turns mashup routes into [MixSegment] lists.
 *
 * The compact form is 'Song A,1:45,Song B,0:33,Song B,2:45,Song C,0:15': leave Song A at
 * 1:45 and enter Song B at 0:33, then leave Song B at 2:45 and enter Song C at 0:15.
 * The structured form is the list of steps the server sends. Pure Kotlin, no Android types.
 */
object MashupRouteParser {

    /** One 'title,time' pair of the compact form. */
    data class Hop(val title: String, val timeMs: Long)

    /** One step of the structured form; [trackId] is whatever the caller can look up. */
    data class Step(
        val trackId: String,
        val startMs: Long,
        val endMs: Long,
        val crossfadeMs: Long,
        val tempoRatio: Float = 1f,
        val gainDb: Float = 0f
    )

    const val MAX_ROUTE_CHARS = 20000
    const val MAX_STEPS = 200
    private const val MIN_STEPS = 2
    private const val MAX_TOKENS = 4000
    private const val MAX_LEADING_DIGITS = 3
    private const val UNKNOWN_TITLE = "?"

    /** 'm:ss' or 'h:mm:ss' in milliseconds; null for anything else. */
    fun parseTime(raw: String?): Long? {
        val parts = raw?.trim().orEmpty().split(':')
        if (parts.size !in 2..3) return null
        var total = 0L
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
            if (index == 0 && part.length > MAX_LEADING_DIGITS) return null
            if (index > 0 && part.length != 2) return null
            val value = part.toLong()
            if (index > 0 && value >= 60L) return null
            total = total * 60L + value
        }
        return total * 1000L
    }

    /**
     * Splits the compact form into hops. A title may contain commas: everything up to the
     * next time is the title.
     *
     * @return null when the text is not a complete route
     */
    fun parseHops(route: String?): List<Hop>? {
        val text = route?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_ROUTE_CHARS) return null
        val tokens = text.split(',')
        if (tokens.size > MAX_TOKENS) return null
        val hops = ArrayList<Hop>()
        val title = StringBuilder()
        for (token in tokens) {
            val time = parseTime(token)
            if (time == null || title.isBlank()) {
                if (title.isNotEmpty()) title.append(',')
                title.append(token)
                continue
            }
            hops.add(Hop(title.toString().trim(), time))
            title.setLength(0)
        }
        if (title.isNotBlank()) return null
        return if (isHopCountValid(hops.size)) hops else null
    }

    /**
     * Hops come in pairs (leave one song, enter the next), so n pairs describe n + 1 steps.
     * The first step starts at 0:00 and the last one plays to the end of its song.
     *
     * @param trackOf finds a song by the title written in the route
     * @return null when a song is missing or a time is impossible
     */
    fun segmentsOfHops(hops: List<Hop>, trackOf: (String) -> Track?): List<MixSegment>? {
        if (!isHopCountValid(hops.size)) return null
        val stepCount = hops.size / 2 + 1
        val segments = ArrayList<MixSegment>(stepCount)
        for (index in 0 until stepCount) {
            val entry = if (index == 0) null else hops[2 * index - 1]
            val exit = hops.getOrNull(2 * index)
            val segment = segmentOfHops(entry, exit, trackOf) ?: return null
            segments.add(segment)
        }
        return segments
    }

    private fun segmentOfHops(entry: Hop?, exit: Hop?, trackOf: (String) -> Track?): MixSegment? {
        val title = entry?.title ?: exit?.title ?: return null
        if (entry != null && exit != null && normalise(entry.title) != normalise(exit.title)) return null
        val startMs = entry?.timeMs ?: 0L
        if (exit != null && exit.timeMs <= startMs) return null
        val track = trackOf(title) ?: return null
        val endMs = exit?.timeMs ?: MixSegment.END_OF_TRACK
        return MixSegment.create(track.uri, startMs, endMs, MixSegment.DEFAULT_CROSSFADE_MS, track.durationMs)
    }

    /** Parses the compact form against [tracks], matching titles without regard to case. */
    fun parse(route: String?, tracks: List<Track>): List<MixSegment>? {
        val hops = parseHops(route) ?: return null
        val byTitle = HashMap<String, Track>()
        for (track in tracks) {
            val key = normalise(track.title)
            if (key.isNotEmpty() && key !in byTitle) byTitle[key] = track
        }
        return segmentsOfHops(hops) { byTitle[normalise(it)] }
    }

    /**
     * Converts the structured form. The last step always plays to the end of its song.
     *
     * @return null when a song is missing or a time is impossible
     */
    fun segmentsOfSteps(steps: List<Step>, trackOf: (String) -> Track?): List<MixSegment>? {
        if (steps.size !in MIN_STEPS..MAX_STEPS) return null
        val segments = ArrayList<MixSegment>(steps.size)
        for ((index, step) in steps.withIndex()) {
            val track = trackOf(step.trackId) ?: return null
            val last = index == steps.size - 1
            if (!last && step.endMs <= step.startMs) return null
            val endMs = if (last) MixSegment.END_OF_TRACK else step.endMs
            val segment = MixSegment.create(
                trackUri = track.uri,
                startMs = step.startMs,
                endMs = endMs,
                crossfadeMs = step.crossfadeMs,
                durationMs = track.durationMs,
                tempoRatio = step.tempoRatio,
                gainDb = step.gainDb
            ) ?: return null
            segments.add(segment)
        }
        return segments
    }

    /** The compact form of [segments]; [trackOf] finds a song by its URI. */
    fun format(segments: List<MixSegment>, trackOf: (String) -> Track?): String {
        val count = minOf(segments.size, MAX_STEPS)
        val parts = ArrayList<String>()
        for (index in 0 until count - 1) {
            val current = segments[index]
            val next = segments[index + 1]
            val currentTrack = trackOf(current.trackUri)
            val exitMs = if (current.playsToEnd) currentTrack?.durationMs ?: 0L else current.endMs
            parts.add(currentTrack?.title ?: UNKNOWN_TITLE)
            parts.add(MixSegment.formatTime(exitMs))
            parts.add(trackOf(next.trackUri)?.title ?: UNKNOWN_TITLE)
            parts.add(MixSegment.formatTime(next.startMs))
        }
        return parts.joinToString(",")
    }

    private fun isHopCountValid(count: Int): Boolean {
        return count >= 2 && count % 2 == 0 && count <= (MAX_STEPS - 1) * 2
    }

    private fun normalise(title: String): String {
        return title.trim().lowercase(Locale.ROOT)
    }
}
