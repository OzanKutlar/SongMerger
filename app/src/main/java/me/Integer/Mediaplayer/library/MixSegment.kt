package me.Integer.Mediaplayer.library

/**
 * One step of a mashup: play [trackUri] from [startMs] up to [endMs], then blend into the
 * next step over [crossfadeMs]. An [endMs] of [END_OF_TRACK] plays the song to its end.
 * Build instances with [create], which rejects impossible values.
 */
data class MixSegment(
    val trackUri: String,
    val startMs: Long,
    val endMs: Long,
    val crossfadeMs: Long,
    val tempoRatio: Float = 1f
) {

    val playsToEnd: Boolean
        get() = endMs == END_OF_TRACK

    companion object {
        const val END_OF_TRACK = -1L
        const val DEFAULT_CROSSFADE_MS = 4000L
        const val MAX_CROSSFADE_MS = 15000L
        const val MIN_TEMPO_RATIO = 0.85f
        const val MAX_TEMPO_RATIO = 1.15f

        /** A step shorter than this is not worth playing. */
        const val MIN_LENGTH_MS = 1000L

        /** An exit this close to the end of the song counts as playing to the end. */
        const val END_TOLERANCE_MS = 1500L
        const val MAX_POSITION_MS = 24L * 60L * 60L * 1000L

        /**
         * @param durationMs the length of the song, or 0 when it is not known
         * @return null when the step cannot be played
         */
        fun create(
            trackUri: String,
            startMs: Long,
            endMs: Long,
            crossfadeMs: Long,
            durationMs: Long = 0L,
            tempoRatio: Float = 1f
        ): MixSegment? {
            if (trackUri.isEmpty()) return null
            if (startMs < 0L || startMs > MAX_POSITION_MS) return null
            if (durationMs > 0L && startMs > durationMs - MIN_LENGTH_MS) return null
            val end = cleanEnd(endMs, durationMs)
            if (end != END_OF_TRACK && end - startMs < MIN_LENGTH_MS) return null
            val cleanTempo = if (tempoRatio.isFinite()) {
                tempoRatio.coerceIn(MIN_TEMPO_RATIO, MAX_TEMPO_RATIO)
            } else {
                1f
            }
            return MixSegment(
                trackUri = trackUri,
                startMs = startMs,
                endMs = end,
                crossfadeMs = crossfadeMs.coerceIn(0L, MAX_CROSSFADE_MS),
                tempoRatio = cleanTempo
            )
        }

        private fun cleanEnd(endMs: Long, durationMs: Long): Long {
            if (endMs <= 0L || endMs > MAX_POSITION_MS) return END_OF_TRACK
            if (durationMs > 0L && endMs >= durationMs - END_TOLERANCE_MS) return END_OF_TRACK
            return endMs
        }

        /** Minutes and seconds, rounded to the nearest second: 105000 gives 1:45. */
        fun formatTime(ms: Long): String {
            val totalSeconds = (maxOf(0L, ms) + 500L) / 1000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return if (seconds < 10L) "$minutes:0$seconds" else "$minutes:$seconds"
        }
    }
}
