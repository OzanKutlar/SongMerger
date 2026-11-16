package me.Integer.Mediaplayer.playback

/**
 * Pure helpers for media position math and time formatting.
 * Free of Android types, so it is unit tested on the JVM.
 */
object PlaybackClock {

    /** Seek bar resolution: positions are mapped to 0..PERMILLE_MAX. */
    const val PERMILLE_MAX = 1000

    private const val MAX_SPEED = 8f
    private const val MS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L

    /**
     * Extrapolates the playback position from the player's last report.
     *
     * @param positionMs position at the last report, negative when unknown
     * @param updatedAtMs elapsedRealtime of the last report, 0 when unknown
     * @param speed playback speed; invalid values fall back to real time
     * @param running true only while the player is actually advancing
     * @param durationMs track length, 0 or less when unknown (no clamping then)
     * @param nowMs current elapsedRealtime
     */
    fun estimate(
        positionMs: Long,
        updatedAtMs: Long,
        speed: Float,
        running: Boolean,
        durationMs: Long,
        nowMs: Long
    ): Long {
        if (positionMs < 0L) return 0L
        var position = positionMs
        if (running && updatedAtMs > 0L && nowMs > updatedAtMs) {
            position += ((nowMs - updatedAtMs) * sanitiseSpeed(speed)).toLong()
        }
        return clamp(position, durationMs)
    }

    internal fun sanitiseSpeed(speed: Float): Float {
        if (speed.isNaN() || speed <= 0f) return 1f
        return minOf(speed, MAX_SPEED)
    }

    fun clamp(positionMs: Long, durationMs: Long): Long {
        if (positionMs < 0L) return 0L
        if (durationMs > 0L && positionMs > durationMs) return durationMs
        return positionMs
    }

    fun toPermille(positionMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return (clamp(positionMs, durationMs) * PERMILLE_MAX / durationMs).toInt()
    }

    fun fromPermille(permille: Int, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val bounded = permille.coerceIn(0, PERMILLE_MAX)
        return durationMs * bounded / PERMILLE_MAX
    }

    /** Formats as m:ss, or h:mm:ss for long tracks. Negative input shows 0:00. */
    fun format(ms: Long): String {
        val totalSeconds = maxOf(0L, ms) / MS_PER_SECOND
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds / SECONDS_PER_MINUTE) % SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        val text = StringBuilder(8)
        if (hours > 0L) {
            text.append(hours).append(':')
            appendTwoDigits(text, minutes)
        } else {
            text.append(minutes)
        }
        text.append(':')
        appendTwoDigits(text, seconds)
        return text.toString()
    }

    private fun appendTwoDigits(text: StringBuilder, value: Long) {
        if (value < 10L) text.append('0')
        text.append(value)
    }
}
