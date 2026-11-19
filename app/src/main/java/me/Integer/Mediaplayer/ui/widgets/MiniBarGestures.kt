package me.Integer.Mediaplayer.ui.widgets

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import kotlin.math.abs

/**
 * Swipes on the mini player: up opens the full player, left skips forward, right goes back.
 * A sideways swipe drags the bar a little so it feels attached to the finger. Taps and the
 * buttons inside the bar keep working, because a touch is only claimed once it becomes a swipe.
 */
internal class MiniBarGestures private constructor(
    private val bar: View,
    private val callback: Callback
) : View.OnTouchListener {

    interface Callback {
        fun onSwipeUp()

        fun onSwipeLeft()

        fun onSwipeRight()
    }

    private val touchSlop = ViewConfiguration.get(bar.context).scaledTouchSlop.toFloat()
    private val density = bar.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var swiping = false
    private var horizontal = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_MOVE -> onMove(view, event)
            MotionEvent.ACTION_UP -> onUp(view, event)
            MotionEvent.ACTION_CANCEL -> onCancel()
            else -> swiping
        }
    }

    private fun onDown(event: MotionEvent): Boolean {
        downX = event.rawX
        downY = event.rawY
        downTime = event.eventTime
        swiping = false
        return false
    }

    private fun onMove(view: View, event: MotionEvent): Boolean {
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        if (!swiping) classify(view, event, dx, dy)
        if (swiping && horizontal) bar.translationX = dx * DRAG_SHARE
        return swiping
    }

    /** Sideways or upwards past the slop is a swipe; the view's own press is then cancelled. */
    private fun classify(view: View, event: MotionEvent, dx: Float, dy: Float) {
        val sideways = abs(dx) > touchSlop && abs(dx) > abs(dy)
        val upwards = -dy > touchSlop && abs(dy) > abs(dx)
        if (!sideways && !upwards) return
        swiping = true
        horizontal = sideways
        view.parent?.requestDisallowInterceptTouchEvent(true)
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        view.onTouchEvent(cancel)
        cancel.recycle()
    }

    private fun onUp(view: View, event: MotionEvent): Boolean {
        if (!swiping) return false
        swiping = false
        settle()
        val quick = event.eventTime - downTime <= QUICK_MS
        if (horizontal) {
            val dx = event.rawX - downX
            if (abs(dx) < needed(quick, COMMIT_DP)) return true
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (dx < 0f) callback.onSwipeLeft() else callback.onSwipeRight()
        } else {
            val dy = event.rawY - downY
            if (-dy < needed(quick, UP_COMMIT_DP)) return true
            callback.onSwipeUp()
        }
        return true
    }

    private fun onCancel(): Boolean {
        val was = swiping
        swiping = false
        if (was) settle()
        return was
    }

    private fun needed(quick: Boolean, distanceDp: Float): Float {
        return (if (quick) QUICK_COMMIT_DP else distanceDp) * density
    }

    private fun settle() {
        if (bar.translationX == 0f) return
        bar.animate().translationX(0f).setDuration(SETTLE_MS).setInterpolator(SETTLE_INTERPOLATOR).start()
    }

    companion object {
        private const val DRAG_SHARE = 0.35f
        private const val COMMIT_DP = 72f
        private const val UP_COMMIT_DP = 40f
        private const val QUICK_COMMIT_DP = 28f
        private const val QUICK_MS = 250L
        private const val SETTLE_MS = 280L
        private val SETTLE_INTERPOLATOR = OvershootInterpolator(1.6f)

        @SuppressLint("ClickableViewAccessibility")
        fun attach(bar: View, callback: Callback) {
            bar.setOnTouchListener(MiniBarGestures(bar, callback))
        }
    }
}
