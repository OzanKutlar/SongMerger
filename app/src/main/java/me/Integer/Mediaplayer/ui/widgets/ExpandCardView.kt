package me.Integer.Mediaplayer.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** A plain rounded card whose bounds, corners and colour are driven from outside, frame by frame. */
internal class ExpandCardView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = RectF()
    private var radius = 0f

    fun set(bounds: RectF, cornerRadius: Float, color: Int) {
        shape.set(bounds)
        radius = cornerRadius.coerceAtLeast(0f)
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(shape, radius, radius, paint)
    }
}
