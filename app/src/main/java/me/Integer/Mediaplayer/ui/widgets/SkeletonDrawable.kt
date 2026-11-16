package me.Integer.Mediaplayer.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock

/**
 * Lightweight shimmering placeholder with zero per-frame allocations.
 * All instances share the same uptime clock phase so rows shimmer in unison.
 */
class SkeletonDrawable(
    private val cornerRadiusPx: Float,
    private val animated: Boolean
) : Drawable() {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BASE_COLOR }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val shaderMatrix = Matrix()
    private var shimmerShader: LinearGradient? = null

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rect.set(bounds)
        val width = bounds.width().toFloat()
        if (width <= 0f) {
            shimmerShader = null
            return
        }
        val sweep = maxOf(width * SWEEP_FRACTION, 1f)
        val shader = LinearGradient(
            0f, 0f, sweep, sweep * TILT_RATIO,
            intArrayOf(BASE_COLOR, HIGHLIGHT_COLOR, BASE_COLOR),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        shimmerShader = shader
        shimmerPaint.shader = shader
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, basePaint)
        val shader = shimmerShader
        if (animated && shader != null) {
            val width = rect.width()
            val sweep = maxOf(width * SWEEP_FRACTION, 1f)
            val phase = (SystemClock.uptimeMillis() % CYCLE_MS).toFloat() / CYCLE_MS.toFloat()
            val offsetX = rect.left - sweep + (width + sweep * 2f) * phase
            shaderMatrix.setTranslate(offsetX, rect.top)
            shader.setLocalMatrix(shaderMatrix)
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, shimmerPaint)
            if (isVisible) invalidateSelf()
        }
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible && animated) invalidateSelf()
        return changed
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        shimmerPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        shimmerPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        private const val BASE_COLOR = 0xFF23252A.toInt()
        private const val HIGHLIGHT_COLOR = 0xFF343842.toInt()
        private const val CYCLE_MS = 1200L
        private const val SWEEP_FRACTION = 0.7f
        private const val TILT_RATIO = 0.25f

        fun tile(context: Context, cornerDp: Float = 8f): SkeletonDrawable {
            val density = context.resources.displayMetrics.density
            return SkeletonDrawable(cornerDp * density, Motion.durationScale(context) > 0f)
        }

        fun bar(context: Context, cornerDp: Float = 4f): SkeletonDrawable {
            val density = context.resources.displayMetrics.density
            return SkeletonDrawable(cornerDp * density, Motion.durationScale(context) > 0f)
        }
    }
}
