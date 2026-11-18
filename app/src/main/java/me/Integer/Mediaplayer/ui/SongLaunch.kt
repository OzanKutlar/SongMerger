package me.Integer.Mediaplayer.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.RectF
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.animation.doOnEnd
import androidx.core.view.drawToBitmap
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.ui.widgets.ListGlide
import me.Integer.Mediaplayer.ui.widgets.Motion
import me.Integer.Mediaplayer.ui.widgets.ViewGeometry
import kotlin.math.roundToInt

/**
 * The song launch: the list glides to the song when it is off screen, the row's cover hops
 * out, falls with gravity onto the mini player's record (shrinking, spinning and rounding
 * into a disc), the mini player squashes as it lands, and then grows into the full player.
 *
 * Playback starts as the cover hops, so the music never waits for the animation. Everything
 * is drawn on [layer]; the list itself only scrolls. Main thread only.
 */
internal class SongLaunch(
    private val layer: FrameLayout,
    private val miniBar: MiniPlayerBar,
    private val openPlayer: () -> Unit
) {

    private val context = layer.context
    private val density = context.resources.displayMetrics.density
    private val start = RectF()
    private val target = RectF()
    private var glide: ListGlide? = null
    private var flight: ValueAnimator? = null
    private var flyer: ImageView? = null
    private var hiddenThumb: View? = null
    private var pendingPlay: (() -> Unit)? = null
    private val expandTask = Runnable { openPlayer() }

    /** Plays with the animation from [row] of [list]; a launch still running is replaced. */
    fun launch(list: RecyclerView, row: Int, play: () -> Unit) {
        cancel(runPending = false)
        pendingPlay = play
        if (Motion.durationScale(context) <= 0f || !list.isAttachedToWindow || layer.width == 0) {
            playAndOpen()
            return
        }
        if (ListGlide.isRowVisible(list, row)) {
            hop(list, row)
            return
        }
        val started = ListGlide.start(list, row) {
            glide = null
            hop(list, row)
        }
        if (started == null) hop(list, row) else glide = started
    }

    /**
     * Ends everything at once and puts every view back.
     *
     * @param runPending true starts a song that was still waiting for its glide
     */
    fun cancel(runPending: Boolean) {
        glide?.cancel()
        glide = null
        val running = flight
        flight = null
        running?.cancel()
        layer.removeCallbacks(expandTask)
        removeFlyer()
        miniBar.releaseHold()
        if (runPending) runPending() else pendingPlay = null
    }

    // ------------------------------------------------------------------ hop and drop

    private fun hop(list: RecyclerView, row: Int) {
        val thumb = list.findViewHolderForAdapterPosition(row)?.itemView?.findViewById<View>(R.id.trackThumb)
        val image = thumb?.let { snapshot(it) }
        if (thumb == null || image == null || !ViewGeometry.rectIn(thumb, layer, start) ||
            !miniBar.discRectIn(layer, target)
        ) {
            playAndOpen()
            return
        }
        miniBar.holdForLanding()
        runPending()
        val outline = RoundOutline(THUMB_CORNER_DP * density)
        val view = ImageView(context).apply {
            setImageBitmap(image)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = outline
            clipToOutline = true
            elevation = FLYER_ELEVATION_DP * density
        }
        layer.addView(view, FrameLayout.LayoutParams(start.width().roundToInt(), start.height().roundToInt()))
        thumb.alpha = 0f
        hiddenThumb = thumb
        flyer = view
        place(view, outline, 0f)
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = HOP_MS + FALL_MS
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener { place(view, outline, it.animatedValue as Float) }
        anim.doOnEnd { if (flight === anim) land() }
        flight = anim
        anim.start()
    }

    /**
     * One frame of the flight. First the hop: straight up, easing out, growing slightly. Then
     * the drop: it hangs at the top and falls faster and faster (gravity), drifting sideways
     * onto the record while shrinking to its size, spinning and rounding into a disc.
     */
    private fun place(view: View, outline: RoundOutline, progress: Float) {
        val width = start.width()
        val height = start.height()
        val hopShare = HOP_MS.toFloat() / (HOP_MS + FALL_MS)
        val apexY = start.centerY() - HOP_DP * density
        val landingScale = if (width > 0f) target.width() / width else 1f
        val cx: Float
        val cy: Float
        val scale: Float
        val turn: Float
        val roundness: Float
        if (progress < hopShare) {
            val lift = HOP_CURVE.getInterpolation(progress / hopShare)
            cx = start.centerX()
            cy = lerp(start.centerY(), apexY, lift)
            scale = lerp(1f, LIFT_SCALE, lift)
            turn = 0f
            roundness = 0f
        } else {
            val fall = ((progress - hopShare) / (1f - hopShare)).coerceIn(0f, 1f)
            val drift = fall * fall * (3f - 2f * fall)
            cx = lerp(start.centerX(), target.centerX(), drift)
            cy = lerp(apexY, target.centerY(), fall * fall)
            scale = lerp(LIFT_SCALE, landingScale, drift)
            turn = SPIN_DEGREES * fall * fall
            roundness = drift
        }
        view.translationX = cx - width / 2f
        view.translationY = cy - height / 2f
        view.scaleX = scale
        view.scaleY = scale
        view.rotation = turn
        outline.radius = lerp(THUMB_CORNER_DP * density, width / 2f, roundness)
        view.invalidateOutline()
    }

    private fun land() {
        flight = null
        removeFlyer()
        miniBar.land()
        layer.postDelayed(expandTask, LAND_TO_EXPAND_MS)
    }

    // ------------------------------------------------------------------ helpers

    private fun playAndOpen() {
        runPending()
        openPlayer()
    }

    private fun runPending() {
        val play = pendingPlay
        pendingPlay = null
        play?.invoke()
    }

    private fun removeFlyer() {
        flyer?.let { layer.removeView(it) }
        flyer = null
        hiddenThumb?.alpha = 1f
        hiddenThumb = null
    }

    private fun snapshot(view: View): Bitmap? {
        return try {
            view.drawToBitmap()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "cannot copy the cover for the launch: ${e.message}")
            null
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** A rounded outline whose radius changes during the flight, so the cover turns into a disc. */
    private class RoundOutline(var radius: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    private companion object {
        const val TAG = "SongLaunch"
        const val HOP_MS = 200L
        const val FALL_MS = 520L
        const val LAND_TO_EXPAND_MS = 220L
        const val HOP_DP = 34f
        const val LIFT_SCALE = 1.18f
        const val SPIN_DEGREES = 180f
        const val THUMB_CORNER_DP = 10f
        const val FLYER_ELEVATION_DP = 10f
        val HOP_CURVE: Interpolator = DecelerateInterpolator(1.5f)
    }
}
