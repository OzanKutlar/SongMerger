package me.Integer.Mediaplayer.library

/**
 * Fallback for files without tags: reads title and artist from the file name.
 * "03 - Artist A, Artist B - Song.mp3" gives the artist text "Artist A, Artist B" and the
 * title "Song". Pure Kotlin, unit tested on the JVM.
 */
object TitleGuesser {

    data class Guess(val title: String, val artistTag: String)

    private const val DIVIDER = " - "
    private const val MAX_EXTENSION_LENGTH = 5

    /** A leading track number followed by a dash, dot or bracket, e.g. "03 - " or "1. ". */
    private val TRACK_NUMBER = Regex("""^\s*\d{1,3}\s*[-.)]\s+""")

    fun fromFileName(fileName: String?): Guess {
        if (fileName.isNullOrBlank()) return Guess("", "")
        val base = stripExtension(fileName.trim()).replace('_', ' ').trim()
        val unnumbered = TRACK_NUMBER.replaceFirst(base, "").trim()
        val cleaned = unnumbered.ifEmpty { base }
        val cut = cleaned.indexOf(DIVIDER)
        if (cut <= 0) return Guess(cleaned, "")
        val artist = cleaned.substring(0, cut).trim()
        val title = cleaned.substring(cut + DIVIDER.length).trim()
        return if (title.isEmpty()) Guess(cleaned, "") else Guess(title, artist)
    }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        val extensionLength = name.length - dot - 1
        if (dot <= 0 || extensionLength !in 1..MAX_EXTENSION_LENGTH) return name
        return name.substring(0, dot)
    }
}
