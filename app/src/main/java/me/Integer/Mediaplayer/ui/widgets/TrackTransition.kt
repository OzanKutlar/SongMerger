package me.Integer.Mediaplayer.ui.widgets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import kotlin.math.roundToLong

/**
 * Fade-out / swap / fade-in for the track text block. The view slides out one way and
 * back in from the other. The swap runs the most recent apply action, so metadata that
 * arrives in pieces, or rapid skips, give one smooth transition instead of a stutter.
 * Main thread only.
 */
class TrackTransition(private val view: View, density: Float) {

    private val slidePx = SLIDE_DP * density
    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)

    private var pendingApply: Runnable? = null
    private var phase = PHASE_IDLE
    private var direction = DIRECTION_NEXT
    private var startAlpha = 1f
    private var startTranslation = 0f
    private var cancelled = false

    init {
        animator.addUpdateListener { applyFrame(it.animatedFraction) }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                cancelled = false
            }

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) onPhaseEnd()
            }
        })
    }

    val isFadingOut: Boolean
        get() = phase == PHASE_OUT

    /**
     * Transitions to new content. [apply] runs once the old content is invisible; calling
     * again before that only replaces the pending apply and direction.
     */
    fun run(direction: Int, apply: Runnable) {
        pendingApply = apply
        this.direction = if (direction == DIRECTION_PREVIOUS) DIRECTION_PREVIOUS else DIRECTION_NEXT
        if (phase == PHASE_OUT) return
        startPhase(PHASE_OUT)
    }

    /** Ends any transition at once: pending content is applied and the view fully shown. */
    fun finish() {
        animator.cancel()
        phase = PHASE_IDLE
        val apply = pendingApply
        pendingApply = null
        apply?.run()
        view.alpha = 1f
        view.translationX = 0f
        setHardwareLayer(false)
    }

    private fun startPhase(next: Int) {
        animator.cancel()
        phase = next
        startAlpha = view.alpha
        startTranslation = view.translationX
        // Half-visible text leaves faster.
        animator.duration = if (next == PHASE_OUT) maxOf(1L, (OUT_MS * startAlpha).roundToLong()) else IN_MS
        animator.interpolator = if (next == PHASE_OUT) OUT_INTERPOLATOR else IN_INTERPOLATOR
        setHardwareLayer(true)
        animator.start()
    }

    private fun applyFrame(fraction: Float) {
        if (phase == PHASE_OUT) {
            val exit = -direction * slidePx
            view.alpha = startAlpha * (1f - fraction)
            view.translationX = startTranslation + (exit - startTranslation) * fraction
        } else if (phase == PHASE_IN) {
            val enter = direction * slidePx
            view.alpha = fraction
            view.translationX = enter * (1f - fraction)
        }
    }

    private fun onPhaseEnd() {
        if (phase == PHASE_OUT) {
            val apply = pendingApply
            pendingApply = null
            apply?.run()
            startPhase(PHASE_IN)
            return
        }
        phase = PHASE_IDLE
        setHardwareLayer(false)
    }

    private fun setHardwareLayer(enabled: Boolean) {
        val type = if (enabled) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (view.layerType != type) view.setLayerType(type, null)
    }

    companion object {
        /** Exit to the left, enter from the right. */
        const val DIRECTION_NEXT = 1

        /** Exit to the right, enter from the left. */
        const val DIRECTION_PREVIOUS = -1

        private const val PHASE_IDLE = 0
        private const val PHASE_OUT = 1
        private const val PHASE_IN = 2
        private const val OUT_MS = 150L
        private const val IN_MS = 220L
        private const val SLIDE_DP = 12f
        private val OUT_INTERPOLATOR: Interpolator = AccelerateInterpolator()
        private val IN_INTERPOLATOR: Interpolator = DecelerateInterpolator()
    }
}
