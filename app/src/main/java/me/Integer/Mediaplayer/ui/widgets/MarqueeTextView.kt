package me.Integer.Mediaplayer.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Single-line label that scrolls text which does not fit: a short pause, a smooth scroll
 * to the left, and a second copy that follows after a gap, so the text keeps flowing in
 * from the right. Unlike TextView's marquee it only restarts when the text changes,
 * never because something else in the layout was re-measured.
 *
 * Reads android:textSize, android:textStyle and android:textColor from XML.
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val gapPx: Float
    private val speedPx: Float
    private val ascent: Float
    private val descent: Float

    private var content = ""
    private var textWidth = 0f
    private var offset = 0f
    private var overflowing = false
    private var frameScheduled = false
    private var lastFrameNanos = 0L
    private var pauseUntilNanos = 0L

    private val frameTask = Runnable {
        frameScheduled = false
        onFrame()
    }

    init {
        val density = resources.displayMetrics.density
        gapPx = GAP_DP * density
        speedPx = SPEED_DP_PER_SECOND * density
        var size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_TEXT_SP, resources.displayMetrics)
        var style = Typeface.NORMAL
        var color = Color.WHITE
        if (attrs != null) {
            val values = context.obtainStyledAttributes(attrs, TEXT_ATTRS)
            try {
                size = values.getDimension(ATTR_SIZE, size)
                style = values.getInt(ATTR_STYLE, style)
                color = values.getColor(ATTR_COLOR, color)
            } finally {
                values.recycle()
            }
        }
        paint.textSize = size
        paint.color = color
        paint.typeface = if (style and STYLE_BOLD != 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val metrics = paint.fontMetrics
        ascent = metrics.ascent
        descent = metrics.descent
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength((FADE_DP * density).roundToInt())
    }

    // ------------------------------------------------------------------ public API

    /** Changing the text restarts the scroll; setting the same text is a no-op. */
    fun setText(value: CharSequence?) {
        val next = value?.toString().orEmpty()
        if (next == content) return
        content = next
        textWidth = paint.measureText(content)
        contentDescription = content
        if (isWrapWidth()) requestLayout()
        refreshOverflow(true)
    }

    fun getText(): CharSequence {
        return content
    }

    private fun isWrapWidth(): Boolean {
        return layoutParams?.width == ViewGroup.LayoutParams.WRAP_CONTENT
    }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = ceil(textWidth).toInt() + paddingLeft + paddingRight
        val desiredHeight = ceil(descent - ascent).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshOverflow(false)
    }

    /** Only resets the scroll when the text changed or it started/stopped overflowing. */
    private fun refreshOverflow(restart: Boolean) {
        val available = (width - paddingLeft - paddingRight).toFloat()
        val overflow = available > 0f && textWidth > available
        if (restart || overflow != overflowing) {
            overflowing = overflow
            offset = 0f
            pauseUntilNanos = System.nanoTime() + START_PAUSE_NANOS
        }
        invalidate()
        scheduleFrame()
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (content.isEmpty()) return
        val left = paddingLeft
        val right = width - paddingRight
        val contentHeight = (height - paddingTop - paddingBottom).toFloat()
        val baseline = paddingTop + (contentHeight - (descent - ascent)) / 2f - ascent
        val x = left - offset
        canvas.save()
        canvas.clipRect(left, 0, right, height)
        canvas.drawText(content, x, baseline, paint)
        if (overflowing) canvas.drawText(content, x + textWidth + gapPx, baseline, paint)
        canvas.restore()
    }

    override fun getLeftFadingEdgeStrength(): Float {
        return if (overflowing && offset > 0f) 1f else 0f
    }

    override fun getRightFadingEdgeStrength(): Float {
        return if (overflowing) 1f else 0f
    }

    // ------------------------------------------------------------------ animation

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

    private fun scheduleFrame() {
        if (frameScheduled || !overflowing || !isShown) return
        frameScheduled = true
        lastFrameNanos = 0L
        postOnAnimation(frameTask)
    }

    private fun onFrame() {
        if (!overflowing || !isShown) return
        val now = System.nanoTime()
        if (now < pauseUntilNanos) {
            // Sleep through the pause instead of spinning frames.
            lastFrameNanos = 0L
            frameScheduled = true
            postOnAnimationDelayed(frameTask, (pauseUntilNanos - now) / NANOS_PER_MILLI + 1L)
            return
        }
        val dt = if (lastFrameNanos == 0L) 0f
        else minOf(MAX_FRAME_SECONDS, (now - lastFrameNanos) / NANOS_PER_SECOND)
        lastFrameNanos = now
        advance(dt, now)
        invalidate()
        frameScheduled = true
        postOnAnimation(frameTask)
    }

    private fun advance(dt: Float, now: Long) {
        val cycle = textWidth + gapPx
        offset += speedPx * dt
        if (offset < cycle) return
        // The second copy now sits where the first one started: snap back and pause again.
        offset = 0f
        pauseUntilNanos = now + START_PAUSE_NANOS
    }

    private companion object {
        // Must stay sorted by attribute id: textSize < textStyle < textColor.
        val TEXT_ATTRS = intArrayOf(
            android.R.attr.textSize,
            android.R.attr.textStyle,
            android.R.attr.textColor
        )
        const val ATTR_SIZE = 0
        const val ATTR_STYLE = 1
        const val ATTR_COLOR = 2
        const val STYLE_BOLD = 1

        const val DEFAULT_TEXT_SP = 22f
        const val SPEED_DP_PER_SECOND = 30f
        const val GAP_DP = 56f
        const val FADE_DP = 20f
        const val START_PAUSE_NANOS = 1500000000L
        const val NANOS_PER_MILLI = 1000000L
        const val NANOS_PER_SECOND = 1e9f
        const val MAX_FRAME_SECONDS = 0.1f
    }
}
