package me.Integer.Mediaplayer.playback

/**
 * Pure decisions around "previous". As in most players, the previous button restarts the
 * song once it has played for a few seconds and only goes back a track near its start.
 * No Android dependencies, so it is unit tested.
 */
object SkipOutcome {

    /** Past this position the previous button restarts the song. */
    const val RESTART_THRESHOLD_MS = 3000L

    /** One record turn per 30 s of rewound playback, as with scrubbing. */
    const val DEGREES_PER_MS = 360f / 30000f
    const val MIN_REWIND_DEGREES = 360f
    const val MAX_REWIND_DEGREES = 1080f

    /** True when the previous button at this position restarts the song. */
    fun expectsRestart(positionMs: Long): Boolean {
        return positionMs > RESTART_THRESHOLD_MS
    }

    /** How far the record spins back (negative = counter-clockwise) for a rewind of this length. */
    fun rewindDegrees(rewoundMs: Long): Float {
        val raw = maxOf(0L, rewoundMs) * DEGREES_PER_MS
        return -raw.coerceIn(MIN_REWIND_DEGREES, MAX_REWIND_DEGREES)
    }
}
