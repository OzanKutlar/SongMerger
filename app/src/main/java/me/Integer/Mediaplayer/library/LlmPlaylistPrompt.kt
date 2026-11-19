package me.Integer.Mediaplayer.library

/**
 * Builds an instruction set asking an LLM chat interface to research the genre and mood of
 * each song on the user's device and output named JSON playlists (without emojis), and parses
 * the returned JSON playlists back into local [Track] lists.
 */
object LlmPlaylistPrompt {

    const val AUTO_PLAYLIST_COUNT = 0
    const val MAX_PLAYLIST_COUNT = 15
    const val MAX_PROMPT_TRACKS = 1500
    const val MAX_PROMPT_CHARS = 240_000

    private const val MAX_INPUT_CHARS = 250_000
    private const val MAX_NAME_SCAN_CHARS = 300
    private const val MAX_JSON_DEPTH = 8
    private const val MAX_JSON_MEMBERS = 2000
    private const val MAX_PARSED_PLAYLISTS = 50

    data class PromptResult(
        val prompt: String,
        val includedTracks: Int,
        val totalTracks: Int
    ) {
        val truncated: Boolean
            get() = includedTracks < totalTracks
    }

    data class SuggestedEntry(
        val title: String,
        val artist: String,
        val matchedTrack: Track?
    )

    data class SuggestedPlaylist(
        val name: String,
        val entries: List<SuggestedEntry>,
        val tracks: List<Track>
    )

    /**
     * Generates the full LLM chat prompt for [tracks].
     * When [playlistCount] is [AUTO_PLAYLIST_COUNT] (or out of range), the LLM is asked to
     * create 3 to 6 playlists.
     */
    fun buildPrompt(
        tracks: List<Track>,
        artistOf: (Track) -> String = { it.artistTag },
        playlistCount: Int = AUTO_PLAYLIST_COUNT
    ): PromptResult {
        if (tracks.isEmpty()) return PromptResult("", 0, 0)
        val countInstruction = if (playlistCount in 1..MAX_PLAYLIST_COUNT) {
            "Create exactly $playlistCount cohesive playlists."
        } else {
            "Create 3 to 6 cohesive playlists depending on the variety of the library."
        }
        val header = buildHeader(countInstruction)
        val builder = StringBuilder(header.length + minOf(tracks.size * 64, MAX_PROMPT_CHARS))
        builder.append(header)

        val maxTracks = minOf(tracks.size, MAX_PROMPT_TRACKS)
        var included = 0
        for (index in 0 until maxTracks) {
            val line = formatTrackLine(index + 1, tracks[index], artistOf)
            if (builder.length + line.length > MAX_PROMPT_CHARS) break
            builder.append(line)
            included++
        }
        return PromptResult(
            prompt = builder.toString().trimEnd(),
            includedTracks = included,
            totalTracks = tracks.size
        )
    }

    private fun buildHeader(countInstruction: String): String {
        return """
You are a music curator and playlist designer. Below is a list of songs from my device.

Instructions:
1. Research and identify the genre, release era, and mood/vibe of each song in the list.
2. Group the songs into themed playlists based on genre, mood, era, or artist showcase. $countInstruction
3. Give each playlist a natural, creative name such as 'A Streak through the 90s' or 'Best of Daft Punk'.
4. Do NOT use any emojis, emoticons, or decorative symbols in the playlist names. Use plain text only.
5. Only use songs from the provided list, copying each song's "title" and "artist" fields verbatim so they can be matched back to my library.
6. Output your answer as a few JSON objects (one JSON object per playlist) using this exact structure:

{
  "name": "A Streak through the 90s",
  "tracks": [
    { "title": "Song Title", "artist": "Artist Name" }
  ]
}

Songs on my device:
""".trimStart()
    }

    private fun formatTrackLine(number: Int, track: Track, artistOf: (Track) -> String): String {
        val title = sanitizeInline(track.title).ifEmpty { sanitizeInline(track.fileName) }
        val artist = sanitizeInline(artistOf(track)).ifEmpty { "Unknown Artist" }
        val album = sanitizeInline(track.album)
        return if (album.isNotEmpty()) {
            "$number. \"$title\" — $artist [Album: $album]\n"
        } else {
            "$number. \"$title\" — $artist\n"
        }
    }

    private fun sanitizeInline(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.replace('\n', ' ').replace('\r', ' ').trim()
    }

    /**
     * Removes emojis, pictographs, variation selectors, and symbol characters from [raw]
     * while preserving letters, numbers, standard punctuation, and spaces.
     */
    fun stripEmojis(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val limit = minOf(raw.length, MAX_NAME_SCAN_CHARS)
        val out = StringBuilder(limit)
        var index = 0
        for (step in 0 until limit) {
            if (index >= limit) break
            val cp = Character.codePointAt(raw, index)
            val charCount = Character.charCount(cp)
            index += charCount
            if (!isEmojiOrPictograph(cp)) {
                out.appendCodePoint(cp)
            }
        }
        return out.toString().replace( MULTI_SPACE, " ").trim()
    }

    private fun isEmojiOrPictograph(cp: Int): Boolean {
        if (cp in 0x1F000..0x1FAFF) return true
        if (cp in 0x2600..0x27BF) return true
        if (cp in 0x2300..0x23FF) return true
        if (cp in 0x2B00..0x2BFF) return true
        if (cp in 0xFE00..0xFE0F) return true
        if (cp in 0xE0020..0xE007F) return true
        if (cp == 0x200D || cp == 0x20E3) return true
        val type = Character.getType(cp)
        return type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt()
    }

    // ------------------------------------------------------------------ JSON parsing

    /**
     * Extracts one or more playlists from raw LLM output (which may contain multiple standalone
     * JSON objects, markdown code blocks, a JSON array, or a `{"playlists": [...]}` wrapper)
     * and matches their tracks against [libraryTracks].
     */
    fun parsePlaylists(rawText: String?, libraryTracks: List<Track>): List<SuggestedPlaylist> {
        if (rawText.isNullOrBlank()) return emptyList()
        val limit = minOf(rawText.length, MAX_INPUT_CHARS)
        val cursor = JsonCursor(rawText, limit)
        val rawObjects = ArrayList<JsonVal.Obj>()

        for (step in 0 until limit) {
            if (cursor.pos >= limit || rawObjects.size >= MAX_PARSED_PLAYLISTS) break
            val ch = rawText[cursor.pos]
            if (ch == '{' || ch == '[') {
                val startPos = cursor.pos
                val parsed = parseValue(cursor, 0)
                if (parsed != null) {
                    collectPlaylistObjects(parsed, rawObjects)
                } else {
                    cursor.pos = startPos + 1
                }
            } else {
                cursor.pos++
            }
        }

        val results = ArrayList<SuggestedPlaylist>(rawObjects.size)
        for (obj in rawObjects) {
            val playlist = toSuggestedPlaylist(obj, libraryTracks)
            if (playlist != null) results.add(playlist)
        }
        return results
    }

    private fun collectPlaylistObjects(value: JsonVal, out: MutableList<JsonVal.Obj>) {
        if (out.size >= MAX_PARSED_PLAYLISTS) return
        when (value) {
            is JsonVal.Obj -> {
                val nested = value.fields["playlists"]
                if (nested is JsonVal.Arr) {
                    for (item in nested.items) collectPlaylistObjects(item, out)
                } else if (hasPlaylistShape(value)) {
                    out.add(value)
                }
            }
            is JsonVal.Arr -> {
                for (item in value.items) collectPlaylistObjects(item, out)
            }
            else -> Unit
        }
    }

    private fun hasPlaylistShape(obj: JsonVal.Obj): Boolean {
        val name = (obj.fields["name"] ?: obj.fields["playlist"] ?: obj.fields["title"]) as? JsonVal.Str
        val tracks = (obj.fields["tracks"] ?: obj.fields["songs"] ?: obj.fields["items"]) as? JsonVal.Arr
        return name != null && tracks != null
    }

    private fun toSuggestedPlaylist(obj: JsonVal.Obj, libraryTracks: List<Track>): SuggestedPlaylist? {
        val rawName = ((obj.fields["name"] ?: obj.fields["playlist"] ?: obj.fields["title"]) as? JsonVal.Str)?.value
        val cleanName = Playlist.cleanName(stripEmojis(rawName))
        if (cleanName.isEmpty()) return null
        val tracksArr = (obj.fields["tracks"] ?: obj.fields["songs"] ?: obj.fields["items"]) as? JsonVal.Arr
            ?: return null

        val entries = ArrayList<SuggestedEntry>(tracksArr.items.size)
        val matchedTracks = ArrayList<Track>(tracksArr.items.size)
        val seenUris = HashSet<String>()

        for (item in tracksArr.items) {
            val entry = parseTrackEntry(item, libraryTracks) ?: continue
            entries.add(entry)
            val track = entry.matchedTrack
            if (track != null && seenUris.add(track.uri)) {
                matchedTracks.add(track)
            }
        }
        if (entries.isEmpty()) return null
        return SuggestedPlaylist(cleanName, entries, matchedTracks)
    }

    private fun parseTrackEntry(item: JsonVal, libraryTracks: List<Track>): SuggestedEntry? {
        return when (item) {
            is JsonVal.Obj -> {
                val title = ((item.fields["title"] ?: item.fields["song"] ?: item.fields["name"]) as? JsonVal.Str)
                    ?.value?.trim().orEmpty()
                val artist = ((item.fields["artist"] ?: item.fields["artists"]) as? JsonVal.Str)
                    ?.value?.trim().orEmpty()
                if (title.isEmpty()) return null
                val matched = TrackMatcher.match(title, artist, libraryTracks)
                SuggestedEntry(title, artist, matched)
            }
            is JsonVal.Str -> {
                val raw = item.value.trim()
                if (raw.isEmpty()) return null
                val parts = raw.split(DASH_SPLIT, limit = 2)
                val title = parts[0].trim()
                val artist = if (parts.size == 2) parts[1].trim() else ""
                val matched = TrackMatcher.match(raw, artist, libraryTracks)
                SuggestedEntry(title, artist, matched)
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ minimal bounded JSON reader

    private sealed class JsonVal {
        data class Obj(val fields: Map<String, JsonVal>) : JsonVal()
        data class Arr(val items: List<JsonVal>) : JsonVal()
        data class Str(val value: String) : JsonVal()
        object Other : JsonVal()
    }

    private class JsonCursor(val text: String, val limit: Int) {
        var pos: Int = 0
    }

    private fun skipWhitespace(cursor: JsonCursor) {
        val maxSteps = cursor.limit - cursor.pos
        for (step in 0 until maxSteps) {
            if (cursor.pos >= cursor.limit) break
            val c = cursor.text[cursor.pos]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                cursor.pos++
            } else {
                break
            }
        }
    }

    private fun parseValue(cursor: JsonCursor, depth: Int): JsonVal? {
        if (depth > MAX_JSON_DEPTH) return null
        skipWhitespace(cursor)
        if (cursor.pos >= cursor.limit) return null
        return when (cursor.text[cursor.pos]) {
            '{' -> parseObject(cursor, depth + 1)
            '[' -> parseArray(cursor, depth + 1)
            '"' -> parseString(cursor)?.let { JsonVal.Str(it) }
            else -> parsePrimitive(cursor)
        }
    }

    private fun parseObject(cursor: JsonCursor, depth: Int): JsonVal.Obj? {
        if (cursor.pos >= cursor.limit || cursor.text[cursor.pos] != '{') return null
        cursor.pos++
        val map = LinkedHashMap<String, JsonVal>()
        for (member in 0 until MAX_JSON_MEMBERS) {
            skipWhitespace(cursor)
            if (cursor.pos >= cursor.limit) return null
            if (cursor.text[cursor.pos] == '}') {
                cursor.pos++
                return JsonVal.Obj(map)
            }
            val key = parseString(cursor) ?: return null
            skipWhitespace(cursor)
            if (cursor.pos >= cursor.limit || cursor.text[cursor.pos] != ':') return null
            cursor.pos++
            val value = parseValue(cursor, depth) ?: return null
            map[key] = value
            skipWhitespace(cursor)
            if (cursor.pos >= cursor.limit) return null
            when (cursor.text[cursor.pos]) {
                ',' -> {
                    cursor.pos++
                    skipWhitespace(cursor)
                    if (cursor.pos < cursor.limit && cursor.text[cursor.pos] == '}') {
                        cursor.pos++
                        return JsonVal.Obj(map)
                    }
                }
                '}' -> {
                    cursor.pos++
                    return JsonVal.Obj(map)
                }
                else -> return null
            }
        }
        return null
    }

    private fun parseArray(cursor: JsonCursor, depth: Int): JsonVal.Arr? {
        if (cursor.pos >= cursor.limit || cursor.text[cursor.pos] != '[') return null
        cursor.pos++
        val list = ArrayList<JsonVal>()
        for (index in 0 until MAX_JSON_MEMBERS) {
            skipWhitespace(cursor)
            if (cursor.pos >= cursor.limit) return null
            if (cursor.text[cursor.pos] == ']') {
                cursor.pos++
                return JsonVal.Arr(list)
            }
            val item = parseValue(cursor, depth) ?: return null
            list.add(item)
            skipWhitespace(cursor)
            if (cursor.pos >= cursor.limit) return null
            when (cursor.text[cursor.pos]) {
                ',' -> {
                    cursor.pos++
                    skipWhitespace(cursor)
                    if (cursor.pos < cursor.limit && cursor.text[cursor.pos] == ']') {
                        cursor.pos++
                        return JsonVal.Arr(list)
                    }
                }
                ']' -> {
                    cursor.pos++
                    return JsonVal.Arr(list)
                }
                else -> return null
            }
        }
        return null
    }

    private fun parseString(cursor: JsonCursor): String? {
        skipWhitespace(cursor)
        if (cursor.pos >= cursor.limit || cursor.text[cursor.pos] != '"') return null
        cursor.pos++
        val out = StringBuilder()
        val maxChars = cursor.limit - cursor.pos
        for (step in 0 until maxChars) {
            if (cursor.pos >= cursor.limit) return null
            val c = cursor.text[cursor.pos++]
            if (c == '"') return out.toString()
            if (c == '\\') {
                if (cursor.pos >= cursor.limit) return null
                when (val esc = cursor.text[cursor.pos++]) {
                    '"', '\\', '/' -> out.append(esc)
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> {
                        if (cursor.pos + 4 > cursor.limit) return null
                        val hex = cursor.text.substring(cursor.pos, cursor.pos + 4)
                        val code = hex.toIntOrNull(16) ?: return null
                        out.append(code.toChar())
                        cursor.pos += 4
                    }
                    else -> out.append(esc)
                }
            } else {
                out.append(c)
            }
        }
        return null
    }

    private fun parsePrimitive(cursor: JsonCursor): JsonVal? {
        val start = cursor.pos
        val maxChars = minOf(64, cursor.limit - cursor.pos)
        for (step in 0 until maxChars) {
            val c = cursor.text[cursor.pos]
            if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\n' || c == '\r' || c == '\t') break
            cursor.pos++
        }
        if (cursor.pos == start) return null
        val token = cursor.text.substring(start, cursor.pos)
        val first = token[0]
        val valid = token == "true" || token == "false" || token == "null" ||
            first == '-' || first in '0'..'9'
        return if (valid) JsonVal.Other else null
    }

    private val MULTI_SPACE = Regex("\\s+")
    private val DASH_SPLIT = Regex("\\s+[-–—]\\s+")
}
