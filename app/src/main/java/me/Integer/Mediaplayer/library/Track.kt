package me.Integer.Mediaplayer.library

/**
 * One song in the library. [artistTag] is the raw artist text from the file; it is split
 * into individual artists on demand, so changing the separator needs no rescan.
 * [sizeBytes] and [modifiedMs] let a rescan skip files that did not change.
 */
data class Track(
    val uri: String,
    val title: String,
    val artistTag: String,
    val album: String,
    val durationMs: Long,
    val folderUri: String,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedMs: Long
)

/** An artist and every song they appear on. An empty [name] means the artist is unknown. */
data class Artist(val name: String, val tracks: List<Track>)
