package me.Integer.Mediaplayer.ui.widgets

import kotlin.math.abs
import kotlin.math.ln

/**
 * The maths of [ListGlide], kept free of Android so it can be unit-tested. Positions are in
 * rows: 12.5 means the list starts halfway through row 12.
 */
internal object GlideMath {

    const val MIN_MS = 320L
    const val MAX_MS = 1600L
    private const val PER_LOG_ROW_MS = 190.0

    /** Smootherstep: starts at rest, is fastest in the middle and arrives at rest. */
    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * x * (x * (x * 6f - 15f) + 10f)
    }

    /** Where the glide is at time fraction [t]. */
    fun position(from: Float, to: Float, t: Float): Float {
        return from + (to - from) * ease(t)
    }

    /** The list's position from its first visible row and how far that row is scrolled off the top. */
    fun currentPosition(firstIndex: Int, firstTopPx: Int, rowHeight: Int): Float {
        if (rowHeight <= 0) return firstIndex.toFloat()
        return firstIndex - firstTopPx.toFloat() / rowHeight
    }

    /**
     * The position that puts [row] in the middle of a viewport [viewportPx] tall, kept inside
     * the list so rows near the top or bottom end as close to the middle as the list allows.
     */
    fun centredPosition(row: Int, rowHeight: Int, viewportPx: Int, count: Int): Float {
        if (rowHeight <= 0 || count <= 0 || viewportPx <= 0) return 0f
        val rowsInView = viewportPx.toFloat() / rowHeight
        val desired = row - (rowsInView - 1f) / 2f
        val lastStart = maxOf(0f, count - rowsInView)
        return desired.coerceIn(0f, lastStart)
    }

    /** Longer glides take longer, but the time grows slowly so far jumps stay quick. */
    fun durationMs(rows: Float): Long {
        val distance = if (rows.isFinite()) abs(rows).toDouble() else 0.0
        return (MIN_MS + PER_LOG_ROW_MS * ln(1.0 + distance)).toLong().coerceIn(MIN_MS, MAX_MS)
    }
}
