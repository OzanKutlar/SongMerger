package me.Integer.Mediaplayer.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.RectF
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.ui.widgets.ExpandCardView
import me.Integer.Mediaplayer.ui.widgets.Motion
import kotlin.math.pow

/**
 * Grows the mini player into the full player: a card in the bar's colour stretches from the
 * bar to the whole screen, its top edge leading so it rises, its corners straightening and
 * its tint deepening into the player's background. Then the player opens underneath and the
 * card fades away. Main thread only.
 */
internal class PlayerExpand(
    private val layer: FrameLayout,
    private val miniBar: MiniPlayerBar
) {

    private val context = layer.context
    private val backgroundColor = ContextCompat.getColor(context, R.color.background)
    private val blend = ArgbEvaluator()
    private val from = RectF()
    private val to = RectF()
    private val frame = RectF()
    private var card: ExpandCardView? = null
    private var grow: ValueAnimator? = null

    /**
     * @param commit opens the player once the card fills the screen; false if it could not
     * @return false when the mini player is not on screen; the caller opens the player itself
     */
    fun expand(commit: () -> Boolean): Boolean {
        if (Motion.durationScale(context) <= 0f || !miniBar.isOnScreen) return false
        cancel()
        if (layer.width == 0 || !miniBar.boundsIn(layer, from)) return false
        to.set(0f, 0f, layer.width.toFloat(), layer.height.toFloat())
        val startColor = miniBar.color
        val radius = miniBar.cornerPx
        val view = ExpandCardView(context)
        layer.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        view.set(from, radius, startColor)
        card = view
        layer.isClickable = true
        miniBar.hideForExpand()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = GROW_MS
        anim.interpolator = GROW_CURVE
        anim.addUpdateListener { step(view, it.animatedValue as Float, startColor, radius) }
        anim.doOnEnd { if (grow === anim) onGrown(view, commit) }
        grow = anim
        anim.start()
        return true
    }

    /** Ends at once and gives the mini player back. */
    fun cancel() {
        val running = grow
        grow = null
        running?.cancel()
        if (card != null) clear(restoreBar = true)
    }

    private fun step(view: ExpandCardView, progress: Float, startColor: Int, radius: Float) {
        val rise = progress.pow(RISE_EXPONENT)
        frame.set(
            lerp(from.left, to.left, progress),
            lerp(from.top, to.top, rise),
            lerp(from.right, to.right, progress),
            lerp(from.bottom, to.bottom, progress)
        )
        view.set(frame, radius * (1f - progress), blend.evaluate(progress, startColor, backgroundColor) as Int)
    }

    /** The card covers the screen: open the player under it, then let the card fade away. */
    private fun onGrown(view: ExpandCardView, commit: () -> Boolean) {
        grow = null
        if (!commit()) {
            cancel()
            return
        }
        view.animate().alpha(0f)
            .setStartDelay(FADE_DELAY_MS)
            .setDuration(FADE_MS)
            .withEndAction { if (card === view) clear(restoreBar = false) }
            .start()
    }

    private fun clear(restoreBar: Boolean) {
        card?.let {
            it.animate().cancel()
            layer.removeView(it)
        }
        card = null
        layer.isClickable = false
        if (restoreBar) miniBar.restoreAfterExpand()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private companion object {
        const val GROW_MS = 420L
        const val FADE_DELAY_MS = 90L
        const val FADE_MS = 240L

        /** Below 1 the top edge runs ahead of the others, so the card rises as it grows. */
        const val RISE_EXPONENT = 0.7f
        val GROW_CURVE: Interpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)
    }
}
