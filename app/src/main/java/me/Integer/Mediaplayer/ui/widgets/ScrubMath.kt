package me.Integer.Mediaplayer.ui.widgets

import kotlin.math.roundToLong

/**
 * Pure math for turning the vinyl to seek: angle unwrapping, degrees to time and clamping.
 * Free of Android types, so it is unit tested on the JVM.
 */
object ScrubMath {

    /** One full turn of the record moves playback by this much. */
    const val MS_PER_TURN = 30000L

    private const val DEGREES_PER_TURN = 360f
    private const val HALF_TURN = 180f

    /**
     * Shortest signed rotation from [prevDeg] to [currDeg], in (-180, 180].
     * Inputs are atan2 angles; non-finite input counts as no movement.
     */
    fun unwrapDelta(prevDeg: Float, currDeg: Float): Float {
        if (!prevDeg.isFinite() || !currDeg.isFinite()) return 0f
        var delta = (currDeg - prevDeg) % DEGREES_PER_TURN
        if (delta > HALF_TURN) {
            delta -= DEGREES_PER_TURN
        } else if (delta <= -HALF_TURN) {
            delta += DEGREES_PER_TURN
        }
        return delta
    }

    /** Playback time covered by a rotation; positive (clockwise) is forward. */
    fun degreesToMs(degrees: Float): Long {
        if (!degrees.isFinite()) return 0L
        return (degrees.toDouble() * MS_PER_TURN / DEGREES_PER_TURN).roundToLong()
    }

    /** Rotation that covers the given playback time. */
    fun msToDegrees(ms: Long): Float {
        return (ms.toDouble() * DEGREES_PER_TURN / MS_PER_TURN).toFloat()
    }

    /**
     * Bounds an accumulated rotation so that [baseMs] plus the rotation stays within
     * [0, duration]. Without a known duration no scrubbing is possible, so the result is 0.
     */
    fun clampDegrees(baseMs: Long, degrees: Float, durationMs: Long): Float {
        if (durationMs <= 0L || !degrees.isFinite()) return 0f
        val base = clampPosition(baseMs, durationMs)
        val min = msToDegrees(-base)
        val max = msToDegrees(durationMs - base)
        return degrees.coerceIn(min, max)
    }

    /** Position reached by turning [degrees] from [baseMs], clamped to the track. */
    fun target(baseMs: Long, degrees: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return maxOf(0L, baseMs)
        val moved = clampPosition(baseMs, durationMs) + degreesToMs(degrees)
        return clampPosition(moved, durationMs)
    }

    /** Index of the step a position falls in; a change means a haptic detent was crossed. */
    fun detent(positionMs: Long, stepMs: Long): Long {
        if (stepMs <= 0L) return 0L
        return maxOf(0L, positionMs) / stepMs
    }

    private fun clampPosition(positionMs: Long, durationMs: Long): Long {
        if (positionMs < 0L) return 0L
        if (durationMs > 0L && positionMs > durationMs) return durationMs
        return positionMs
    }
}
