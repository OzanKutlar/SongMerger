package me.Integer.Mediaplayer.ui.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import kotlin.math.roundToInt

/**
 * Dark, blurred colour field taken from the album cover, like YouTube Music's player
 * background. The cover is reduced to a 4x4 grid of average colours that is stretched
 * with bilinear filtering: one tiny bitmap per track, nothing extra per frame.
 * Track changes crossfade (eased, honouring the animator scale); one edge fades into the
 * background colour.
 */
class CoverAmbienceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fieldPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = DIM_COLOR }
    private val edgePaint = Paint()
    private val fieldSource = Rect(0, 0, GRID, GRID)
    private val fieldTarget = RectF()

    private var sourceArt: Bitmap? = null
    private var currentField: Bitmap? = null
    private var previousField: Bitmap? = null
    private var fadeStartNanos = 0L
    private var fadeNanos = 0L
    private var frameScheduled = false
    private var blendTop = true

    private val frameTask = Runnable {
        frameScheduled = false
        onFrame()
    }

    // ------------------------------------------------------------------ public API

    /** Rebuilds the colour field for a new cover; null fades back to the plain background. */
    fun setArt(art: Bitmap?) {
        val usable = art?.takeUnless { it.isRecycled }
        if (usable === sourceArt) return
        sourceArt = usable
        val field = usable?.let { buildField(it) }
        val duration = Motion.scaledNanos(context, FADE_NANOS)
        previousField = if (duration > 0L) currentField else null
        currentField = field
        fadeNanos = duration
        fadeStartNanos = System.nanoTime()
        invalidate()
        scheduleFrame()
    }

    /** true: fade the top edge into the background; false: fade the left edge (landscape). */
    fun setBlendTop(top: Boolean) {
        if (blendTop == top) return
        blendTop = top
        updateEdgeShader()
        invalidate()
    }

    // ------------------------------------------------------------------ layout & drawing

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fieldTarget.set(0f, 0f, w.toFloat(), h.toFloat())
        updateEdgeShader()
    }

    private fun updateEdgeShader() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            edgePaint.shader = null
            glowPaint.shader = null
            return
        }
        glowPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(AMBIENT_TOP_COLOR, AMBIENT_MID_COLOR, BASE_COLOR),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        edgePaint.shader = if (blendTop) {
            LinearGradient(0f, 0f, 0f, h * EDGE_FRACTION, BASE_COLOR, BASE_TRANSPARENT, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, w * EDGE_FRACTION, 0f, BASE_COLOR, BASE_TRANSPARENT, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BASE_COLOR)
        if (glowPaint.shader != null) canvas.drawRect(fieldTarget, glowPaint)
        val progress = fadeProgress(System.nanoTime())
        val previous = previousField
        val current = currentField
        if (previous != null && progress < 1f) {
            drawField(canvas, previous, if (current == null) 1f - progress else 1f)
        }
        if (current != null) drawField(canvas, current, progress)
        canvas.drawRect(fieldTarget, dimPaint)
        if (edgePaint.shader != null) canvas.drawRect(fieldTarget, edgePaint)
    }

    private fun drawField(canvas: Canvas, field: Bitmap, alpha: Float) {
        fieldPaint.alpha = (OPAQUE * alpha.coerceIn(0f, 1f)).roundToInt()
        canvas.drawBitmap(field, fieldSource, fieldTarget, fieldPaint)
    }

    private fun fadeProgress(now: Long): Float {
        if (fadeNanos <= 0L) return 1f
        val raw = minOf(1f, (now - fadeStartNanos) / fadeNanos.toFloat())
        return FADE_INTERPOLATOR.getInterpolation(raw)
    }

    // ------------------------------------------------------------------ crossfade

    private fun scheduleFrame() {
        if (frameScheduled || !isShown || fadeProgress(System.nanoTime()) >= 1f) return
        frameScheduled = true
        postOnAnimation(frameTask)
    }

    private fun onFrame() {
        invalidate()
        if (fadeProgress(System.nanoTime()) < 1f && isShown) {
            frameScheduled = true
            postOnAnimation(frameTask)
        } else {
            previousField = null
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameTask)
        frameScheduled = false
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ colour field

    private fun buildField(art: Bitmap): Bitmap? {
        val pixels = samplePixels(art) ?: return null
        val cells = IntArray(GRID * GRID)
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                cells[gy * GRID + gx] = averageCell(pixels, gx * CELL, gy * CELL)
            }
        }
        return try {
            Bitmap.createBitmap(cells, GRID, GRID, Bitmap.Config.ARGB_8888)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot build cover ambience: ${e.message}")
            null
        }
    }

    private fun samplePixels(art: Bitmap): IntArray? {
        var sample: Bitmap? = null
        return try {
            val scaled = Bitmap.createScaledBitmap(art, SAMPLE_SIZE, SAMPLE_SIZE, true)
            sample = scaled
            val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
            scaled.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            pixels
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot sample cover colours: ${e.message}")
            null
        } finally {
            if (sample != null && sample !== art) sample.recycle()
        }
    }

    private fun averageCell(pixels: IntArray, startX: Int, startY: Int): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        for (y in startY until startY + CELL) {
            val row = y * SAMPLE_SIZE
            for (x in startX until startX + CELL) {
                val color = pixels[row + x]
                red += (color shr 16) and 0xFF
                green += (color shr 8) and 0xFF
                blue += color and 0xFF
            }
        }
        val count = CELL * CELL
        val r = (red / count).toInt()
        val g = (green / count).toInt()
        val b = (blue / count).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private companion object {
        const val TAG = "CoverAmbienceView"
        const val SAMPLE_SIZE = 48
        const val GRID = 4
        const val CELL = SAMPLE_SIZE / GRID
        const val BASE_COLOR = 0xFF09080A.toInt()
        const val BASE_TRANSPARENT = 0x0009080A
        const val AMBIENT_TOP_COLOR = 0xFF3A1908.toInt()
        const val AMBIENT_MID_COLOR = 0xFF1C0E0A.toInt()
        const val DIM_COLOR = 0xB809080A.toInt()
        const val EDGE_FRACTION = 0.2f
        const val FADE_NANOS = 600000000L
        const val OPAQUE = 255
        val FADE_INTERPOLATOR: Interpolator = DecelerateInterpolator()
    }
}
