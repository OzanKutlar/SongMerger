package me.Integer.Mediaplayer.ui.widgets

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.abs

/**
 * A thin playback progress line along the bottom edge of the mini player, drawn as the bar's
 * foreground. It is inset from both ends so it stays inside the bar's rounded corners.
 */
internal class ProgressLineDrawable(density: Float, private val insetPx: Float) : Drawable() {

    private val thicknessPx = THICKNESS_DP * density
    private val bottomPx = BOTTOM_DP * density
    private val trackPaint = linePaint(TRACK_COLOR)
    private val fillPaint = linePaint(FILL_COLOR)
    private var fraction = 0f

    /** Shows [value] (0..1) of the song as played; tiny changes are not redrawn. */
    fun setFraction(value: Float) {
        val clean = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        if (abs(clean - fraction) < MIN_CHANGE) return
        fraction = clean
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        val left = area.left + insetPx
        val right = area.right - insetPx
        if (right <= left) return
        val y = area.bottom - bottomPx
        canvas.drawLine(left, y, right, y, trackPaint)
        if (fraction > 0f) canvas.drawLine(left, y, left + (right - left) * fraction, y, fillPaint)
    }

    override fun setAlpha(alpha: Int) {
        trackPaint.alpha = ((TRACK_COLOR ushr 24) * alpha) / OPAQUE
        fillPaint.alpha = ((FILL_COLOR ushr 24) * alpha) / OPAQUE
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        trackPaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun linePaint(color: Int): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = thicknessPx
        }
    }

    private companion object {
        const val THICKNESS_DP = 2f
        const val BOTTOM_DP = 4f
        const val MIN_CHANGE = 0.0005f
        const val OPAQUE = 255
        const val TRACK_COLOR = 0x33FFFFFF
        const val FILL_COLOR = 0xE6FFFFFF.toInt()
    }
}
