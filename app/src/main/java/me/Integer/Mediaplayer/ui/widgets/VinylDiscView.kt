package me.Integer.Mediaplayer.ui.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A vinyl record whose whole surface is the album cover, with a see-through spindle hole.
 * It spins slowly while music plays, eases in on play and coasts to a stop on pause.
 * New covers crossfade in while spinning. Track changes run as a carousel: the old record
 * slides out one side while the new one slides in from the other, and the resting record
 * can be dragged with the finger. The frame loop only runs while something moves and is visible.
 */
class VinylDiscView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val previousPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val outgoingPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holeRimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelEmptyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPath = Path()
    private val labelPath = Path()
    private val groovePath = Path()
    private val sheenPath = Path()
    private val discBounds = RectF()
    private val accentBounds = RectF()
    private val rimNormalWidth: Float
    private val rimScrubWidth: Float

    private var artBitmap: Bitmap? = null
    private var artShader: BitmapShader? = null
    private var previousArt: Bitmap? = null
    private var previousShader: BitmapShader? = null
    private var crossfadeStartNanos = 0L
    private var crossfadeNanos = 0L

    // Carousel: one record leaving, the current one resting, hidden or entering.
    private var outgoingArt: Bitmap? = null
    private var outgoingShader: BitmapShader? = null
    private var outgoingFrom = 0f
    private var outgoingTo = 0f
    private var outgoingStartNanos = 0L
    private var outgoingNanos = 0L
    private var outgoingLiftInPlace = false
    private var phase = PHASE_SHOWN
    private var dragOffset = 0f
    private var springFrom = 0f
    private var springStartNanos = 0L
    private var springNanos = 0L
    private var enterFrom = 0f
    private var enterStartNanos = 0L
    private var enterNanos = 0L
    private var exitOffset = 0f

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var holeRadius = 0f
    private var labelRadius = 0f
    private var angle = 0f
    private var velocity = 0f
    private var spinning = false
    private var expanded = false
    private var discScale = RESTING_SCALE
    private var scrubActive = false
    private var rewindActive = false
    private var rewindRemaining = 0f
    private var frameScheduled = false
    private var lastFrameNanos = 0L

    private val frameTask = Runnable {
        frameScheduled = false
        onFrame()
    }

    init {
        val density = resources.displayMetrics.density
        rimNormalWidth = 1.5f * density
        rimScrubWidth = 3f * density
        discPaint.style = Paint.Style.FILL
        artPaint.style = Paint.Style.FILL
        previousPaint.style = Paint.Style.FILL
        outgoingPaint.style = Paint.Style.FILL
        groovePaint.style = Paint.Style.STROKE
        rimPaint.style = Paint.Style.STROKE
        rimPaint.strokeWidth = rimNormalWidth
        rimPaint.color = COLOR_RIM
        holeRimPaint.style = Paint.Style.STROKE
        holeRimPaint.strokeWidth = 2f * density
        holeRimPaint.color = COLOR_HOLE_RIM
        labelEmptyPaint.style = Paint.Style.FILL
        labelEmptyPaint.color = COLOR_LABEL_EMPTY
        accentPaint.style = Paint.Style.STROKE
        accentPaint.strokeWidth = 3f * density
        accentPaint.strokeCap = Paint.Cap.ROUND
        accentPaint.color = COLOR_ACCENT
        sheenPaint.style = Paint.Style.FILL
        sheenPaint.color = COLOR_SHEEN
    }

    // ------------------------------------------------------------------ public API

    /** Covers the disc with the album art (crossfading from the previous cover). */
    fun setArt(bitmap: Bitmap?) {
        val usable = bitmap?.takeUnless { it.isRecycled }
        if (usable === artBitmap) return
        val duration = Motion.scaledNanos(context, CROSSFADE_NANOS)
        if (duration > 0L && isShown && radius > 0f && phase != PHASE_HIDDEN) {
            previousArt = artBitmap
            previousShader = artShader
            previousPaint.shader = previousShader
            crossfadeStartNanos = System.nanoTime()
            crossfadeNanos = duration
        } else {
            clearPrevious()
        }
        artBitmap = usable
        artShader = usable?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        artPaint.shader = artShader
        configureShader(artShader, artBitmap)
        invalidate()
        scheduleFrame()
    }

    /** Spins up when true, coasts to a stop when false. */
    fun setPlaying(playing: Boolean) {
        if (spinning == playing) return
        spinning = playing
        scheduleFrame()
    }

    /**
     * The record rests slightly smaller and grows to full size while music plays. The size
     * eases towards its target every frame, so quick toggles never jump.
     */
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        if (Motion.durationScale(context) <= 0f) discScale = targetScale()
        invalidate()
        scheduleFrame()
    }

    /**
     * While scrubbing the record only follows the finger: the motor spin is frozen and the
     * rim lights up. Afterwards the spin eases back in from wherever the finger left it.
     */
    fun setScrubbing(active: Boolean) {
        if (scrubActive == active) return
        scrubActive = active
        velocity = 0f
        if (active) cancelRewind()
        rimPaint.color = if (active) COLOR_ACCENT else COLOR_RIM
        rimPaint.strokeWidth = if (active) rimScrubWidth else rimNormalWidth
        invalidate()
        scheduleFrame()
    }

    /** Turns the record by the finger's rotation (positive = clockwise). Only while scrubbing. */
    fun addScrubRotation(degrees: Float) {
        if (!scrubActive || !degrees.isFinite()) return
        angle = (angle + degrees) % 360f
        invalidate()
    }

    /**
     * Spins the record by [degrees] (negative = backwards) as a lerp to a stop: fast at
     * first, easing out, after which the motor spin eases back in. Used when "previous"
     * restarts the song. Ignored while scrubbing.
     */
    fun rewind(degrees: Float) {
        if (scrubActive || degrees == 0f || !degrees.isFinite()) return
        velocity = 0f
        if (Motion.durationScale(context) <= 0f) {
            angle = (angle + degrees) % 360f
            cancelRewind()
            invalidate()
            return
        }
        rewindRemaining = degrees
        rewindActive = true
        invalidate()
        scheduleFrame()
    }

    /**
     * Adds [degrees] (positive = clockwise/forward, negative = counter-clockwise/rewind)
     * to the record's lerp spin so slider drags and fast-forwards spin with momentum.
     */
    fun spinBy(degrees: Float) {
        if (scrubActive || degrees == 0f || !degrees.isFinite()) return
        velocity = 0f
        if (Motion.durationScale(context) <= 0f) {
            angle = (angle + degrees) % 360f
            cancelRewind()
            invalidate()
            return
        }
        rewindRemaining = (rewindRemaining + degrees).coerceIn(-MAX_SPIN_DEGREES, MAX_SPIN_DEGREES)
        rewindActive = true
        invalidate()
        scheduleFrame()
    }

    /** Remaining degrees in the current lerp spin. */
    val pendingSpinDegrees: Float
        get() = if (rewindActive) rewindRemaining else 0f

    /** True while a [rewind] is still turning the record. */
    val isRewinding: Boolean
        get() = rewindActive

    // ------------------------------------------------------------------ carousel

    /** Moves the resting record with the finger. Ignored while a slide is running. */
    fun setDragOffset(px: Float) {
        if (phase != PHASE_SHOWN) return
        val limit = slideDistance()
        springNanos = 0L
        dragOffset = if (limit > 0f) px.coerceIn(-limit, limit) else 0f
        invalidate()
    }

    /** Springs a dragged record back to the centre. */
    fun releaseDrag() {
        if (phase != PHASE_SHOWN || dragOffset == 0f) return
        springFrom = dragOffset
        dragOffset = 0f
        springStartNanos = System.nanoTime()
        springNanos = Motion.scaledNanos(context, SPRING_NANOS)
        invalidate()
        scheduleFrame()
    }

    /**
     * Slides the record out of the disc area, starting wherever it is now (e.g. mid-drag):
     * DIRECTION_NEXT leaves to the left, DIRECTION_PREVIOUS to the right.
     * The area stays empty until [enter].
     */
    fun exit(direction: Int) {
        if (phase == PHASE_HIDDEN) return
        val now = System.nanoTime()
        val from = currentOffset(now)
        val travel = slideDistance()
        val liftInPlace = from == 0f
        val base = Motion.scaledNanos(context, if (liftInPlace) OUTGOING_NANOS else SLIDE_NANOS)
        clearPrevious()
        clearOutgoing()
        cancelRewind()
        phase = PHASE_HIDDEN
        dragOffset = 0f
        springNanos = 0L
        exitOffset = -directionSign(direction) * travel
        if (base > 0L && travel > 0f && isShown) {
            val to = exitOffset
            val share = if (liftInPlace) 1f else minOf(2f, abs(to - from) / travel)
            outgoingArt = artBitmap
            outgoingShader = artShader
            outgoingPaint.shader = outgoingShader
            outgoingFrom = from
            outgoingTo = to
            outgoingStartNanos = now
            outgoingNanos = maxOf(MIN_SLIDE_NANOS, (base * share).toLong())
            outgoingLiftInPlace = liftInPlace
        }
        invalidate()
        scheduleFrame()
    }

    /**
     * Slides the new record in while big, synced with the outgoing record's lift, and then
     * slims it down onto the spindle once centered.
     */
    fun enter(direction: Int) {
        val travel = slideDistance()
        val duration = Motion.scaledNanos(context, ENTER_NANOS)
        clearPrevious()
        dragOffset = 0f
        springNanos = 0L
        if (duration <= 0L || travel <= 0f || !isShown) {
            phase = PHASE_SHOWN
            enterNanos = 0L
            invalidate()
            return
        }
        phase = PHASE_ENTERING
        enterFrom = directionSign(direction) * travel
        enterStartNanos = System.nanoTime()
        enterNanos = duration
        invalidate()
        scheduleFrame()
    }

    /** True after [exit] until the next [enter]. */
    val isAwaitingTrack: Boolean
        get() = phase == PHASE_HIDDEN

    /**
     * Brings a record that left through [exit] back to the centre, reversing from wherever
     * its slide is now. Used when a skip did not change the track.
     */
    fun returnInPlace() {
        if (phase != PHASE_HIDDEN) return
        val now = System.nanoTime()
        val from = if (isOutgoingActive(now)) outgoingOffset(now) else exitOffset
        val travel = slideDistance()
        val base = Motion.scaledNanos(context, SLIDE_NANOS)
        clearOutgoing()
        clearPrevious()
        dragOffset = 0f
        springNanos = 0L
        if (base <= 0L || travel <= 0f || from == 0f || !isShown) {
            phase = PHASE_SHOWN
            enterNanos = 0L
            invalidate()
            return
        }
        val share = minOf(1f, abs(from) / travel)
        phase = PHASE_ENTERING
        enterFrom = from
        enterStartNanos = now
        enterNanos = maxOf(MIN_SLIDE_NANOS, (base * share).toLong())
        invalidate()
        scheduleFrame()
    }

    /** Ends every slide at once and shows the current record resting in the centre. */
    fun showInPlace() {
        clearOutgoing()
        cancelRewind()
        phase = PHASE_SHOWN
        dragOffset = 0f
        springNanos = 0L
        enterNanos = 0L
        invalidate()
    }

    // ------------------------------------------------------------------ measuring

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val widthFree = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        val heightFree = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
        val size = when {
            widthFree && heightFree -> (DEFAULT_SIZE_DP * resources.displayMetrics.density).roundToInt()
            widthFree -> height
            heightFree -> width
            else -> minOf(width, height)
        }
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = maxOf(0f, minOf(w, h) * DISC_RADIUS_FRACTION - rimNormalWidth)
        holeRadius = radius * HOLE_FRACTION
        labelRadius = radius * LABEL_FRACTION
        discBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val accent = labelRadius * (1f - ACCENT_INSET)
        accentBounds.set(cx - accent, cy - accent, cx + accent, cy + accent)
        rebuildRing(discPath, radius)
        rebuildRing(labelPath, labelRadius)
        rebuildSheen()
        discPaint.shader = if (radius > 0f) {
            RadialGradient(cx, cy, radius, COLOR_DISC_CENTER, COLOR_DISC_EDGE, Shader.TileMode.CLAMP)
        } else {
            null
        }
        layoutGrooves()
        configureShader(artShader, artBitmap)
        configureShader(previousShader, previousArt)
        configureShader(outgoingShader, outgoingArt)
    }

    /** A filled circle with the spindle hole cut out, so whatever is behind shows through. */
    private fun rebuildRing(path: Path, outer: Float) {
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        if (outer <= holeRadius) return
        path.addCircle(cx, cy, outer, Path.Direction.CW)
        path.addCircle(cx, cy, holeRadius, Path.Direction.CW)
    }

    private fun rebuildSheen() {
        sheenPath.reset()
        if (radius <= holeRadius) return
        sheenPath.moveTo(cx, cy)
        sheenPath.arcTo(discBounds, SHEEN_START_A, SHEEN_SWEEP)
        sheenPath.close()
        sheenPath.moveTo(cx, cy)
        sheenPath.arcTo(discBounds, SHEEN_START_B, SHEEN_SWEEP)
        sheenPath.close()
        sheenPath.op(discPath, Path.Op.INTERSECT)
    }

    private fun layoutGrooves() {
        val inner = labelRadius * GROOVE_INNER
        val outer = radius * GROOVE_OUTER
        val step = maxOf(0f, outer - inner) / GROOVE_COUNT
        groovePaint.strokeWidth = maxOf(1f, step * 0.3f)
        groovePath.reset()
        for (i in 0 until GROOVE_COUNT) {
            val r = inner + step * (i + 0.5f)
            if (r > 0f) groovePath.addCircle(cx, cy, r, Path.Direction.CW)
        }
    }

    /** Centre-crops a cover over the full disc. */
    private fun configureShader(shader: BitmapShader?, bitmap: Bitmap?) {
        if (shader == null || bitmap == null || radius <= 0f) return
        val shortest = minOf(bitmap.width, bitmap.height)
        if (shortest <= 0) return
        val scale = radius * 2f / shortest
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(cx - bitmap.width * scale / 2f, cy - bitmap.height * scale / 2f)
        shader.setLocalMatrix(matrix)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return
        val now = System.nanoTime()
        if (isOutgoingActive(now)) {
            drawDisc(canvas, outgoingOffset(now), discScale * outgoingSwapScale(now), true, 1f)
        }
        if (phase != PHASE_HIDDEN) {
            drawDisc(canvas, currentOffset(now), discScale * currentSwapScale(now), false, crossfadeProgress(now))
        }
    }

    /** One record at a horizontal offset and scale; fades via paint alpha without saveLayerAlpha. */
    private fun drawDisc(canvas: Canvas, offset: Float, scale: Float, outgoing: Boolean, progress: Float) {
        val fade = slideFade(offset)
        val saved = canvas.save()
        canvas.translate(offset, 0f)
        canvas.scale(scale, scale, cx, cy)
        canvas.save()
        canvas.rotate(angle, cx, cy)
        if (outgoing) drawOutgoingSurface(canvas, fade) else drawSurface(canvas, progress, fade)
        canvas.restore()
        sheenPaint.alpha = (SHEEN_ALPHA * fade).roundToInt()
        canvas.drawPath(sheenPath, sheenPaint)
        rimPaint.alpha = alphaOf(fade)
        holeRimPaint.alpha = (HOLE_RIM_ALPHA * fade).roundToInt()
        canvas.drawCircle(cx, cy, radius, rimPaint)
        canvas.drawCircle(cx, cy, holeRadius, holeRimPaint)
        canvas.restoreToCount(saved)
    }

    private fun drawOutgoingSurface(canvas: Canvas, fade: Float) {
        if (outgoingShader == null) {
            drawPlaceholder(canvas, fade)
            return
        }
        outgoingPaint.alpha = alphaOf(fade)
        canvas.drawPath(discPath, outgoingPaint)
        drawGrooves(canvas, COLOR_GROOVE_ON_ART, ART_GROOVE_ALPHA_SCALE * fade)
    }

    /** Old surface underneath, new surface fading in on top (or old art fading off). */
    private fun drawSurface(canvas: Canvas, progress: Float, fade: Float) {
        if (artShader != null) {
            if (progress < 1f) drawPreviousSurface(canvas, fade)
            artPaint.alpha = alphaOf(progress * fade)
            canvas.drawPath(discPath, artPaint)
            drawGrooves(canvas, COLOR_GROOVE_ON_ART, ART_GROOVE_ALPHA_SCALE * fade)
            return
        }
        drawPlaceholder(canvas, fade)
        if (progress < 1f && previousShader != null) {
            previousPaint.alpha = alphaOf((1f - progress) * fade)
            canvas.drawPath(discPath, previousPaint)
        }
    }

    private fun drawPreviousSurface(canvas: Canvas, fade: Float) {
        if (previousShader == null) {
            drawPlaceholder(canvas, fade)
            return
        }
        previousPaint.alpha = alphaOf(fade)
        canvas.drawPath(discPath, previousPaint)
    }

    private fun drawPlaceholder(canvas: Canvas, fade: Float) {
        val alpha = alphaOf(fade)
        discPaint.alpha = alpha
        labelEmptyPaint.alpha = alpha
        accentPaint.alpha = alpha
        canvas.drawPath(discPath, discPaint)
        drawGrooves(canvas, COLOR_GROOVE, fade)
        canvas.drawPath(labelPath, labelEmptyPaint)
        canvas.drawArc(accentBounds, -60f, 120f, false, accentPaint)
    }

    private fun drawGrooves(canvas: Canvas, color: Int, alphaScale: Float) {
        groovePaint.color = color
        groovePaint.alpha = (GROOVE_BASE_ALPHA * alphaScale).roundToInt().coerceIn(0, OPAQUE)
        canvas.drawPath(groovePath, groovePaint)
    }

    private fun alphaOf(fraction: Float): Int {
        return (OPAQUE * fraction.coerceIn(0f, 1f)).roundToInt()
    }

    // ------------------------------------------------------------------ timelines

    private fun isCrossfading(now: Long): Boolean {
        return crossfadeNanos > 0L && now - crossfadeStartNanos < crossfadeNanos
    }

    private fun crossfadeProgress(now: Long): Float {
        if (!isCrossfading(now)) return 1f
        val raw = (now - crossfadeStartNanos) / crossfadeNanos.toFloat()
        return FADE_INTERPOLATOR.getInterpolation(raw)
    }

    /** A record has to travel the full view width to be completely out of sight. */
    private fun slideDistance(): Float {
        return width.toFloat()
    }

    private fun currentOffset(now: Long): Float {
        if (phase == PHASE_ENTERING) {
            val p = fraction(now, enterStartNanos, enterNanos)
            return enterFrom * (1f - SLIDE_INTERPOLATOR.getInterpolation(p))
        }
        if (isSpringing(now)) {
            val p = fraction(now, springStartNanos, springNanos)
            return springFrom * (1f - SPRING_INTERPOLATOR.getInterpolation(p))
        }
        return dragOffset
    }

    private fun currentSwapScale(now: Long): Float {
        if (phase == PHASE_ENTERING) {
            val p = fraction(now, enterStartNanos, enterNanos)
            if (p <= ENTER_HOLD_BIG_FRACTION) return LIFT_MULT
            val settleP = ((p - ENTER_HOLD_BIG_FRACTION) / (1f - ENTER_HOLD_BIG_FRACTION)).coerceIn(0f, 1f)
            return 1f + (LIFT_MULT - 1f) * (1f - FADE_INTERPOLATOR.getInterpolation(settleP))
        }
        val offset = abs(currentOffset(now))
        val travel = slideDistance()
        if (offset == 0f || travel <= 0f) return 1f
        val dragShare = minOf(1f, offset / (travel * DRAG_LIFT_SPAN))
        return 1f + (LIFT_MULT - 1f) * dragShare
    }

    private fun outgoingOffset(now: Long): Float {
        val p = fraction(now, outgoingStartNanos, outgoingNanos)
        val curve = if (outgoingLiftInPlace) OUTGOING_INTERPOLATOR else SLIDE_INTERPOLATOR
        return outgoingFrom + (outgoingTo - outgoingFrom) * curve.getInterpolation(p)
    }

    private fun outgoingSwapScale(now: Long): Float {
        if (!outgoingLiftInPlace) return LIFT_MULT
        val p = fraction(now, outgoingStartNanos, outgoingNanos)
        if (p >= OUTGOING_LIFT_PEAK) return LIFT_MULT
        val liftP = (p / OUTGOING_LIFT_PEAK).coerceIn(0f, 1f)
        return 1f + (LIFT_MULT - 1f) * FADE_INTERPOLATOR.getInterpolation(liftP)
    }

    private fun isOutgoingActive(now: Long): Boolean {
        return outgoingNanos > 0L && now - outgoingStartNanos < outgoingNanos
    }

    private fun isSpringing(now: Long): Boolean {
        return springNanos > 0L && now - springStartNanos < springNanos
    }

    private fun slideFade(offset: Float): Float {
        val travel = slideDistance()
        if (travel <= 0f || offset == 0f) return 1f
        val away = minOf(1f, abs(offset) / travel)
        return 1f - SLIDE_FADE * away
    }

    private fun fraction(now: Long, start: Long, duration: Long): Float {
        if (duration <= 0L) return 1f
        return ((now - start) / duration.toFloat()).coerceIn(0f, 1f)
    }

    private fun directionSign(direction: Int): Float {
        return if (direction == TrackTransition.DIRECTION_PREVIOUS) -1f else 1f
    }

    private fun finishSlidesAt(now: Long) {
        if (outgoingNanos > 0L && !isOutgoingActive(now)) clearOutgoing()
        if (phase == PHASE_ENTERING && now >= enterStartNanos && fraction(now, enterStartNanos, enterNanos) >= 1f) {
            phase = PHASE_SHOWN
            dragOffset = 0f
        }
        if (springNanos > 0L && !isSpringing(now)) springNanos = 0L
    }

    private fun clearOutgoing() {
        outgoingArt = null
        outgoingShader = null
        outgoingPaint.shader = null
        outgoingNanos = 0L
        outgoingLiftInPlace = false
    }

    private fun clearPrevious() {
        previousArt = null
        previousShader = null
        previousPaint.shader = null
        crossfadeNanos = 0L
    }

    // ------------------------------------------------------------------ animation

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameTask)
        frameScheduled = false
        lastFrameNanos = 0L
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

    private fun needsFrames(now: Long): Boolean {
        return (spinning && !scrubActive) || rewindActive || velocity > 0f || isCrossfading(now) ||
            isOutgoingActive(now) || phase == PHASE_ENTERING || isSpringing(now) ||
            discScale != targetScale()
    }

    private fun scheduleFrame() {
        if (frameScheduled || !isShown || !needsFrames(System.nanoTime())) return
        frameScheduled = true
        lastFrameNanos = 0L
        postOnAnimation(frameTask)
    }

    private fun onFrame() {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f
        else minOf(MAX_FRAME_SECONDS, (now - lastFrameNanos) / NANOS_PER_SECOND)
        lastFrameNanos = now
        advance(dt)
        if (!isCrossfading(now)) clearPrevious()
        finishSlidesAt(now)
        invalidate()
        if (needsFrames(now) && isShown) {
            frameScheduled = true
            postOnAnimation(frameTask)
        }
    }

    private fun targetScale(): Float {
        return if (expanded) 1f else RESTING_SCALE
    }

    /** Each frame covers a fixed share of the remaining size change, then snaps when close. */
    private fun advanceScale(dt: Float) {
        val target = targetScale()
        val gap = target - discScale
        if (abs(gap) < SCALE_DONE) {
            discScale = target
            return
        }
        discScale += gap * minOf(1f, SCALE_RATE * dt)
    }

    private fun advance(dt: Float) {
        advanceScale(dt)
        if (scrubActive) {
            velocity = 0f
            return
        }
        if (rewindActive) {
            advanceRewind(dt)
            return
        }
        val target = if (spinning) DEGREES_PER_SECOND else 0f
        val rate = if (spinning) SPIN_UP_RATE else SPIN_DOWN_RATE
        velocity += (target - velocity) * minOf(1f, rate * dt)
        if (!spinning && velocity < STOP_VELOCITY) velocity = 0f
        angle = (angle + velocity * dt) % 360f
    }

    /** Each frame covers a fixed share of what is left, so the spin decelerates smoothly to rest. */
    private fun advanceRewind(dt: Float) {
        velocity = 0f
        val step = rewindRemaining * minOf(1f, REWIND_RATE * dt)
        angle = (angle + step) % 360f
        rewindRemaining -= step
        if (abs(rewindRemaining) >= REWIND_DONE_DEGREES) return
        angle = (angle + rewindRemaining) % 360f
        cancelRewind()
    }

    private fun cancelRewind() {
        rewindActive = false
        rewindRemaining = 0f
    }

    private companion object {
        const val DEGREES_PER_SECOND = 60f // one turn every 6 s
        const val SPIN_UP_RATE = 4f // 1/s, exponential approach
        const val SPIN_DOWN_RATE = 8f // ~0.6 s to stop
        const val STOP_VELOCITY = 0.5f // deg/s
        const val MAX_FRAME_SECONDS = 0.1f
        const val NANOS_PER_SECOND = 1e9f
        const val CROSSFADE_NANOS = 450000000L
        const val SLIDE_NANOS = 380000000L
        const val OUTGOING_NANOS = 420000000L
        const val ENTER_NANOS = 480000000L
        const val MIN_SLIDE_NANOS = 120000000L
        const val SPRING_NANOS = 320000000L
        const val OUTGOING_LIFT_PEAK = 0.35f
        const val ENTER_HOLD_BIG_FRACTION = 0.42f
        const val LIFT_MULT = 1.14f
        const val DRAG_LIFT_SPAN = 0.35f
        const val DISC_RADIUS_FRACTION = 0.435f
        const val REWIND_RATE = 5f // 1/s, share of the remaining turn per second
        const val REWIND_DONE_DEGREES = 0.5f
        const val MAX_SPIN_DEGREES = 1080f
        const val SLIDE_FADE = 0.7f
        const val RESTING_SCALE = 0.84f // size while paused
        const val SCALE_RATE = 9f // 1/s, share of the remaining size change per second
        const val SCALE_DONE = 0.002f
        const val PHASE_SHOWN = 0
        const val PHASE_HIDDEN = 1
        const val PHASE_ENTERING = 2
        const val DEFAULT_SIZE_DP = 160
        const val GROOVE_COUNT = 22
        const val HOLE_FRACTION = 0.09f
        const val LABEL_FRACTION = 0.36f
        const val GROOVE_INNER = 1.12f
        const val GROOVE_OUTER = 0.96f
        const val ART_GROOVE_ALPHA_SCALE = 0.22f
        const val ACCENT_INSET = 0.3f
        const val SHEEN_START_A = -70f
        const val SHEEN_START_B = 110f
        const val SHEEN_SWEEP = 35f
        const val GROOVE_BASE_ALPHA = 130
        const val SHEEN_ALPHA = 20
        const val HOLE_RIM_ALPHA = 153
        const val OPAQUE = 255

        const val COLOR_DISC_CENTER = 0xFF1C1F26.toInt()
        const val COLOR_DISC_EDGE = 0xFF07090D.toInt()
        const val COLOR_GROOVE = 0xFF2E3440.toInt()
        const val COLOR_GROOVE_ON_ART = 0xFF000000.toInt()
        const val COLOR_RIM = 0xFF3A4252.toInt()
        const val COLOR_LABEL_EMPTY = 0xFF1A2436.toInt()
        const val COLOR_HOLE_RIM = 0x99000000.toInt()
        const val COLOR_ACCENT = 0xFF00E676.toInt()
        const val COLOR_SHEEN = 0x14FFFFFF

        val FADE_INTERPOLATOR: Interpolator = DecelerateInterpolator()
        val OUTGOING_INTERPOLATOR: Interpolator = AccelerateDecelerateInterpolator()
        val SLIDE_INTERPOLATOR: Interpolator = DecelerateInterpolator(1.7f)
        val SPRING_INTERPOLATOR: Interpolator = OvershootInterpolator(1.2f)
    }
}
