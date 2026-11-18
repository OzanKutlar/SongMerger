package me.Integer.Mediaplayer.ui.widgets

import android.graphics.RectF
import android.view.View

/** Where views sit relative to each other, for animations drawn on an overlay layer. */
internal object ViewGeometry {

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
}
