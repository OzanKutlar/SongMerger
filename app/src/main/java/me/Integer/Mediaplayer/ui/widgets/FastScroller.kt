package me.Integer.Mediaplayer.ui.widgets

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lightweight fast-scroll thumb and floating section bubble attached as an ItemDecoration
 * and OnItemTouchListener to a [RecyclerView]. Appears automatically when the list has at
 * least [MIN_ITEMS_FOR_THUMB] items and is scrolled or dragged along the right edge.
 */
class FastScroller private constructor(
    private val list: RecyclerView,
    private val sectionOf: (Int) -> String
) : RecyclerView.ItemDecoration(), RecyclerView.OnItemTouchListener {

    private val density = list.resources.displayMetrics.density
    private val thumbWidth = 6f * density
    private val thumbTouchWidth = 36f * density
    private val thumbHeight = 44f * density
    private val thumbMarginEnd = 4f * density
    private val bubbleSize = 56f * density
    private val bubbleGap = 16f * density

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF6D00.toInt()
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26FFFFFF
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE282024.toInt()
    }
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF6D00.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val thumbRect = RectF()
    private val bubbleRect = RectF()
    private var dragging = false
    private var visibleAlpha = 0f
    private var currentSection = ""
    private var dragFraction = 0f

    private val fadeTask = Runnable {
        if (!dragging) {
            visibleAlpha = 0f
            list.invalidate()
        }
    }

    init {
        list.addItemDecoration(this)
        list.addOnItemTouchListener(this)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0 && itemCount() >= MIN_ITEMS_FOR_THUMB) {
                    showTemporarily()
                }
            }
        })
    }

    private fun itemCount(): Int = list.adapter?.itemCount ?: 0

    private fun showTemporarily() {
        visibleAlpha = 1f
        list.removeCallbacks(fadeTask)
        list.postDelayed(fadeTask, HIDE_DELAY_MS)
        list.invalidate()
    }

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val total = itemCount()
        if (total < MIN_ITEMS_FOR_THUMB || visibleAlpha <= 0f) return
        val topBound = parent.paddingTop + 8f * density
        val bottomBound = parent.height - parent.paddingBottom - 8f * density
        val usableHeight = bottomBound - topBound - thumbHeight
        if (usableHeight <= 0f) return

        val fraction = if (dragging) {
            dragFraction
        } else {
            val manager = parent.layoutManager as? LinearLayoutManager ?: return
            val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
            (first.toFloat() / max(1, total - 1)).coerceIn(0f, 1f)
        }

        val thumbTop = topBound + usableHeight * fraction
        val right = parent.width - thumbMarginEnd
        val left = right - if (dragging) thumbWidth * 1.4f else thumbWidth
        thumbRect.set(left, thumbTop, right, thumbTop + thumbHeight)

        canvas.drawRoundRect(
            right - thumbWidth,
            topBound,
            right,
            bottomBound,
            thumbWidth / 2f,
            thumbWidth / 2f,
            trackPaint
        )
        thumbPaint.alpha = (255 * visibleAlpha).roundToInt()
        canvas.drawRoundRect(thumbRect, thumbWidth, thumbWidth, thumbPaint)

        if (dragging && currentSection.isNotEmpty()) {
            val bubbleRight = left - bubbleGap
            val bubbleLeft = bubbleRight - bubbleSize
            val bubbleCenterY = thumbRect.centerY().coerceIn(topBound + bubbleSize / 2f, bottomBound - bubbleSize / 2f)
            bubbleRect.set(
                bubbleLeft,
                bubbleCenterY - bubbleSize / 2f,
                bubbleRight,
                bubbleCenterY + bubbleSize / 2f
            )
            val corner = bubbleSize / 2f
            canvas.drawRoundRect(bubbleRect, corner, corner, bubblePaint)
            canvas.drawRoundRect(bubbleRect, corner, corner, bubbleStrokePaint)
            val baseline = bubbleRect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(currentSection, bubbleRect.centerX(), baseline, textPaint)
        }
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (itemCount() < MIN_ITEMS_FOR_THUMB) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isNearThumb(e.x, e.y)) {
                    startDrag(e.y)
                    return true
                }
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> startDrag(e.y)
            MotionEvent.ACTION_MOVE -> if (dragging) updateDrag(e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDrag()
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

    private fun isNearThumb(x: Float, y: Float): Boolean {
        if (visibleAlpha <= 0f) return false
        if (x < list.width - thumbTouchWidth) return false
        return y >= thumbRect.top - 16f * density && y <= thumbRect.bottom + 16f * density
    }

    private fun startDrag(y: Float) {
        dragging = true
        visibleAlpha = 1f
        list.removeCallbacks(fadeTask)
        list.parent?.requestDisallowInterceptTouchEvent(true)
        updateDrag(y)
    }

    private fun updateDrag(y: Float) {
        val total = itemCount()
        if (total <= 0) return
        val topBound = list.paddingTop + 8f * density + thumbHeight / 2f
        val bottomBound = list.height - list.paddingBottom - 8f * density - thumbHeight / 2f
        val span = max(1f, bottomBound - topBound)
        dragFraction = ((y - topBound) / span).coerceIn(0f, 1f)
        val targetIndex = (dragFraction * (total - 1)).roundToInt().coerceIn(0, total - 1)
        currentSection = sectionOf(targetIndex)
        (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(targetIndex, 0)
        list.invalidate()
    }

    private fun stopDrag() {
        if (!dragging) return
        dragging = false
        showTemporarily()
    }

    companion object {
        private const val MIN_ITEMS_FOR_THUMB = 20
        private const val HIDE_DELAY_MS = 1500L

        fun attach(list: RecyclerView, sectionOf: (Int) -> String): FastScroller {
            return FastScroller(list, sectionOf)
        }

        /** First uppercase letter or '#' for digits/symbols. */
        fun initialOf(text: String?): String {
            val ch = text?.trim()?.firstOrNull()?.uppercaseChar() ?: return "#"
            return if (ch.isLetter()) ch.toString() else "#"
        }
    }
}
