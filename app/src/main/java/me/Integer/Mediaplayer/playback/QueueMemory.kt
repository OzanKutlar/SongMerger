package me.Integer.Mediaplayer.playback

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** One queue entry as it is kept on disk. */
internal data class SavedItem(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val uid: Long,
    val clipStartMs: Long = 0L,
    /** Where a mashup step stops, or [TO_END] for the whole song. */
    val clipEndMs: Long = TO_END,
    val crossfadeMs: Long = 0L,
    val tempoRatio: Float = 1f,
    /** Loudness correction of a mashup step, 0 or below. */
    val gainDb: Float = 0f
) {
    companion object {
        const val TO_END = -1L
    }
}

/** The queue in playing order, with the shuffle state and the order to return to. */
internal data class SavedQueue(
    val items: List<SavedItem>,
    val shuffleOn: Boolean,
    /** Slot ids in pre-shuffle order; empty when shuffle is off. */
    val originalOrder: List<Long>
)

/** Where playback was. Saved often, so it is kept apart from the queue. */
internal data class SavedPosition(
    val index: Int,
    val uid: Long,
    val uri: String,
    val positionMs: Long,
    val repeatMode: Int
)

/** A saved queue ready to load: the song to start at, where in it, and the repeat mode. */
internal data class RestoredQueue(
    val queue: SavedQueue,
    val startIndex: Int,
    val startPositionMs: Long,
    val repeatMode: Int
)

/**
 * Turns a [SavedQueue] into text and back: one line per entry, fields separated by tabs, with
 * tabs, line breaks and backslashes inside values escaped. Pure Kotlin, so it is unit-tested.
 */
internal object QueueCodec {

    const val MAX_ITEMS = 50000

    private const val HEADER = "MPQ1"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_ORDER = "order"
    private const val KEY_ITEM = "item"
    private const val ON = "1"
    private const val OFF = "0"
    private const val ORDER_SEPARATOR = ","
    private const val ITEM_PARTS = 11

    /** Queues saved before the gain field have one part less; they still load. */
    private const val LEGACY_ITEM_PARTS = 10
    private const val MIN_GAIN_DB = -12f
    private const val HEADER_LINES = 3
    private const val LINE_ESTIMATE = 128
    private const val ESCAPE_SLACK = 8
    private const val MAX_CROSSFADE_MS = 15000L
    private const val MIN_TEMPO = 0.5f
    private const val MAX_TEMPO = 2f
    private const val REPEAT_MODES = 3
    private const val BACKSLASH = '\\'
    private const val TAB = '\t'
    private const val NEWLINE = '\n'
    private const val RETURN = '\r'

    fun encode(queue: SavedQueue): String {
        val items = queue.items.take(MAX_ITEMS)
        val out = StringBuilder(items.size * LINE_ESTIMATE + LINE_ESTIMATE)
        out.append(HEADER).append(NEWLINE)
        out.append(KEY_SHUFFLE).append(TAB).append(if (queue.shuffleOn) ON else OFF).append(NEWLINE)
        out.append(KEY_ORDER).append(TAB)
        queue.originalOrder.take(MAX_ITEMS).joinTo(out, ORDER_SEPARATOR)
        out.append(NEWLINE)
        for (item in items) appendItem(out, item)
        return out.toString()
    }

    /** @return null for text that is not a saved queue, or one without a usable song */
    fun decode(text: String): SavedQueue? {
        val lines = text.split(NEWLINE)
        if (lines.firstOrNull() != HEADER) return null
        var shuffle = false
        var order: List<Long> = emptyList()
        val items = ArrayList<SavedItem>()
        val last = minOf(lines.size, MAX_ITEMS + HEADER_LINES)
        for (i in 1 until last) {
            val parts = lines[i].split(TAB)
            when (parts[0]) {
                KEY_SHUFFLE -> shuffle = parts.getOrNull(1) == ON
                KEY_ORDER -> order = parseOrder(parts.getOrNull(1).orEmpty())
                KEY_ITEM -> parseItem(parts)?.let { items.add(it) }
            }
        }
        if (items.isEmpty()) return null
        return SavedQueue(items, shuffle, if (shuffle) order else emptyList())
    }

    /**
     * Finds the song to start at: the saved index when it still holds the same song, else the
     * same slot id, else the same file. An unknown song starts the queue from the top.
     */
    fun resolve(queue: SavedQueue, position: SavedPosition?): RestoredQueue {
        if (position == null) return RestoredQueue(queue, 0, 0L, 0)
        val repeat = if (position.repeatMode in 0 until REPEAT_MODES) position.repeatMode else 0
        val index = indexOf(queue.items, position)
        if (index < 0) return RestoredQueue(queue, 0, 0L, repeat)
        return RestoredQueue(queue, index, position.positionMs.coerceAtLeast(0L), repeat)
    }

    fun escape(value: String): String {
        if (value.none { it == BACKSLASH || it == TAB || it == NEWLINE || it == RETURN }) return value
        val out = StringBuilder(value.length + ESCAPE_SLACK)
        for (c in value) {
            when (c) {
                BACKSLASH -> out.append(BACKSLASH).append(BACKSLASH)
                TAB -> out.append(BACKSLASH).append('t')
                NEWLINE -> out.append(BACKSLASH).append('n')
                RETURN -> out.append(BACKSLASH).append('r')
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    fun unescape(value: String): String {
        if (value.indexOf(BACKSLASH) < 0) return value
        val out = StringBuilder(value.length)
        var escaped = false
        for (c in value) {
            if (escaped) {
                out.append(
                    when (c) {
                        't' -> TAB
                        'n' -> NEWLINE
                        'r' -> RETURN
                        else -> c
                    }
                )
                escaped = false
            } else if (c == BACKSLASH) {
                escaped = true
            } else {
                out.append(c)
            }
        }
        if (escaped) out.append(BACKSLASH)
        return out.toString()
    }

    private fun indexOf(items: List<SavedItem>, position: SavedPosition): Int {
        val hinted = items.getOrNull(position.index)
        if (hinted != null && hinted.uid == position.uid && hinted.uri == position.uri) return position.index
        if (position.uid != 0L) {
            val byUid = items.indexOfFirst { it.uid == position.uid && it.uri == position.uri }
            if (byUid >= 0) return byUid
        }
        return items.indexOfFirst { it.uri == position.uri }
    }

    private fun appendItem(out: StringBuilder, item: SavedItem) {
        out.append(KEY_ITEM)
        val fields = arrayOf(
            item.uri, item.title, item.artist, item.album,
            item.uid.toString(), item.clipStartMs.toString(), item.clipEndMs.toString(),
            item.crossfadeMs.toString(), item.tempoRatio.toString(), item.gainDb.toString()
        )
        for (field in fields) out.append(TAB).append(escape(field))
        out.append(NEWLINE)
    }

    private fun parseOrder(text: String): List<Long> {
        if (text.isEmpty()) return emptyList()
        return text.split(ORDER_SEPARATOR).asSequence()
            .take(MAX_ITEMS)
            .mapNotNull { it.toLongOrNull() }
            .toList()
    }

    private fun parseItem(parts: List<String>): SavedItem? {
        if (parts.size != ITEM_PARTS && parts.size != LEGACY_ITEM_PARTS) return null
        val uri = unescape(parts[1])
        val uid = parts[5].toLongOrNull()
        if (uri.isEmpty() || uid == null) return null
        val start = parts[6].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val end = parts[7].toLongOrNull()?.takeIf { it > start } ?: SavedItem.TO_END
        val crossfade = parts[8].toLongOrNull()?.coerceIn(0L, MAX_CROSSFADE_MS) ?: 0L
        val tempo = parts[9].toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(MIN_TEMPO, MAX_TEMPO) ?: 1f
        val gain = parts.getOrNull(10)?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(MIN_GAIN_DB, 0f) ?: 0f
        return SavedItem(
            uri = uri,
            title = unescape(parts[2]),
            artist = unescape(parts[3]),
            album = unescape(parts[4]),
            uid = uid,
            clipStartMs = start,
            clipEndMs = end,
            crossfadeMs = crossfade,
            tempoRatio = tempo,
            gainDb = gain
        )
    }
}

/**
 * Keeps the last queue across app restarts. The queue goes to a file, written whole and
 * atomically on one background thread; the playing position goes to preferences, which are
 * cheap enough to update every few seconds. Loading runs on the same thread, after any write
 * still waiting, so it always sees the latest queue.
 */
internal class QueueMemory(context: Context) {

    private val appContext = context.applicationContext
    private val file = AtomicFile(File(appContext.filesDir, QUEUE_FILE))
    private val prefs = appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, THREAD_NAME) }

    /** Writes [queue] in the background; an empty queue forgets everything. */
    fun saveQueue(queue: SavedQueue) {
        if (queue.items.isEmpty()) {
            clear()
            return
        }
        submit { writeQueue(queue) }
    }

    fun savePosition(position: SavedPosition) {
        if (position.uri.isEmpty()) return
        prefs.edit()
            .putInt(KEY_INDEX, position.index)
            .putLong(KEY_UID, position.uid)
            .putString(KEY_URI, position.uri)
            .putLong(KEY_POSITION, position.positionMs.coerceAtLeast(0L))
            .putInt(KEY_REPEAT, position.repeatMode)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        submit {
            file.delete()
        }
    }

    /** The saved queue, or null when there is none. Never fails. */
    fun load(): ListenableFuture<RestoredQueue?> {
        val result = SettableFuture.create<RestoredQueue?>()
        val queued = submit {
            try {
                result.set(readAll())
            } catch (e: RuntimeException) {
                Log.w(TAG, "cannot load the saved queue: ${e.message}")
                result.set(null)
            }
        }
        if (!queued) result.set(null)
        return result
    }

    /** Lets waiting writes finish, then stops the thread. */
    fun shutdown() {
        worker.shutdown()
    }

    private fun submit(task: () -> Unit): Boolean {
        return try {
            worker.execute(Runnable { task() })
            true
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "queue memory is closed: ${e.message}")
            false
        }
    }

    private fun writeQueue(queue: SavedQueue) {
        val bytes = QueueCodec.encode(queue).toByteArray(Charsets.UTF_8)
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: IOException) {
            Log.w(TAG, "cannot save the queue: ${e.message}")
            if (stream != null) file.failWrite(stream)
        }
    }

    private fun readAll(): RestoredQueue? {
        val text = try {
            String(file.readFully(), Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            // Nothing was saved yet: a normal first start.
            return null
        } catch (e: IOException) {
            Log.w(TAG, "cannot read the saved queue: ${e.message}")
            return null
        }
        val queue = QueueCodec.decode(text) ?: return null
        return QueueCodec.resolve(queue, readPosition())
    }

    private fun readPosition(): SavedPosition? {
        val uri = prefs.getString(KEY_URI, null) ?: return null
        return SavedPosition(
            index = prefs.getInt(KEY_INDEX, 0),
            uid = prefs.getLong(KEY_UID, 0L),
            uri = uri,
            positionMs = prefs.getLong(KEY_POSITION, 0L),
            repeatMode = prefs.getInt(KEY_REPEAT, 0)
        )
    }

    private companion object {
        const val TAG = "QueueMemory"
        const val QUEUE_FILE = "last_queue.txt"
        const val PREFS_FILE = "last_position"
        const val THREAD_NAME = "queue-memory"
        const val KEY_INDEX = "index"
        const val KEY_UID = "uid"
        const val KEY_URI = "uri"
        const val KEY_POSITION = "position_ms"
        const val KEY_REPEAT = "repeat_mode"
    }
}
