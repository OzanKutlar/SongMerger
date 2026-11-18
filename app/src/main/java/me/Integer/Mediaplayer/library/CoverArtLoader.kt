package me.Integer.Mediaplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Loads song covers for the screens. Covers are decoded on one low-priority thread and a
 * few are kept in memory. Only the newest requests are queued, so skipping quickly through
 * tracks never piles up work. Main thread only; a cached cover is delivered immediately.
 */
class CoverArtLoader(context: Context, private val targetPx: Int) {

    fun interface Callback {
        /** [art] is null when the song has no cover. */
        fun onArt(trackUri: String, art: Bitmap?)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(CACHE_ENTRIES)
    private val withoutArt = HashSet<String>()
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(MAX_QUEUED),
        { task -> Thread(task, "Mediaplayer-Cover").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    fun load(trackUri: String, callback: Callback) {
        if (trackUri.isEmpty()) return
        val cached = cache.get(trackUri)
        if (cached != null || trackUri in withoutArt) {
            callback.onArt(trackUri, cached)
            return
        }
        if (executor.isShutdown) return
        executor.execute {
            val art = EmbeddedArt.decode(appContext, Uri.parse(trackUri), targetPx)
            main.post { deliver(trackUri, art, callback) }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    private fun deliver(trackUri: String, art: Bitmap?, callback: Callback) {
        if (art != null) {
            cache.put(trackUri, art)
        } else {
            if (withoutArt.size >= MAX_REMEMBERED_MISSES) withoutArt.clear()
            withoutArt.add(trackUri)
        }
        callback.onArt(trackUri, art)
    }

    private companion object {
        const val CACHE_ENTRIES = 8
        const val MAX_QUEUED = 2
        const val MAX_REMEMBERED_MISSES = 2000
    }
}
