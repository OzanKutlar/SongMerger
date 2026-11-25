package me.Integer.Mediaplayer.ui.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** One line of text that moves, resizes and restyles from one layout to another. */
internal class TextPart(
    private val fromText: String,
    fromRect: RectF,
    val fromSize: Float,
    val fromFace: Typeface,
    val fromColor: Int,
    private val fromCentered: Boolean,
    private val toText: String,
    toRect: RectF,
    val toSize: Float,
    val toFace: Typeface,
    val toColor: Int,
    private val toCentered: Boolean
) {
    private val fromRect = RectF(fromRect)
    private val toRect = RectF(toRect)

    var fromShown = fromText
        private set
    var toShown = toText
        private set
    var fromLeft = 0f
        private set
    var toLeft = 0f
        private set
    var fromBaseline = 0f
        private set
    var toBaseline = 0f
        private set

    /** True when both ends show the same text in the same font, so it is drawn once. */
    val sameLook: Boolean
        get() = fromShown == toShown && fromFace == toFace

    /** Fits each end to its own width and works out where it sits. */
    fun prepare(paint: TextPaint) {
        paint.typeface = fromFace
        paint.textSize = fromSize
        fromShown = TextUtils.ellipsize(fromText, paint, max(0f, fromRect.width()), TextUtils.TruncateAt.END).toString()
        fromLeft = leftOf(fromRect, paint.measureText(fromShown), fromCentered)
        fromBaseline = baselineOf(fromRect, paint)
        paint.typeface = toFace
        paint.textSize = toSize
        toShown = TextUtils.ellipsize(toText, paint, max(0f, toRect.width()), TextUtils.TruncateAt.END).toString()
        toLeft = leftOf(toRect, paint.measureText(toShown), toCentered)
        toBaseline = baselineOf(toRect, paint)
    }

    private fun leftOf(rect: RectF, width: Float, centered: Boolean): Float {
        return if (centered) rect.centerX() - width / 2f else rect.left
    }

    private fun baselineOf(rect: RectF, paint: TextPaint): Float {
        return rect.centerY() - (paint.ascent() + paint.descent()) / 2f
    }
}

/** An icon that fades out at its start position or fades in at its end position. */
internal class IconPart(val drawable: Drawable, rect: RectF, val fadesIn: Boolean) {

    val rect = RectF(rect)

    companion object {
        /** A tinted icon at [rect]; a [sizePx] above 0 makes it a centred square of that size. */
        fun of(context: Context, res: Int, rect: RectF, sizePx: Float, color: Int, fadesIn: Boolean): IconPart? {
            val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: return null
            drawable.setTint(color)
            val bounds = RectF(rect)
            if (sizePx > 0f) {
                val half = sizePx / 2f
                bounds.set(rect.centerX() - half, rect.centerY() - half, rect.centerX() + half, rect.centerY() + half)
            }
            return IconPart(drawable, bounds, fadesIn)
        }
    }
}

/** Everything [MorphCardView] morphs between: two layouts of one card. Rects are copied. */
internal class MorphScene(
    fromCard: RectF,
    toCard: RectF,
    val fromCorner: Float,
    /** The corner the start layout rounds to while it is lifted. */
    val liftCorner: Float,
    val toCorner: Float,
    val fromColor: Int,
    val toColor: Int,
    /** How visible the card's background is at the start before lifting; 0 for a list row. */
    val fromBackgroundAlpha: Float,
    fromCover: RectF,
    toCover: RectF,
    val fromCoverCorner: Float,
    val toCoverCorner: Float,
    val cover: Bitmap?,
    val coverPlaceholder: Int,
    val title: TextPart?,
    val artist: TextPart?,
    val icons: List<IconPart>,
    /** Below 1 the card's top edge runs ahead of the others, so a growing card rises. */
    val topLead: Float = 1f,
    /** True when the card casts a soft shadow while lifted; the shadow is built once, up front. */
    val liftShadow: Boolean = false
) {
    val fromCard = RectF(fromCard)
    val toCard = RectF(toCard)
    val fromCover = RectF(fromCover)
    val toCover = RectF(toCover)
}

/** One line of text rasterized once into an alpha mask; drawing it tinted is a single textured quad. */
internal class TextSprite(val mask: Bitmap, val originX: Float, val originY: Float, val baseSize: Float) {

    companion object {
        private const val TAG = "TextSprite"
        private const val MIN_PAD_PX = 2f
        private const val PAD_SHARE = 0.2f
        private const val MAX_SIDE_PX = 4096

        /** @return null when there is nothing to draw or no mask can be made; the caller then draws live text */
        fun of(text: String, face: Typeface, size: Float, paint: TextPaint): TextSprite? {
            if (text.isEmpty() || !size.isFinite() || size <= 0f) return null
            paint.typeface = face
            paint.textSize = size
            paint.color = Color.WHITE
            val pad = max(MIN_PAD_PX, size * PAD_SHARE)
            val ascent = paint.ascent()
            val width = ceil(paint.measureText(text) + pad * 2f).toInt()
            val height = ceil(paint.descent() - ascent + pad * 2f).toInt()
            if (width <= 0 || height <= 0 || width > MAX_SIDE_PX || height > MAX_SIDE_PX) return null
            return try {
                val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                val originY = pad - ascent
                Canvas(mask).drawText(text, pad, originY, paint)
                mask.prepareToDraw()
                TextSprite(mask, pad, originY, size)
            } catch (e: RuntimeException) {
                Log.w(TAG, "cannot pre-render text: ${e.message}")
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "no memory to pre-render text")
                null
            }
        }
    }
}

/**
 * A soft rounded shadow blurred once into a small mask, then stretched around a card of any
 * size in nine slices. Drawing it is nine textured quads, with no blur work per frame.
 */
internal class ShadowSprite private constructor(
    private val mask: Bitmap,
    private val edgePx: Int,
    private val halo: Float
) {

    private val src = Rect()
    private val dst = RectF()

    /** Draws around [card], reaching the halo past its edges and shifted down by [dy]; [paint] sets the alpha. */
    fun draw(canvas: Canvas, card: RectF, dy: Float, paint: Paint) {
        val left = card.left - halo
        val top = card.top - halo + dy
        val right = card.right + halo
        val bottom = card.bottom + halo + dy
        val corner = minOf(edgePx.toFloat(), (right - left) / 2f, (bottom - top) / 2f)
        if (!corner.isFinite() || corner <= 0f) return
        for (row in 0 until SLICES) {
            for (col in 0 until SLICES) {
                src.set(srcStart(col), srcStart(row), srcEnd(col), srcEnd(row))
                dst.set(
                    dstStart(col, left, right, corner),
                    dstStart(row, top, bottom, corner),
                    dstEnd(col, left, right, corner),
                    dstEnd(row, top, bottom, corner)
                )
                if (dst.width() > 0f && dst.height() > 0f) canvas.drawBitmap(mask, src, dst, paint)
            }
        }
    }

    private fun srcStart(i: Int): Int = when (i) {
        0 -> 0
        1 -> edgePx
        else -> edgePx + 1
    }

    private fun srcEnd(i: Int): Int = when (i) {
        0 -> edgePx
        1 -> edgePx + 1
        else -> edgePx * 2 + 1
    }

    private fun dstStart(i: Int, a: Float, b: Float, corner: Float): Float = when (i) {
        0 -> a
        1 -> a + corner
        else -> b - corner
    }

    private fun dstEnd(i: Int, a: Float, b: Float, corner: Float): Float = when (i) {
        0 -> a + corner
        1 -> b - corner
        else -> b
    }

    companion object {
        private const val TAG = "ShadowSprite"
        private const val SLICES = 3
        private const val MAX_EDGE_PX = 1024

        /** @return null when the sizes are unusable or no mask can be made; the card then has no shadow */
        fun create(blurPx: Float, haloPx: Float, cornerPx: Float): ShadowSprite? {
            val valid = blurPx.isFinite() && blurPx > 0f && haloPx.isFinite() && haloPx > 0f &&
                cornerPx.isFinite() && cornerPx >= 0f
            if (!valid) return null
            val edge = ceil(haloPx + cornerPx + blurPx).toInt()
            if (edge <= 0 || edge > MAX_EDGE_PX) return null
            val size = edge * 2 + 1
            return try {
                val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
                }
                val body = RectF(haloPx, haloPx, size - haloPx, size - haloPx)
                Canvas(mask).drawRoundRect(body, cornerPx, cornerPx, paint)
                mask.prepareToDraw()
                ShadowSprite(mask, edge, haloPx)
            } catch (e: RuntimeException) {
                Log.w(TAG, "cannot build the shadow: ${e.message}")
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "no memory for the shadow")
                null
            }
        }
    }
}

/** The masks for one [TextPart]: the start version (null when both ends look the same) and the end version. */
internal class TextSprites(val from: TextSprite?, val to: TextSprite?)

/**
 * Draws one card part-way between two layouts: background, cover, title, artist and icons
 * all interpolate together. Each frame only sets numbers; nothing is allocated while it runs,
 * and text is drawn from masks made once, so glyphs are never re-rasterized mid-flight.
 * The card can also be offset, squashed from its bottom edge and tilted, for physics on top.
 */
internal class MorphCardView(context: Context, private val scene: MorphScene) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = scene.coverPlaceholder }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val coverBitmap: Bitmap? = scene.cover?.takeUnless { it.isRecycled }
    private val coverShader: BitmapShader? = coverBitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    // No anti-aliasing: the nine shadow slices then meet without seams.
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadow: ShadowSprite? = if (scene.liftShadow) {
        ShadowSprite.create(SHADOW_BLUR_DP * density, SHADOW_HALO_DP * density, scene.liftCorner)
    } else {
        null
    }
    private val coverMatrix = Matrix()
    private val card = RectF()
    private val cover = RectF()

    private var progress = 0f
    private var lift = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var squashX = 1f
    private var squashY = 1f
    private var tilt = 0f

    init {
        coverPaint.shader = coverShader
        scene.title?.prepare(textPaint)
        scene.artist?.prepare(textPaint)
        // Upload the cover to the GPU now rather than on the first animated frame.
        coverBitmap?.prepareToDraw()
    }

    // Built after the parts are prepared, so each line is rasterized exactly once.
    private val titleSprites: TextSprites? = scene.title?.let { spritesFor(it) }
    private val artistSprites: TextSprites? = scene.artist?.let { spritesFor(it) }

    /**
     * @param progress 0 = start layout, 1 = end layout
     * @param lift 0..1: how far the card is picked up (shadow, background, rounder corners)
     */
    fun update(
        progress: Float,
        lift: Float,
        offsetX: Float,
        offsetY: Float,
        squashX: Float,
        squashY: Float,
        tilt: Float
    ) {
        this.progress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        this.lift = if (lift.isFinite()) lift.coerceIn(0f, 1f) else 0f
        this.offsetX = offsetX
        this.offsetY = offsetY
        this.squashX = squashX
        this.squashY = squashY
        this.tilt = tilt
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = progress
        lerpRect(card, scene.fromCard, scene.toCard, m, scene.topLead)
        card.offset(offsetX, offsetY)
        val saved = canvas.save()
        canvas.rotate(tilt, card.centerX(), card.centerY())
        canvas.scale(squashX, squashY, card.centerX(), card.bottom)
        drawBackground(canvas, m)
        drawCover(canvas, m)
        scene.title?.let { drawText(canvas, it, titleSprites, m) }
        scene.artist?.let { drawText(canvas, it, artistSprites, m) }
        for (icon in scene.icons) drawIcon(canvas, icon, m)
        canvas.restoreToCount(saved)
    }

    private fun drawBackground(canvas: Canvas, m: Float) {
        val lifted = scene.fromBackgroundAlpha + (1f - scene.fromBackgroundAlpha) * lift
        val visible = lerp(lifted, 1f, m)
        val corner = lerp(lerp(scene.fromCorner, scene.liftCorner, lift), scene.toCorner, m)
        val shadowStrength = lift * visible
        if (shadowStrength > 0.01f) {
            drawSoftShadow(canvas, shadowStrength)
        }
        val color = mixColor(scene.fromColor, scene.toColor, m)
        backgroundPaint.color = color
        backgroundPaint.alpha = (((color ushr 24) and 0xFF) * visible).roundToInt()
        canvas.drawRoundRect(card, corner, corner, backgroundPaint)
    }

    /** One pre-blurred mask stretched around the card: a smooth falloff with no bands and no per-frame blur. */
    private fun drawSoftShadow(canvas: Canvas, strength: Float) {
        val sprite = shadow ?: return
        val alpha = (SHADOW_ALPHA * strength).roundToInt().coerceIn(0, OPAQUE)
        if (alpha == 0) return
        shadowPaint.color = Color.BLACK
        shadowPaint.alpha = alpha
        sprite.draw(canvas, card, SHADOW_DY_DP * density * lift, shadowPaint)
    }

    private fun drawCover(canvas: Canvas, m: Float) {
        lerpRect(cover, scene.fromCover, scene.toCover, m, 1f)
        cover.offset(offsetX, offsetY)
        val radius = lerp(scene.fromCoverCorner, scene.toCoverCorner, m)
        val bitmap = coverBitmap
        val shader = coverShader
        if (bitmap == null || shader == null || bitmap.width <= 0 || bitmap.height <= 0) {
            canvas.drawRoundRect(cover, radius, radius, placeholderPaint)
            return
        }
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val scale = max(cover.width() / width, cover.height() / height)
        coverMatrix.setScale(scale, scale)
        coverMatrix.postTranslate(cover.centerX() - width * scale / 2f, cover.centerY() - height * scale / 2f)
        shader.setLocalMatrix(coverMatrix)
        canvas.drawRoundRect(cover, radius, radius, coverPaint)
    }

    /**
     * Each line is a pre-rendered alpha mask, so moving, scaling and tilting it costs one
     * textured quad per frame. The paint colour tints the mask, so the colour still blends.
     */
    private fun drawText(canvas: Canvas, part: TextPart, sprites: TextSprites?, m: Float) {
        val currentSize = lerp(part.fromSize, part.toSize, m)
        val left = lerp(part.fromLeft, part.toLeft, m) + offsetX
        val baseline = lerp(part.fromBaseline, part.toBaseline, m) + offsetY
        val color = mixColor(part.fromColor, part.toColor, m)
        if (part.sameLook) {
            drawVersion(canvas, sprites?.to, part.toShown, part.toFace, currentSize, color, 1f, left, baseline)
            return
        }
        drawVersion(canvas, sprites?.from, part.fromShown, part.fromFace, currentSize, color, 1f - m, left, baseline)
        drawVersion(canvas, sprites?.to, part.toShown, part.toFace, currentSize, color, m, left, baseline)
    }

    private fun drawVersion(
        canvas: Canvas,
        sprite: TextSprite?,
        text: String,
        face: Typeface,
        targetSize: Float,
        color: Int,
        share: Float,
        x: Float,
        y: Float
    ) {
        if (share <= 0f || text.isEmpty() || targetSize <= 0f) return
        val alpha = (((color ushr 24) and 0xFF) * share).roundToInt().coerceIn(0, OPAQUE)
        if (alpha == 0) return
        if (sprite == null) {
            drawLiveText(canvas, text, face, targetSize, color, alpha, x, y)
            return
        }
        spritePaint.color = color
        spritePaint.alpha = alpha
        val scale = targetSize / sprite.baseSize
        val saved = canvas.save()
        canvas.translate(x, y)
        if (abs(scale - 1f) >= SCALE_EPSILON) canvas.scale(scale, scale)
        canvas.drawBitmap(sprite.mask, -sprite.originX, -sprite.originY, spritePaint)
        canvas.restoreToCount(saved)
    }

    /** Fallback when no mask could be made: draws the glyphs directly. */
    private fun drawLiveText(
        canvas: Canvas,
        text: String,
        face: Typeface,
        size: Float,
        color: Int,
        alpha: Int,
        x: Float,
        y: Float
    ) {
        textPaint.typeface = face
        textPaint.textSize = size
        textPaint.color = color
        textPaint.alpha = alpha
        canvas.drawText(text, x, y, textPaint)
    }

    /** Masks at the larger of the two sizes, so scaling only ever shrinks them and they stay sharp. */
    private fun spritesFor(part: TextPart): TextSprites {
        val size = max(part.fromSize, part.toSize)
        val to = TextSprite.of(part.toShown, part.toFace, size, textPaint)
        val from = if (part.sameLook) null else TextSprite.of(part.fromShown, part.fromFace, size, textPaint)
        return TextSprites(from, to)
    }

    /** Icons ride along with the card, keeping their place relative to its edges. */
    private fun drawIcon(canvas: Canvas, icon: IconPart, m: Float) {
        val source = if (icon.fadesIn) scene.toCard else scene.fromCard
        if (source.width() <= 0f || source.height() <= 0f) return
        val share = if (icon.fadesIn) (m - ICON_IN_START) / (1f - ICON_IN_START) else 1f - m / ICON_OUT_END
        val alpha = share.coerceIn(0f, 1f)
        if (alpha <= 0f) return
        val cx = card.left + (icon.rect.centerX() - source.left) * card.width() / source.width()
        val cy = card.top + (icon.rect.centerY() - source.top) * card.height() / source.height()
        val halfWidth = icon.rect.width() / 2f
        val halfHeight = icon.rect.height() / 2f
        icon.drawable.setBounds(
            (cx - halfWidth).roundToInt(),
            (cy - halfHeight).roundToInt(),
            (cx + halfWidth).roundToInt(),
            (cy + halfHeight).roundToInt()
        )
        icon.drawable.alpha = (OPAQUE * alpha).roundToInt()
        icon.drawable.draw(canvas)
    }

    private fun lerpRect(out: RectF, from: RectF, to: RectF, t: Float, topLead: Float) {
        val topT = if (topLead == 1f) t else t.pow(topLead)
        out.set(
            lerp(from.left, to.left, t),
            lerp(from.top, to.top, topT),
            lerp(from.right, to.right, t),
            lerp(from.bottom, to.bottom, t)
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Blends two ARGB colours channel by channel, without boxing. */
    private fun mixColor(a: Int, b: Int, t: Float): Int {
        val keep = 1f - t
        val alpha = (((a ushr 24) and 0xFF) * keep + ((b ushr 24) and 0xFF) * t).roundToInt()
        val red = (((a shr 16) and 0xFF) * keep + ((b shr 16) and 0xFF) * t).roundToInt()
        val green = (((a shr 8) and 0xFF) * keep + ((b shr 8) and 0xFF) * t).roundToInt()
        val blue = ((a and 0xFF) * keep + (b and 0xFF) * t).roundToInt()
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private companion object {
        const val SHADOW_BLUR_DP = 8f
        const val SHADOW_HALO_DP = 16f
        const val SHADOW_DY_DP = 6f
        const val SHADOW_ALPHA = 96
        const val ICON_OUT_END = 0.35f
        const val ICON_IN_START = 0.55f
        const val OPAQUE = 255
        const val SCALE_EPSILON = 0.001f
    }
}
