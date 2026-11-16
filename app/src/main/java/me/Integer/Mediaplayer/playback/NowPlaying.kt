package me.Integer.Mediaplayer.playback

/**
 * Immutable snapshot of the player, delivered on the main thread.
 * Positions use the SystemClock.elapsedRealtime() clock.
 */
data class NowPlaying(
    val active: Boolean = false,
    val trackUri: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val positionUpdatedAtMs: Long = 0L,
    val speed: Float = 1f,
    /** The user wants playback (also true while buffering). */
    val playing: Boolean = false,
    /** Audio is actually advancing right now. */
    val clockRunning: Boolean = false,
    val hasPreviousTrack: Boolean = false,
    val canNext: Boolean = false,
    val canSeek: Boolean = false,
    val shuffleOn: Boolean = false,
    val repeatMode: Int = REPEAT_OFF,
    val currentIndex: Int = -1,
    val queue: List<QueueItem> = emptyList()
) {

    /** Live position, extrapolated from the last report. */
    fun positionAt(nowElapsedMs: Long): Long {
        return PlaybackClock.estimate(positionMs, positionUpdatedAtMs, speed, clockRunning, durationMs, nowElapsedMs)
    }

    companion object {
        const val REPEAT_OFF = 0
        const val REPEAT_ALL = 1
        const val REPEAT_ONE = 2

        /** Nothing loaded, or not connected to the player yet. */
        val IDLE = NowPlaying()
    }
}

/** Receives now-playing snapshots on the main thread. */
fun interface NowPlayingListener {
    fun onNowPlaying(state: NowPlaying)
}
