package me.Integer.Mediaplayer.ui.widgets

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Touch handling for the vinyl. A tap clicks (play/pause through the view's own click
 * listener); a horizontal drag is reported as an offset, then as a release that either
 * commits (far enough, or a quick flick) or not. Holding the disc still for the long-press
 * timeout starts scrubbing: finger rotation around the centre is reported in degrees
 * (positive = clockwise) until the finger lifts. The disc squeezes while pressed and ticks
 * once when a drag is far enough to count. Moving or seeking the record is up to the callback.
 */
class DiscGestures private constructor(
    private val view: View,
    private val callback: Callback
) : View.OnTouchListener {

    interface Callback {
        /** Horizontal finger travel since touch-down, in px (positive = right). */
        fun onDrag(offsetPx: Float)

        /** End of a drag; [commit] is true for a long enough drag or a flick. */
        fun onRelease(offsetPx: Float, commit: Boolean)

        /**
         * The finger was held still for the long-press timeout.
         *
         * @return true to start scrubbing, false when seeking is not possible right now
         */
        fun onScrubStart(): Boolean

        /** Finger rotation around the disc centre since the last report; positive is clockwise. */
        fun onScrub(deltaDegrees: Float)

        /** End of scrubbing; [commit] is false when the system cancelled the gesture. */
        fun onScrubEnd(commit: Boolean)
    }

    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private val density = view.resources.displayMetrics.density
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val screenLocation = IntArray(2)
    private val longPressTask = Runnable { onLongPress() }

    private var velocity: VelocityTracker? = null
    private var state = STATE_IDLE
    private var downX = 0f
    private var downY = 0f
    private var armed = false
    private var lastAngle = 0f
    private var hasAngle = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(touched: View, event: MotionEvent): Boolean {
        if (!view.isEnabled) return false
        track(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> onCancel()
            else -> Unit
        }
        return true
    }

    // ------------------------------------------------------------------ phases

    private fun onDown(event: MotionEvent) {
        downX = event.rawX
        downY = event.rawY
        state = STATE_PRESSED
        armed = false
        hasAngle = false
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        view.animate().cancel()
        animateScale(PRESSED_SCALE, PRESS_MS, PRESS_INTERPOLATOR)
        cancelLongPress()
        view.postDelayed(longPressTask, longPressMs)
    }

    private fun onMove(event: MotionEvent) {
        if (state == STATE_SCRUBBING) {
            scrub(event)
            return
        }
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        if (state == STATE_PRESSED) classify(dx, dy)
        if (state == STATE_SWIPING) drag(dx)
    }

    private fun onUp(event: MotionEvent) {
        if (state == STATE_SCRUBBING) {
            finish()
            callback.onScrubEnd(true)
            return
        }
        if (state == STATE_SWIPING) {
            val dx = event.rawX - downX
            val commit = abs(dx) >= commitDistance() || isFling(dx)
            finish()
            callback.onRelease(dx, commit)
            return
        }
        val tap = state == STATE_PRESSED && isInside(event)
        animateScale(1f, RELEASE_MS, RELEASE_INTERPOLATOR)
        finish()
        if (tap) view.performClick()
    }

    private fun onCancel() {
        val wasSwiping = state == STATE_SWIPING
        val wasScrubbing = state == STATE_SCRUBBING
        animateScale(1f, RELEASE_MS, RELEASE_INTERPOLATOR)
        finish()
        if (wasSwiping) callback.onRelease(0f, false)
        if (wasScrubbing) callback.onScrubEnd(false)
    }

    /** Decides once per gesture: horizontal beyond the slop is a swipe, vertical is not ours. */
    private fun classify(dx: Float, dy: Float) {
        if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
            state = STATE_SWIPING
            cancelLongPress()
            disallowParentIntercept()
            animateScale(1f, RELEASE_MS, RELEASE_INTERPOLATOR)
        } else if (abs(dy) > touchSlop) {
            state = STATE_SCROLLED
            cancelLongPress()
            animateScale(1f, RELEASE_MS, RELEASE_INTERPOLATOR)
        }
    }

    private fun drag(dx: Float) {
        callback.onDrag(dx)
        val reached = abs(dx) >= commitDistance()
        if (reached == armed) return
        armed = reached
        if (reached) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // ------------------------------------------------------------------ scrubbing

    /** The finger stayed put for the long-press timeout: start scrubbing if the callback agrees. */
    private fun onLongPress() {
        if (state != STATE_PRESSED) return
        animateScale(1f, RELEASE_MS, RELEASE_INTERPOLATOR)
        if (!callback.onScrubStart()) {
            state = STATE_HELD
            return
        }
        state = STATE_SCRUBBING
        hasAngle = false
        disallowParentIntercept()
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun scrub(event: MotionEvent) {
        val angle = fingerAngle(event)
        if (angle.isNaN()) {
            hasAngle = false
            return
        }
        if (hasAngle) {
            val delta = ScrubMath.unwrapDelta(lastAngle, angle)
            if (delta != 0f) callback.onScrub(delta)
        }
        lastAngle = angle
        hasAngle = true
    }

    /**
     * Finger angle around the disc centre in screen degrees (grows clockwise, as screen y
     * points down), or NaN inside the dead zone around the spindle.
     */
    private fun fingerAngle(event: MotionEvent): Float {
        view.getLocationOnScreen(screenLocation)
        val halfWidth = view.width * view.scaleX / 2f
        val halfHeight = view.height * view.scaleY / 2f
        val rx = event.rawX - (screenLocation[0] + halfWidth)
        val ry = event.rawY - (screenLocation[1] + halfHeight)
        val deadZone = minOf(halfWidth, halfHeight) * SCRUB_DEAD_ZONE_FRACTION
        if (rx * rx + ry * ry < deadZone * deadZone) return Float.NaN
        return Math.toDegrees(atan2(ry.toDouble(), rx.toDouble())).toFloat()
    }

    // ------------------------------------------------------------------ helpers

    private fun commitDistance(): Float {
        return maxOf(MIN_COMMIT_DP * density, view.width * COMMIT_FRACTION)
    }

    private fun isFling(dx: Float): Boolean {
        val tracker = velocity ?: return false
        if (dx == 0f) return false
        tracker.computeCurrentVelocity(VELOCITY_UNITS_MS)
        val vx = tracker.xVelocity
        return abs(vx) >= FLING_DP_PER_SECOND * density && (vx > 0f) == (dx > 0f)
    }

    private fun isInside(event: MotionEvent): Boolean {
        return event.x >= 0f && event.y >= 0f && event.x <= view.width && event.y <= view.height
    }

    private fun disallowParentIntercept() {
        view.parent?.requestDisallowInterceptTouchEvent(true)
    }

    /** Tracks in screen coordinates: the disc itself moves under the finger. */
    private fun track(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            recycleVelocity()
            velocity = VelocityTracker.obtain()
        }
        val tracker = velocity ?: return
        val screen = MotionEvent.obtain(event)
        screen.setLocation(event.rawX, event.rawY)
        tracker.addMovement(screen)
        screen.recycle()
    }

    private fun animateScale(scale: Float, duration: Long, interpolator: Interpolator) {
        view.animate().scaleX(scale).scaleY(scale)
            .setDuration(duration).setInterpolator(interpolator).start()
    }

    private fun cancelLongPress() {
        view.removeCallbacks(longPressTask)
    }

    private fun finish() {
        cancelLongPress()
        state = STATE_IDLE
        armed = false
        hasAngle = false
        recycleVelocity()
    }

    private fun recycleVelocity() {
        velocity?.recycle()
        velocity = null
    }

    companion object {
        private const val STATE_IDLE = 0
        private const val STATE_PRESSED = 1
        private const val STATE_SWIPING = 2
        private const val STATE_SCROLLED = 3
        private const val STATE_SCRUBBING = 4

        /** Long press reached but refused: the rest of the gesture is ignored (and is no tap). */
        private const val STATE_HELD = 5

        private const val PRESSED_SCALE = 0.95f
        private const val PRESS_MS = 90L
        private const val RELEASE_MS = 260L
        private const val COMMIT_FRACTION = 0.22f
        private const val MIN_COMMIT_DP = 48f
        private const val FLING_DP_PER_SECOND = 700f
        private const val VELOCITY_UNITS_MS = 1000

        /** Near the spindle the finger angle is meaningless; ignore that spot while scrubbing. */
        private const val SCRUB_DEAD_ZONE_FRACTION = 0.15f
        private val PRESS_INTERPOLATOR: Interpolator = DecelerateInterpolator()
        private val RELEASE_INTERPOLATOR: Interpolator = OvershootInterpolator(2.5f)

        @SuppressLint("ClickableViewAccessibility")
        fun attach(view: View, callback: Callback) {
            view.setOnTouchListener(DiscGestures(view, callback))
        }
    }
}
