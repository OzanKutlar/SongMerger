package me.Integer.Mediaplayer.playback

/**
 * One entry in the player's current queue, in the exact order it will play.
 * [uid] is unique per queue slot so duplicate tracks still animate and drag cleanly.
 */
data class QueueItem(
    val uid: Long,
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String
)
