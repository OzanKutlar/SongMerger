package me.Integer.Mediaplayer.ui.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The player background: near-black at the top, with a soft glow of the cover's colours
 * rising from the bottom like stage light behind the controls.
 *
 * A few coloured light blobs drift, breathe and slowly trade colours while music plays, and
 * ease to a standstill on pause. They are rendered into a tiny bitmap about 30 times a second,
 * which is stretched over the view with bilinear filtering; the stretch is what makes the
 * light look blurred. Nothing is allocated per frame, and the loop only runs while the view
 * is visible and something moves. Cover changes crossfade the palette.
 */
class CoverAmbienceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fieldPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val fieldSource = Rect(0, 0, FIELD_W, FIELD_H)
    private val fieldTarget = RectF()
    private val pixels = IntArray(FIELD_W * FIELD_H)
    private val rowMask = FloatArray(FIELD_H) { maskAt((it + 0.5f) / FIELD_H) }
    private var field: Bitmap? = null
    private var fieldFailed = false

    private val fromPalette = FALLBACK_PALETTE.copyOf()
    private val toPalette = FALLBACK_PALETTE.copyOf()
    private val palette = FALLBACK_PALETTE.copyOf()
    private var paletteStartNanos = 0L
    private var paletteNanos = 0L

    private val blobX = FloatArray(BLOBS)
    private val blobY = FloatArray(BLOBS)
    private val blobRadius2 = FloatArray(BLOBS)
    private val blobRed = FloatArray(BLOBS)
    private val blobGreen = FloatArray(BLOBS)
    private val blobBlue = FloatArray(BLOBS)

    private var sourceArt: Bitmap? = null
    private var drifting = false
    private var driftSpeed = 0f
    private var driftTime = 0.0
    private var lastFrameNanos = 0L
    private var frameScheduled = false
    private var aspect = DEFAULT_ASPECT

    private val frameTask = Runnable {
        frameScheduled = false
        onFrame()
    }

    // ------------------------------------------------------------------ public API

    /** Takes the glow colours from a new cover; null goes back to the default warm glow. */
    fun setArt(art: Bitmap?) {
        val usable = art?.takeUnless { it.isRecycled }
        if (usable === sourceArt) return
        sourceArt = usable
        val target = usable?.let { paletteOf(it) } ?: FALLBACK_PALETTE
        val now = System.nanoTime()
        blendPalette(now)
        palette.copyInto(fromPalette)
        target.copyInto(toPalette)
        paletteStartNanos = now
        paletteNanos = Motion.scaledNanos(context, PALETTE_NANOS)
        if (paletteNanos <= 0L) toPalette.copyInto(palette)
        invalidate()
        scheduleFrame()
    }

    /** The light drifts while music plays and eases to a stop when it pauses. */
    fun setDrifting(playing: Boolean) {
        val wanted = playing && Motion.durationScale(context) > 0f
        if (drifting == wanted) return
        drifting = wanted
        scheduleFrame()
    }

    // ------------------------------------------------------------------ layout & drawing

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fieldTarget.set(0f, 0f, w.toFloat(), h.toFloat())
        aspect = if (w > 0 && h > 0) h.toFloat() / w else DEFAULT_ASPECT
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BASE_COLOR)
        val bitmap = ensureField() ?: return
        renderField(bitmap, System.nanoTime())
        canvas.drawBitmap(bitmap, fieldSource, fieldTarget, fieldPaint)
    }

    private fun ensureField(): Bitmap? {
        field?.let { return it }
        if (fieldFailed) return null
        return try {
            Bitmap.createBitmap(FIELD_W, FIELD_H, Bitmap.Config.ARGB_8888).also { field = it }
        } catch (e: RuntimeException) {
            fieldFailed = true
            Log.w(TAG, "cannot create the glow field: ${e.message}")
            null
        }
    }

    private fun renderField(bitmap: Bitmap, now: Long) {
        blendPalette(now)
        placeBlobs()
        for (row in 0 until FIELD_H) {
            val mask = rowMask[row]
            val start = row * FIELD_W
            if (mask <= 0f) {
                pixels.fill(BASE_COLOR, start, start + FIELD_W)
                continue
            }
            val y = (row + 0.5f) / FIELD_H
            for (col in 0 until FIELD_W) {
                pixels[start + col] = shade((col + 0.5f) / FIELD_W, y, mask)
            }
        }
        bitmap.setPixels(pixels, 0, FIELD_W, 0, 0, FIELD_W, FIELD_H)
    }

    /** The colour at one point: the blobs' light added up, faded towards the top, softly compressed. */
    private fun shade(x: Float, y: Float, mask: Float): Int {
        var red = 0f
        var green = 0f
        var blue = 0f
        for (b in 0 until BLOBS) {
            val dx = x - blobX[b]
            val dy = (y - blobY[b]) * aspect
            val falloff = 1f - (dx * dx + dy * dy) / blobRadius2[b]
            if (falloff <= 0f) continue
            val weight = falloff * falloff
            red += blobRed[b] * weight
            green += blobGreen[b] * weight
            blue += blobBlue[b] * weight
        }
        return Color.rgb(
            channel(red * mask, BASE_RED),
            channel(green * mask, BASE_GREEN),
            channel(blue * mask, BASE_BLUE)
        )
    }

    private fun channel(light: Float, base: Int): Int {
        val lit = 1f - exp(-GAIN * light)
        return (base + (MAX_CHANNEL - base) * lit).toInt().coerceIn(0, MAX_CHANNEL)
    }

    /** Each blob wanders on its own slow curves, so the light never repeats in an obvious way. */
    private fun placeBlobs() {
        val t = driftTime
        for (b in 0 until BLOBS) {
            val phase = PHASES[b]
            blobX[b] = BASE_X[b] + SWING_X[b] * sin(t * FREQ_X[b] + phase).toFloat()
            blobY[b] = BASE_Y[b] + SWING_Y[b] * sin(t * FREQ_Y[b] + phase * PHASE_Y).toFloat()
            val radius = BASE_R[b] * (1f + RADIUS_SWING * sin(t * FREQ_R[b] + phase * PHASE_R).toFloat())
            blobRadius2[b] = radius * radius
            val mix = 0.5f + 0.5f * sin(t * FREQ_C[b] + phase * PHASE_C).toFloat()
            val from = COLOR_A[b] * 3
            val to = COLOR_B[b] * 3
            val strength = STRENGTH[b]
            blobRed[b] = lerp(palette[from], palette[to], mix) * strength
            blobGreen[b] = lerp(palette[from + 1], palette[to + 1], mix) * strength
            blobBlue[b] = lerp(palette[from + 2], palette[to + 2], mix) * strength
        }
    }

    private fun blendPalette(now: Long) {
        val progress = paletteProgress(now)
        for (i in palette.indices) palette[i] = lerp(fromPalette[i], toPalette[i], progress)
    }

    private fun paletteProgress(now: Long): Float {
        if (paletteNanos <= 0L) return 1f
        val raw = ((now - paletteStartNanos) / paletteNanos.toFloat()).coerceIn(0f, 1f)
        return raw * raw * (3f - 2f * raw)
    }

    // ------------------------------------------------------------------ animation

    private fun isPaletteFading(now: Long): Boolean {
        return paletteNanos > 0L && now - paletteStartNanos < paletteNanos
    }

    private fun needsFrames(now: Long): Boolean {
        return drifting || driftSpeed > 0f || isPaletteFading(now)
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        if (!isShown || !needsFrames(System.nanoTime())) {
            invalidate()
            return
        }
        frameScheduled = true
        lastFrameNanos = 0L
        postOnAnimation(frameTask)
    }

    private fun onFrame() {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else min(MAX_FRAME_SECONDS, (now - lastFrameNanos) / NANOS_PER_SECOND)
        lastFrameNanos = now
        advanceDrift(dt)
        invalidate()
        if (isShown && needsFrames(now)) {
            frameScheduled = true
            postOnAnimationDelayed(frameTask, FRAME_DELAY_MS)
        }
    }

    /** Drift speed eases in on play and out on pause, so the light never jerks. */
    private fun advanceDrift(dt: Float) {
        val target = if (drifting) 1f else 0f
        driftSpeed += (target - driftSpeed) * min(1f, DRIFT_EASE_RATE * dt)
        if (!drifting && driftSpeed < STOP_SPEED) driftSpeed = 0f
        driftTime += (dt * driftSpeed).toDouble()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameTask)
        frameScheduled = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) scheduleFrame()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) scheduleFrame()
    }

    // ------------------------------------------------------------------ palette

    /**
     * The cover's three strongest hues as bright, fairly saturated light (r, g, b per colour).
     * Covers with one hue get neighbours of it; grey covers get a soft neutral glow.
     */
    private fun paletteOf(art: Bitmap): FloatArray? {
        val sample = samplePixels(art) ?: return null
        val weights = FloatArray(HUE_BINS)
        val saturations = FloatArray(HUE_BINS)
        val hsv = FloatArray(3)
        for (pixel in sample) {
            Color.colorToHSV(pixel, hsv)
            if (hsv[2] < MIN_VALUE) continue
            val weight = hsv[1] * hsv[2]
            val bin = (hsv[0] / 360f * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            weights[bin] += weight
            saturations[bin] += hsv[1] * weight
        }
        val order = (0 until HUE_BINS).sortedByDescending { weights[it] }
        val best = order[0]
        if (weights[best] < MIN_WEIGHT) return NEUTRAL_PALETTE
        val picks = ArrayList<Int>(PALETTE_SIZE)
        picks.add(best)
        for (bin in order) {
            if (picks.size >= PALETTE_SIZE || weights[bin] < weights[best] * SECONDARY_SHARE) break
            if (picks.all { hueGap(it, bin) >= MIN_BIN_GAP }) picks.add(bin)
        }
        val bestSaturation = saturations[best] / weights[best]
        val out = FloatArray(PALETTE_SIZE * 3)
        for (slot in 0 until PALETTE_SIZE) {
            val bin = picks.getOrNull(slot)
            val hue = if (bin != null) {
                (bin + 0.5f) * BIN_DEGREES
            } else {
                ((best + 0.5f) * BIN_DEGREES + FILL_SHIFT[slot] + 360f) % 360f
            }
            val saturation = (if (bin != null) saturations[bin] / weights[bin] else bestSaturation)
                .coerceIn(MIN_SATURATION, MAX_SATURATION)
            val color = Color.HSVToColor(floatArrayOf(hue, saturation, SLOT_VALUE[slot]))
            out[slot * 3] = Color.red(color) / 255f
            out[slot * 3 + 1] = Color.green(color) / 255f
            out[slot * 3 + 2] = Color.blue(color) / 255f
        }
        return out
    }

    private fun hueGap(a: Int, b: Int): Int {
        val gap = abs(a - b)
        return min(gap, HUE_BINS - gap)
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

    private companion object {
        const val TAG = "CoverAmbienceView"
        const val FIELD_W = 36
        const val FIELD_H = 48
        const val BLOBS = 4
        const val PALETTE_SIZE = 3
        const val BASE_COLOR = 0xFF070608.toInt()
        const val BASE_RED = 7
        const val BASE_GREEN = 6
        const val BASE_BLUE = 8
        const val MAX_CHANNEL = 255
        const val GAIN = 1.5f

        /** The glow fades in from this height (0 = top) and is full at the bottom edge. */
        const val MASK_START = 0.3f
        const val MASK_END = 1f
        const val DEFAULT_ASPECT = 2f
        const val RADIUS_SWING = 0.14f
        const val PHASE_Y = 1.7
        const val PHASE_R = 0.6
        const val PHASE_C = 2.3
        const val PALETTE_NANOS = 900000000L
        const val FRAME_DELAY_MS = 33L
        const val MAX_FRAME_SECONDS = 0.1f
        const val NANOS_PER_SECOND = 1e9f
        const val DRIFT_EASE_RATE = 1.2f
        const val STOP_SPEED = 0.002f
        const val SAMPLE_SIZE = 24
        const val HUE_BINS = 12
        const val BIN_DEGREES = 360f / HUE_BINS
        const val MIN_VALUE = 0.12f
        const val MIN_WEIGHT = 12f
        const val SECONDARY_SHARE = 0.12f
        const val MIN_BIN_GAP = 2
        const val MIN_SATURATION = 0.45f
        const val MAX_SATURATION = 0.85f

        // Blob layout in view units: x across the width, y down the height, radius in widths.
        val BASE_X = floatArrayOf(0.12f, 0.88f, 0.5f, 0.3f)
        val BASE_Y = floatArrayOf(0.98f, 0.94f, 1.08f, 0.82f)
        val BASE_R = floatArrayOf(0.62f, 0.66f, 0.8f, 0.48f)
        val SWING_X = floatArrayOf(0.2f, 0.18f, 0.26f, 0.24f)
        val SWING_Y = floatArrayOf(0.05f, 0.07f, 0.04f, 0.06f)
        val STRENGTH = floatArrayOf(0.95f, 0.9f, 1f, 0.7f)

        // Radians per second of drift: whole cycles take roughly 20 to 70 seconds.
        val FREQ_X = doubleArrayOf(0.23, 0.17, 0.13, 0.29)
        val FREQ_Y = doubleArrayOf(0.19, 0.25, 0.15, 0.21)
        val FREQ_R = doubleArrayOf(0.31, 0.27, 0.22, 0.37)
        val FREQ_C = doubleArrayOf(0.11, 0.14, 0.09, 0.12)
        val PHASES = doubleArrayOf(0.0, 2.1, 4.2, 1.3)

        /** Each blob slides between two palette colours. */
        val COLOR_A = intArrayOf(0, 1, 2, 1)
        val COLOR_B = intArrayOf(1, 2, 0, 0)
        val SLOT_VALUE = floatArrayOf(0.78f, 0.66f, 0.6f)
        val FILL_SHIFT = floatArrayOf(0f, 30f, -34f)
        val FALLBACK_PALETTE = floatArrayOf(0.78f, 0.34f, 0.1f, 0.46f, 0.14f, 0.36f, 0.58f, 0.18f, 0.12f)
        val NEUTRAL_PALETTE = floatArrayOf(0.44f, 0.38f, 0.34f, 0.3f, 0.29f, 0.36f, 0.36f, 0.34f, 0.4f)

        fun maskAt(y: Float): Float {
            val s = ((y - MASK_START) / (MASK_END - MASK_START)).coerceIn(0f, 1f)
            return s * s * (3f - 2f * s)
        }

        fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}
