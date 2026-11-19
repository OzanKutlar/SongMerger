package me.Integer.Mediaplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import me.Integer.Mediaplayer.library.EmbeddedArt
import me.Integer.Mediaplayer.library.Playlist
import me.Integer.Mediaplayer.ui.widgets.SkeletonDrawable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Playlist covers. A cover is the user's image, one song's cover, or by default the covers
 * of the first four songs in a 2x2 grid (a playlist with fewer songs shows its first cover).
 * Rendering runs on one background thread and results are cached; the public functions are
 * main thread only.
 */
class PlaylistCoverLoader private constructor(context: Context) {

    /** The cover an ImageView wants right now; read by the worker. */
    private class Slot {
        @Volatile
        var key: String = ""
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val missing = HashSet<String>()
    private val gridPaint = Paint(Paint.FILTER_BITMAP_FLAG) // worker thread only
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_QUEUED),
        { task -> Thread(task, "Mediaplayer-PlaylistCover").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    // ------------------------------------------------------------------ public API

    /** Shows the cover of [playlist] in [view], a skeleton while rendering, or nothing when missing. */
    fun bind(view: ImageView, playlist: Playlist?) {
        val slot = view.tag as? Slot ?: Slot().also { view.tag = it }
        val key = if (playlist == null) "" else keyOf(playlist)
        if (slot.key == key && view.drawable != null) return
        slot.key = key
        view.animate().cancel()
        view.alpha = 1f
        if (playlist == null || key.isEmpty() || key in missing) {
            view.setImageDrawable(null)
            return
        }
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(SkeletonDrawable.tile(view.context))
        val target = WeakReference(view)
        executor.execute {
            if (slot.key == key) {
                val art = render(playlist)
                main.post { deliver(slot, key, target, art) }
            }
        }
    }

    /**
     * Copies a picked image into the app, downsized, so it stays available without access
     * to the original. [done] gets the new file path, or null when the image is unusable.
     */
    fun importImage(source: Uri, playlistId: Long, done: (String?) -> Unit) {
        executor.execute {
            val path = copyImage(source, playlistId)
            main.post { done(path) }
        }
    }

    private fun deliver(slot: Slot, key: String, view: WeakReference<ImageView>, art: Bitmap?) {
        if (art != null) {
            cache.put(key, art)
        } else {
            if (missing.size >= MAX_REMEMBERED_MISSES) missing.clear()
            missing.add(key)
        }
        val target = view.get() ?: return
        if (slot.key != key) return
        if (art == null) {
            target.setImageDrawable(null)
            return
        }
        target.setImageBitmap(art)
    }

    /** Changes whenever the picture would: cover choice, or the songs the grid is made of. */
    private fun keyOf(playlist: Playlist): String {
        val grid = gridUris(playlist).joinToString("|")
        return "${playlist.id}|${playlist.coverType}|${playlist.coverRef}|$grid"
    }

    private fun gridUris(playlist: Playlist): List<String> {
        val uris = playlist.trackUris
        return if (uris.size >= GRID_CELLS) uris.take(GRID_CELLS) else uris.take(1)
    }

    // ------------------------------------------------------------------ rendering (worker thread)

    /** A chosen cover that cannot be read falls back to the automatic one. */
    private fun render(playlist: Playlist): Bitmap? {
        val chosen = when (playlist.coverType) {
            Playlist.COVER_IMAGE -> decodeFile(playlist.coverRef)
            Playlist.COVER_TRACK -> EmbeddedArt.decode(appContext, Uri.parse(playlist.coverRef), COVER_PX)
            else -> null
        }
        return chosen ?: automatic(playlist)
    }

    private fun automatic(playlist: Playlist): Bitmap? {
        val uris = gridUris(playlist)
        if (uris.isEmpty()) return null
        if (uris.size < GRID_CELLS) return EmbeddedArt.decode(appContext, Uri.parse(uris[0]), COVER_PX)
        return grid(uris)
    }

    /** Four covers in a 2x2 grid; a song without a cover leaves a dark tile. */
    private fun grid(uris: List<String>): Bitmap? {
        val cell = COVER_PX / GRID_SIDE
        val out = try {
            Bitmap.createBitmap(COVER_PX, COVER_PX, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "out of memory composing a playlist cover")
            return null
        }
        val canvas = Canvas(out)
        canvas.drawColor(TILE_COLOR)
        var drawn = 0
        for ((index, uri) in uris.take(GRID_CELLS).withIndex()) {
            val art = EmbeddedArt.decode(appContext, Uri.parse(uri), cell) ?: continue
            val left = (index % GRID_SIDE) * cell
            val top = (index / GRID_SIDE) * cell
            canvas.drawBitmap(art, centreSquare(art), Rect(left, top, left + cell, top + cell), gridPaint)
            art.recycle()
            drawn++
        }
        if (drawn > 0) return out
        out.recycle()
        return null
    }

    private fun centreSquare(art: Bitmap): Rect {
        val side = minOf(art.width, art.height)
        val left = (art.width - side) / 2
        val top = (art.height - side) / 2
        return Rect(left, top, left + side, top + side)
    }

    private fun decodeFile(path: String): Bitmap? {
        if (path.isEmpty() || !File(path).isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = EmbeddedArt.sampleSize(minOf(bounds.outWidth, bounds.outHeight), COVER_PX)
        }
        return try {
            BitmapFactory.decodeFile(path, options)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "out of memory decoding $path")
            null
        }
    }

    // ------------------------------------------------------------------ image import (worker thread)

    private fun copyImage(source: Uri, playlistId: Long): String? {
        val bytes = readBounded(source) ?: return null
        val bitmap = decodeBytes(bytes) ?: return null
        val dir = File(appContext.filesDir, COVER_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "cannot create $dir")
            bitmap.recycle()
            return null
        }
        val target = File(dir, "cover_${playlistId}_${System.currentTimeMillis()}.jpg")
        return try {
            FileOutputStream(target).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw IOException("image could not be encoded")
                }
            }
            target.absolutePath
        } catch (e: IOException) {
            Log.w(TAG, "cannot save the playlist cover: ${e.message}")
            target.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun readBounded(source: Uri): ByteArray? {
        return try {
            appContext.contentResolver.openInputStream(source)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_BYTES)
                for (reads in 0 until MAX_READS) {
                    val count = input.read(buffer)
                    if (count < 0) return out.toByteArray()
                    if (out.size() + count > MAX_IMPORT_BYTES) {
                        Log.w(TAG, "picked image is larger than $MAX_IMPORT_BYTES bytes")
                        return null
                    }
                    out.write(buffer, 0, count)
                }
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "cannot read the picked image: ${e.message}")
            null
        } catch (e: RuntimeException) {
            // SecurityException or a failing content provider.
            Log.w(TAG, "picked image is not accessible: ${e.message}")
            null
        }
    }

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        return EmbeddedArt.decodeByteArray(bytes, IMPORT_PX)
    }

    companion object {
        private const val TAG = "PlaylistCoverLoader"
        private const val COVER_DIR = "playlist_covers"
        private const val COVER_PX = 480
        private const val IMPORT_PX = 640
        private const val GRID_SIDE = 2
        private const val GRID_CELLS = GRID_SIDE * GRID_SIDE
        private const val TILE_COLOR = 0xFF1E1E21.toInt()
        private const val JPEG_QUALITY = 90
        private const val CACHE_BYTES = 16 * 1024 * 1024
        private const val MAX_QUEUED = 32
        private const val MAX_REMEMBERED_MISSES = 500
        private const val MAX_IMPORT_BYTES = 24 * 1024 * 1024
        private const val BUFFER_BYTES = 16 * 1024
        private const val MAX_READS = 4096

        @Volatile
        private var instance: PlaylistCoverLoader? = null

        fun get(context: Context): PlaylistCoverLoader {
            return instance ?: synchronized(this) {
                instance ?: PlaylistCoverLoader(context.applicationContext).also { instance = it }
            }
        }
    }
}
