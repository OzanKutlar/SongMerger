package me.Integer.Mediaplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import me.Integer.Mediaplayer.library.EmbeddedArt
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Small covers for list rows. Each ImageView remembers which song it currently shows;
 * a request for a row that was scrolled away is skipped before any decoding. Decoded
 * thumbnails are kept in a memory cache bounded by bytes. Only bind() is public and it is
 * main thread only.
 */
class ThumbnailLoader private constructor(context: Context) {

    /** The song an ImageView wants right now; read by the workers. */
    private class Slot {
        @Volatile
        var uri: String = ""
    }

    private inner class Task(
        val slot: Slot,
        val uri: String,
        val view: WeakReference<ImageView>
    ) : Runnable {
        override fun run() {
            if (slot.uri != uri) return // scrolled away before its turn
            val art = cache.get(uri) ?: decode(uri)
            val task = this
            main.post { deliver(task, art) }
        }
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sizePx = (THUMB_DP * appContext.resources.displayMetrics.density).roundToInt()
    private val missing = HashSet<String>()
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val executor = ThreadPoolExecutor(
        WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_QUEUED),
        { task -> Thread(task, "Mediaplayer-Thumb").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    /** Shows the cover of [trackUri] in [view], or nothing (the placeholder behind it shows). */
    fun bind(view: ImageView, trackUri: String) {
        val slot = view.tag as? Slot ?: Slot().also { view.tag = it }
        slot.uri = trackUri
        view.animate().cancel()
        view.alpha = 1f
        val cached = if (trackUri.isEmpty()) null else cache.get(trackUri)
        view.setImageBitmap(cached)
        if (cached != null || trackUri.isEmpty() || trackUri in missing) return
        executor.execute(Task(slot, trackUri, WeakReference(view)))
    }

    /** Worker thread. */
    private fun decode(uri: String): Bitmap? {
        val art = EmbeddedArt.decode(appContext, Uri.parse(uri), sizePx) ?: return null
        return shrink(art)
    }

    private fun shrink(art: Bitmap): Bitmap {
        val shortest = minOf(art.width, art.height)
        if (shortest <= sizePx * SHRINK_SLACK) return art
        val scale = sizePx.toFloat() / shortest
        return try {
            val width = maxOf(1, (art.width * scale).roundToInt())
            val height = maxOf(1, (art.height * scale).roundToInt())
            val small = Bitmap.createScaledBitmap(art, width, height, true)
            if (small !== art) art.recycle()
            small
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot shrink a thumbnail: ${e.message}")
            art
        }
    }

    private fun deliver(task: Task, art: Bitmap?) {
        if (art != null) {
            cache.put(task.uri, art)
        } else {
            if (missing.size >= MAX_REMEMBERED_MISSES) missing.clear()
            missing.add(task.uri)
        }
        val view = task.view.get() ?: return
        if (art == null || task.slot.uri != task.uri) return
        view.setImageBitmap(art)
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(FADE_MS).start()
    }

    companion object {
        private const val TAG = "ThumbnailLoader"
        private const val THUMB_DP = 56f
        private const val SHRINK_SLACK = 1.25f
        private const val CACHE_BYTES = 24 * 1024 * 1024
        private const val WORKERS = 2
        private const val MAX_QUEUED = 64
        private const val MAX_REMEMBERED_MISSES = 5000
        private const val FADE_MS = 150L

        @Volatile
        private var instance: ThumbnailLoader? = null

        fun get(context: Context): ThumbnailLoader {
            return instance ?: synchronized(this) {
                instance ?: ThumbnailLoader(context.applicationContext).also { instance = it }
            }
        }
    }
}
