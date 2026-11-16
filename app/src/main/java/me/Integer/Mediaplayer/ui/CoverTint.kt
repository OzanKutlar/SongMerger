package me.Integer.Mediaplayer.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

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

    fun of(art: Bitmap?): Int {
        if (art == null || art.isRecycled) return FALLBACK
        val pixels = sample(art) ?: return FALLBACK
        val weights = FloatArray(BINS)
        val saturationSums = FloatArray(BINS)
        val hsv = FloatArray(3)
        for (pixel in pixels) {
            Color.colorToHSV(pixel, hsv)
            val weight = hsv[1] * hsv[2]
            val bin = (hsv[0] / 360f * BINS).toInt().coerceIn(0, BINS - 1)
            weights[bin] += weight
            saturationSums[bin] += hsv[1] * weight
        }
        val best = weights.indices.maxByOrNull { weights[it] } ?: return FALLBACK
        val weight = weights[best]
        if (weight < MIN_WEIGHT) return Color.HSVToColor(floatArrayOf(0f, 0f, NEUTRAL_VALUE))
        val hue = (best + 0.5f) * 360f / BINS
        val saturation = (saturationSums[best] / weight).coerceIn(MIN_SATURATION, MAX_SATURATION)
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
