package me.Integer.Mediaplayer.library

import java.util.Locale

/**
 * Encodes and decodes standard `.m3u` / `.m3u8` playlist files, with a round-trip
 * `#EXTMIX:` directive so AI DJ Mashup playlists preserve their entry/exit cue points,
 * crossfade length, tempo ratio, and loudness gain.
 * Pure Kotlin.
 */
object M3uCodec {

    data class DecodedPlaylist(
        val name: String,
        val tracks: List<Track>,
        val segments: List<MixSegment>
    )

    private const val HEADER = "#EXTM3U"
    private const val PREFIX_PLAYLIST = "#PLAYLIST:"
    private const val PREFIX_EXTINF = "#EXTINF:"
    private const val PREFIX_EXTMIX = "#EXTMIX:"
    private const val MAX_LINES = 20000

    /** Encodes [playlist] into UTF-8 M3U8 text. */
    fun encode(
        playlist: Playlist,
        trackOf: (String) -> Track?,
        artistOf: (Track) -> String
    ): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        sb.append(PREFIX_PLAYLIST).append(playlist.name).append('\n')
        if (playlist.isMix) {
            for (seg in playlist.segments) {
                val track = trackOf(seg.trackUri) ?: continue
                appendExtInf(sb, track, artistOf(track))
                sb.append(PREFIX_EXTMIX)
                    .append("start=").append(seg.startMs)
                    .append(",end=").append(seg.endMs)
                    .append(",fade=").append(seg.crossfadeMs)
                    .append(",tempo=").append(String.format(Locale.US, "%.4f", seg.tempoRatio))
                    .append(",gain=").append(String.format(Locale.US, "%.2f", seg.gainDb))
                    .append('\n')
                sb.append(track.fileName.ifEmpty { track.title + ".mp3" }).append('\n')
            }
        } else {
            for (uri in playlist.trackUris) {
                val track = trackOf(uri) ?: continue
                appendExtInf(sb, track, artistOf(track))
                sb.append(track.fileName.ifEmpty { track.title + ".mp3" }).append('\n')
            }
        }
        return sb.toString()
    }

    private fun appendExtInf(sb: StringBuilder, track: Track, artist: String) {
        val sec = if (track.durationMs > 0L) track.durationMs / 1000L else -1L
        val label = if (artist.isNotEmpty()) "$artist - ${track.title}" else track.title
        sb.append(PREFIX_EXTINF).append(sec).append(',').append(label).append('\n')
    }

    /** Matches an `.m3u` / `.m3u8` file against [library] tracks. */
    fun decode(text: String, fallbackName: String, library: List<Track>): DecodedPlaylist {
        var name = Playlist.cleanName(fallbackName).ifEmpty { "Imported Playlist" }
        val byFileLower = library.associateBy { it.fileName.lowercase(Locale.ROOT) }
        val matchedTracks = ArrayList<Track>()
        val matchedSegments = ArrayList<MixSegment>()

        var pendingInfoTitle = ""
        var pendingInfoArtist = ""
        var pendingMixProps: Map<String, String>? = null
        var hasMixDirectives = false

        for (rawLine in text.lineSequence().take(MAX_LINES)) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.equals(HEADER, ignoreCase = true)) continue
            if (line.startsWith(PREFIX_PLAYLIST, ignoreCase = true)) {
                val parsedName = Playlist.cleanName(line.substring(PREFIX_PLAYLIST.length))
                if (parsedName.isNotEmpty()) name = parsedName
                continue
            }
            if (line.startsWith(PREFIX_EXTINF, ignoreCase = true)) {
                val comma = line.indexOf(',')
                if (comma >= 0) {
                    val display = line.substring(comma + 1).trim()
                    val dash = display.indexOf(" - ")
                    if (dash > 0) {
                        pendingInfoArtist = display.substring(0, dash).trim()
                        pendingInfoTitle = display.substring(dash + 3).trim()
                    } else {
                        pendingInfoArtist = ""
                        pendingInfoTitle = display
                    }
                }
                continue
            }
            if (line.startsWith(PREFIX_EXTMIX, ignoreCase = true)) {
                pendingMixProps = parseKeyValues(line.substring(PREFIX_EXTMIX.length))
                hasMixDirectives = true
                continue
            }
            if (line.startsWith("#")) continue

            val filePart = line.substringAfterLast('/').substringAfterLast('\\').trim()
            val track = byFileLower[filePart.lowercase(Locale.ROOT)]
                ?: (if (pendingInfoTitle.isNotEmpty()) TrackMatcher.match(pendingInfoTitle, pendingInfoArtist, library) else null)
                ?: matchFromFileName(filePart, library)

            if (track != null) {
                matchedTracks.add(track)
                val props = pendingMixProps
                val seg = if (props != null) {
                    MixSegment.create(
                        trackUri = track.uri,
                        startMs = props["start"]?.toLongOrNull() ?: 0L,
                        endMs = props["end"]?.toLongOrNull() ?: MixSegment.END_OF_TRACK,
                        crossfadeMs = props["fade"]?.toLongOrNull() ?: MixSegment.DEFAULT_CROSSFADE_MS,
                        durationMs = track.durationMs,
                        tempoRatio = props["tempo"]?.toFloatOrNull() ?: 1f,
                        gainDb = props["gain"]?.toFloatOrNull() ?: 0f
                    )
                } else {
                    MixSegment.create(
                        trackUri = track.uri,
                        startMs = 0L,
                        endMs = MixSegment.END_OF_TRACK,
                        crossfadeMs = MixSegment.DEFAULT_CROSSFADE_MS,
                        durationMs = track.durationMs
                    )
                }
                if (seg != null) matchedSegments.add(seg)
            }
            pendingInfoTitle = ""
            pendingInfoArtist = ""
            pendingMixProps = null
        }

        val finalSegments = if (hasMixDirectives && matchedSegments.size >= 2) matchedSegments else emptyList()
        return DecodedPlaylist(name, matchedTracks.distinctBy { it.uri }, finalSegments)
    }

    private fun matchFromFileName(fileName: String, library: List<Track>): Track? {
        val guess = TitleGuesser.fromFileName(fileName)
        if (guess.title.isEmpty()) return null
        return TrackMatcher.match(guess.title, guess.artistTag, library)
    }

    private fun parseKeyValues(spec: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (part in spec.split(',')) {
            val eq = part.indexOf('=')
            if (eq > 0) {
                val k = part.substring(0, eq).trim().lowercase(Locale.ROOT)
                val v = part.substring(eq + 1).trim()
                map[k] = v
            }
        }
        return map
    }
}
