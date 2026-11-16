package me.Integer.Mediaplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

/** Reads the cover picture embedded in an audio file. Blocking: call it off the main thread. */
object EmbeddedArt {

    private const val TAG = "EmbeddedArt"
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MIN_TARGET_PX = 64
    private const val MAX_HALVINGS = 6

    /** The cover downsampled to roughly [targetPx] on its short side, or null when there is none. */
    fun decode(context: Context, uri: Uri, targetPx: Int): Bitmap? {
        val data = pictureBytes(context, uri) ?: return null
        if (data.size > MAX_BYTES) {
            Log.w(TAG, "cover of $uri is larger than $MAX_BYTES bytes, skipped")
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(minOf(bounds.outWidth, bounds.outHeight), targetPx)
        }
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "out of memory decoding the cover of $uri")
            null
        }
    }

    private fun pictureBytes(context: Context, uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot read the cover of $uri: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "cannot release the cover reader: ${e.message}")
            }
        }
    }

    private fun sampleSize(shortestPx: Int, targetPx: Int): Int {
        val target = maxOf(MIN_TARGET_PX, targetPx)
        var sample = 1
        for (step in 0 until MAX_HALVINGS) {
            if (shortestPx / (sample * 2) < target) break
            sample *= 2
        }
        return sample
    }
}
