package me.Integer.Mediaplayer.library

import java.util.Locale

/**
 * Matches song metadata reported by an external media player against local [Track]s.
 */
internal object TrackMatcher {

    private val BRACKET_NOISE = Regex(
        "\\s*[(\\[]\\s*(feat\\.?|ft\\.?|featuring|with|prod\\.?|official|remaster|remastered|audio|video|lyric|lyrics|visualizer|explicit|clean|version|edit|hd|hq)[^)\\]]*[)\\]]",
        RegexOption.IGNORE_CASE
    )
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val DASH_SPLIT = Regex("\\s+[-–—]\\s+")

    /** Stable identity key for detecting track changes and loop-back to the first song. */
    fun fingerprint(title: String, artist: String): String {
        val cleanTitle = normalizeKeepBrackets(title)
        if (cleanTitle.isEmpty()) return ""
        val cleanArtist = normalizeKeepBrackets(artist)
        return "$cleanTitle|$cleanArtist"
    }

    /** Best matching [Track] from [candidates], or null when none match. */
    fun match(externalTitle: String, externalArtist: String, candidates: List<Track>): Track? {
        if (externalTitle.isBlank() || candidates.isEmpty()) return null
        val direct = matchPair(externalTitle, externalArtist, candidates)
        if (direct != null) return direct

        // Some players put "Artist - Title" into the title field.
        val parts = externalTitle.split(DASH_SPLIT, limit = 2)
        if (parts.size == 2) {
            val swapped = matchPair(parts[1], parts[0], candidates)
                ?: matchPair(parts[0], parts[1], candidates)
            if (swapped != null) return swapped
        }
        return null
    }

    private fun matchPair(rawTitle: String, rawArtist: String, candidates: List<Track>): Track? {
        val exactTitle = normalizeKeepBrackets(rawTitle)
        val strippedTitle = normalizeStripped(rawTitle)
        if (exactTitle.isEmpty() && strippedTitle.isEmpty()) return null
        val normArtist = normalizeStripped(rawArtist)

        var bestTrack: Track? = null
        var bestScore = 0

        for (candidate in candidates) {
            val score = scoreCandidate(candidate, exactTitle, strippedTitle, normArtist)
            if (score > bestScore) {
                bestScore = score
                bestTrack = candidate
            }
        }
        return if (bestScore >= MIN_ACCEPT_SCORE) bestTrack else null
    }

    private fun scoreCandidate(
        candidate: Track,
        exactTitle: String,
        strippedTitle: String,
        normArtist: String
    ): Int {
        val candExactTitle = normalizeKeepBrackets(candidate.title)
        val candStrippedTitle = normalizeStripped(candidate.title)
        val titleScore = when {
            exactTitle.isNotEmpty() && candExactTitle == exactTitle -> 100
            strippedTitle.isNotEmpty() && candStrippedTitle == strippedTitle -> 85
            strippedTitle.length >= 4 && candStrippedTitle.length >= 4 &&
                (candStrippedTitle.contains(strippedTitle) || strippedTitle.contains(candStrippedTitle)) -> 60
            else -> 0
        }
        if (titleScore == 0) return 0
        if (normArtist.isEmpty()) return titleScore

        val candArtist = normalizeStripped(candidate.artistTag)
        val candFile = normalizeStripped(candidate.fileName.substringBeforeLast('.'))
        val artistBonus = when {
            candArtist.isNotEmpty() && candArtist == normArtist -> 50
            candArtist.isNotEmpty() &&
                (candArtist.contains(normArtist) || normArtist.contains(candArtist)) -> 35
            hasSharedToken(normArtist, candArtist) -> 25
            candFile.contains(normArtist) -> 15
            candArtist.isEmpty() -> 0
            else -> -25
        }
        return titleScore + artistBonus
    }

    private fun hasSharedToken(first: String, second: String): Boolean {
        if (first.isEmpty() || second.isEmpty()) return false
        val firstTokens = first.split(' ').filter { it.length >= 3 }
        val secondTokens = second.split(' ').filter { it.length >= 3 }.toSet()
        return firstTokens.any { it in secondTokens }
    }

    fun normalizeStripped(text: String): String {
        val withoutBrackets = BRACKET_NOISE.replace(text, " ")
        return normalizeKeepBrackets(withoutBrackets)
    }

    private fun normalizeKeepBrackets(text: String): String {
        return NON_WORD.replace(text.lowercase(Locale.ROOT), " ").trim()
    }

    private const val MIN_ACCEPT_SCORE = 75
}
