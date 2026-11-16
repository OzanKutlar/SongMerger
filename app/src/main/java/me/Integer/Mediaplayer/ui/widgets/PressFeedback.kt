package me.Integer.Mediaplayer.ui.widgets

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator

/**
 * Squeeze-and-spring feedback for a touch target: the target shrinks while the finger is
 * down and springs back with a slight overshoot on release. Fires the system key haptic
 * on press, which follows the phone's touch-vibration setting. Never consumes the touch,
 * so clicks, disabled states and accessibility work exactly as before.
 */
class PressFeedback private constructor(
    private val target: View,
    private val pressedScale: Float
) : View.OnTouchListener {

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (!view.isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                animateTo(pressedScale, PRESS_MS, PRESS_INTERPOLATOR)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animateTo(1f, RELEASE_MS, RELEASE_INTERPOLATOR)
            else -> Unit
        }
        return false
    }

    private fun animateTo(scale: Float, duration: Long, interpolator: Interpolator) {
        target.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .start()
    }

    companion object {
        const val DEFAULT_PRESSED_SCALE = 0.86f
        private const val PRESS_MS = 90L
        private const val RELEASE_MS = 260L
        private val PRESS_INTERPOLATOR: Interpolator = DecelerateInterpolator()
        private val RELEASE_INTERPOLATOR: Interpolator = OvershootInterpolator(2.5f)

        /** Feedback on [cell], animating [target] (the cell itself when null). */
        @SuppressLint("ClickableViewAccessibility")
        fun attach(cell: View, target: View? = null, pressedScale: Float = DEFAULT_PRESSED_SCALE) {
            val scale = pressedScale.coerceIn(0.5f, 1f)
            cell.setOnTouchListener(PressFeedback(target ?: cell, scale))
        }
    }
}
