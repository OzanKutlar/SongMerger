package me.Integer.Mediaplayer.ui.widgets

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Glides a list to a row and centres it: a slow start, a fast middle and a slow arrival.
 * It moves through row positions rather than pixels, so it is equally smooth over five rows
 * or five thousand. A touch on the list skips to the end. Rows must share one height, as
 * song rows do. Main thread only.
 */
internal class ListGlide private constructor(
    private val list: RecyclerView,
    private val manager: LinearLayoutManager,
    private val rowHeight: Int,
    private val from: Float,
    private val to: Float,
    private val onDone: () -> Unit
) {

    private var animator: ValueAnimator? = null

    /** Ending from inside touch dispatch would change the listener list mid-loop, so it is posted. */
    private val skipOnTouch = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) rv.post { animator?.end() }
            return false
        }
    }

    private fun begin() {
        list.stopScroll()
        list.addOnItemTouchListener(skipOnTouch)
        val glide = ValueAnimator.ofFloat(0f, 1f)
        glide.duration = GlideMath.durationMs(abs(to - from))
        glide.interpolator = LinearInterpolator()
        glide.addUpdateListener { scrollTo(GlideMath.position(from, to, it.animatedFraction)) }
        glide.doOnEnd { if (animator === glide) finish() }
        animator = glide
        glide.start()
    }

    /** Stops where it is; [onDone] is not called. */
    fun cancel() {
        val running = animator
        animator = null
        running?.cancel()
        list.removeOnItemTouchListener(skipOnTouch)
    }

    /** Lands exactly on the target and reports once the rows there are laid out. */
    private fun finish() {
        animator = null
        list.removeOnItemTouchListener(skipOnTouch)
        if (!list.isAttachedToWindow) {
            onDone()
            return
        }
        scrollTo(to)
        list.doOnNextLayout { onDone() }
        list.requestLayout()
    }

    private fun scrollTo(position: Float) {
        val index = floor(position).toInt().coerceAtLeast(0)
        val offset = -((position - index) * rowHeight).roundToInt()
        manager.scrollToPositionWithOffset(index, offset)
    }

    companion object {

        /** True when [row] is laid out and not cut off, nor hidden behind the bottom padding. */
        fun isRowVisible(list: RecyclerView, row: Int): Boolean {
            val view = list.layoutManager?.findViewByPosition(row) ?: return false
            return view.top >= list.paddingTop && view.bottom <= list.height - list.paddingBottom
        }

        /**
         * Starts gliding [list] so that [row] is centred, then calls [onDone].
         *
         * @return null when the list cannot glide (not laid out, or the row does not exist)
         */
        fun start(list: RecyclerView, row: Int, onDone: () -> Unit): ListGlide? {
            val manager = list.layoutManager as? LinearLayoutManager ?: return null
            val count = list.adapter?.itemCount ?: 0
            if (row !in 0 until count) return null
            val first = manager.findFirstVisibleItemPosition()
            if (first == RecyclerView.NO_POSITION) return null
            val firstView = manager.findViewByPosition(first) ?: return null
            val rowHeight = firstView.height
            val viewport = list.height - list.paddingTop - list.paddingBottom
            if (rowHeight <= 0 || viewport <= 0) return null
            val from = GlideMath.currentPosition(first, firstView.top - list.paddingTop, rowHeight)
            val to = GlideMath.centredPosition(row, rowHeight, viewport, count)
            return ListGlide(list, manager, rowHeight, from, to, onDone).also { it.begin() }
        }
    }
}
