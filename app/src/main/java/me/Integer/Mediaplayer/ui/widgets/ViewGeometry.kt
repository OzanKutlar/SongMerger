package me.Integer.Mediaplayer.ui.widgets

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/** Where views sit relative to each other, and shared layout/stage helpers. */
internal object ViewGeometry {

    private const val GRID_COLUMNS = 3
    private const val CELL_PADDING_DP = 5f
    private const val DEFAULT_TILE_DP = 112f
    private const val STAGE_FADE_MS = 220L
    private val ACCELERATE = AccelerateInterpolator()
    private val DECELERATE = DecelerateInterpolator()

    /**
     * The bounds of [view] in the coordinates of [layer], including any translation of its
     * parents.
     *
     * @return false when either view is not attached or [view] has no size yet
     */
    fun rectIn(view: View, layer: View, out: RectF): Boolean {
        if (!view.isAttachedToWindow || !layer.isAttachedToWindow) return false
        if (view.width <= 0 || view.height <= 0) return false
        val viewSpot = IntArray(2)
        val layerSpot = IntArray(2)
        view.getLocationInWindow(viewSpot)
        layer.getLocationInWindow(layerSpot)
        val left = (viewSpot[0] - layerSpot[0]).toFloat()
        val top = (viewSpot[1] - layerSpot[1]).toFloat()
        out.set(left, top, left + view.width, top + view.height)
        return true
    }

    /** Sizes [thumb] to a square fitting one cell of a 3-column grid inside [parent]. */
    fun applySquareGridSize(parent: ViewGroup, thumb: View) {
        val usableWidth = parent.measuredWidth - parent.paddingLeft - parent.paddingRight
        val density = parent.resources.displayMetrics.density
        val cellPaddingPx = (CELL_PADDING_DP * 2 * density).toInt()
        val fallbackPx = (DEFAULT_TILE_DP * density).toInt()
        val side = if (usableWidth > 0) (usableWidth / GRID_COLUMNS) - cellPaddingPx else fallbackPx
        if (side > 0) {
            val params = thumb.layoutParams
            params.height = side
            thumb.layoutParams = params
        }
    }

    /** Crossfades between [discStage] and an overlay stage ([overlayStage]) in Now Playing. */
    fun transitionOverlayStage(
        discStage: View,
        overlayStage: View,
        showOverlay: Boolean,
        animate: Boolean,
        isStillVisible: () -> Boolean
    ) {
        discStage.animate().cancel()
        overlayStage.animate().cancel()
        if (showOverlay) {
            overlayStage.visibility = View.VISIBLE
            if (!animate) {
                discStage.alpha = 0f
                discStage.visibility = View.INVISIBLE
                overlayStage.alpha = 1f
                return
            }
            discStage.animate()
                .alpha(0f)
                .setDuration(STAGE_FADE_MS)
                .setInterpolator(ACCELERATE)
                .withEndAction { if (isStillVisible()) discStage.visibility = View.INVISIBLE }
                .start()
            overlayStage.animate()
                .alpha(1f)
                .setDuration(STAGE_FADE_MS)
                .setInterpolator(DECELERATE)
                .start()
        } else {
            discStage.visibility = View.VISIBLE
            if (!animate) {
                overlayStage.alpha = 0f
                overlayStage.visibility = View.GONE
                discStage.alpha = 1f
                return
            }
            overlayStage.animate()
                .alpha(0f)
                .setDuration(STAGE_FADE_MS)
                .setInterpolator(ACCELERATE)
                .withEndAction { if (!isStillVisible()) overlayStage.visibility = View.GONE }
                .start()
            discStage.animate()
                .alpha(1f)
                .setDuration(STAGE_FADE_MS)
                .setInterpolator(DECELERATE)
                .start()
        }
    }
}
