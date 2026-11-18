package me.Integer.Mediaplayer.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.util.LruCache

/**
 * A dark colour that matches a cover, for the mini player. The strongest hue in the cover
 * wins (weighted by saturation and brightness), then it is darkened to a fixed level so
 * white text always reads well. Grey covers give a neutral dark grey.
 */
object CoverTint {

    const val FALLBACK = 0xFF281913.toInt()

    private const val TAG = "CoverTint"
    private const val SAMPLE = 24
    private const val BINS = 12
    private const val MIN_WEIGHT = 15f
    private const val VALUE = 0.30f
    private const val MIN_SATURATION = 0.35f
    private const val MAX_SATURATION = 0.70f
    private const val NEUTRAL_VALUE = 0.16f
    private const val CACHE_SIZE = 32
    private val NEUTRAL_COLOR = Color.HSVToColor(floatArrayOf(0f, 0f, NEUTRAL_VALUE))
    private val cache = LruCache<Int, Int>(CACHE_SIZE)

    fun of(art: Bitmap?): Int {
        if (art == null || art.isRecycled) return FALLBACK
        val key = art.generationId
        synchronized(cache) {
            cache.get(key)?.let { return it }
        }
        val computed = computeTint(art)
        synchronized(cache) {
            cache.put(key, computed)
        }
        return computed
    }

    private fun computeTint(art: Bitmap): Int {
        val pixels = sample(art) ?: return FALLBACK
        val weights = FloatArray(BINS)
        val saturationSums = FloatArray(BINS)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val maxC = maxOf(r, g, b)
            if (maxC == 0) continue
            val minC = minOf(r, g, b)
            val delta = maxC - minC
            if (delta == 0) continue
            val sat = delta.toFloat() / maxC.toFloat()
            val value = maxC.toFloat() / 255f
            val weight = sat * value
            val rawHue = when (maxC) {
                r -> (g - b).toFloat() / delta
                g -> 2f + (b - r).toFloat() / delta
                else -> 4f + (r - g).toFloat() / delta
            }
            val hue = if (rawHue < 0f) rawHue + 6f else rawHue
            val bin = (hue / 6f * BINS).toInt().coerceIn(0, BINS - 1)
            weights[bin] += weight
            saturationSums[bin] += sat * weight
        }
        var best = 0
        var bestWeight = weights[0]
        for (i in 1 until BINS) {
            if (weights[i] > bestWeight) {
                bestWeight = weights[i]
                best = i
            }
        }
        if (bestWeight < MIN_WEIGHT) return NEUTRAL_COLOR
        val hue = (best + 0.5f) * 360f / BINS
        val saturation = (saturationSums[best] / bestWeight).coerceIn(MIN_SATURATION, MAX_SATURATION)
        return Color.HSVToColor(floatArrayOf(hue, saturation, VALUE))
    }

    private fun sample(art: Bitmap): IntArray? {
        var scaled: Bitmap? = null
        return try {
            val small = Bitmap.createScaledBitmap(art, SAMPLE, SAMPLE, true)
            scaled = small
            val pixels = IntArray(SAMPLE * SAMPLE)
            small.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
            pixels
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot sample the cover: ${e.message}")
            null
        } finally {
            if (scaled != null && scaled !== art) scaled.recycle()
        }
    }
}
