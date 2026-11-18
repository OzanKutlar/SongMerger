package me.Integer.Mediaplayer.library

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The user's playlists, kept in one small JSON file inside the app. Playlists hold song
 * URIs, so they survive rescans.
 *
 * Every read and change goes through one lock, so the worker that preloads and filters
 * playlists never sees a half-made change. Changes are written to disk as snapshots on a
 * background thread, in order. [onChanged] runs on the calling thread, outside the lock.
 */
class PlaylistStore(context: Context, private val onChanged: () -> Unit) {

    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "Mediaplayer-Playlists") }
    private var loaded: MutableList<Playlist>? = null

    /** Warms the in-memory playlist cache off the main thread. */
    fun preload() {
        synchronized(lock) { items() }
    }

    /** Every playlist, sorted by name. */
    fun all(): List<Playlist> {
        return synchronized(lock) { items().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) }
    }

    fun get(id: Long): Playlist? {
        return synchronized(lock) { items().firstOrNull { it.id == id } }
    }

    /** @return null when the name is blank or there are too many playlists */
    fun create(rawName: String?): Playlist? {
        val name = Playlist.cleanName(rawName)
        if (name.isEmpty()) return null
        var created: Playlist? = null
        mutate { items ->
            if (items.size >= MAX_PLAYLISTS) {
                false
            } else {
                val playlist = Playlist(nextId(items), name, emptyList())
                items.add(playlist)
                created = playlist
                true
            }
        }
        return created
    }

    /**
     * Creates a mashup playlist that plays [segments] in order.
     *
     * @return null when the name is blank, there are fewer than two steps, or there are
     * too many playlists
     */
    fun createMix(rawName: String?, segments: List<MixSegment>): Playlist? {
        val name = Playlist.cleanName(rawName)
        val steps = segments.filter { it.trackUri.isNotEmpty() }.take(MAX_SEGMENTS)
        if (name.isEmpty() || steps.size < MIN_MIX_STEPS) return null
        val uris = steps.map { it.trackUri }.distinct().take(MAX_TRACKS)
        var created: Playlist? = null
        mutate { items ->
            if (items.size >= MAX_PLAYLISTS) {
                false
            } else {
                val playlist = Playlist(nextId(items), name, uris, segments = steps)
                items.add(playlist)
                created = playlist
                true
            }
        }
        return created
    }

    fun rename(id: Long, rawName: String?): Boolean {
        val name = Playlist.cleanName(rawName)
        if (name.isEmpty()) return false
        return update(id) { it.copy(name = name) }
    }

    fun delete(id: Long) {
        mutate { items ->
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) {
                false
            } else {
                discardImage(items.removeAt(index), "")
                true
            }
        }
    }

    /** @return false when the song is already in the playlist or the playlist is full */
    fun addTrack(id: Long, trackUri: String): Boolean {
        if (trackUri.isEmpty()) return false
        return update(id) { old ->
            if (trackUri in old.trackUris || old.trackUris.size >= MAX_TRACKS) {
                null
            } else {
                old.copy(trackUris = old.trackUris + trackUri, segments = extended(old, listOf(trackUri)))
            }
        }
    }

    /** Appends [trackUris] not yet in the playlist in one write; returns how many were added. */
    fun addTracks(id: Long, trackUris: List<String>): Int {
        var added = 0
        update(id) { old ->
            val additions = newUris(old, trackUris)
            added = additions.size
            if (additions.isEmpty()) {
                null
            } else {
                old.copy(trackUris = old.trackUris + additions, segments = extended(old, additions))
            }
        }
        return added
    }

    /** Removing the song whose cover the playlist uses puts the cover back on automatic. */
    fun removeTrack(id: Long, trackUri: String) {
        update(id) { old ->
            val wasCover = old.coverType == Playlist.COVER_TRACK && old.coverRef == trackUri
            old.copy(
                trackUris = old.trackUris - trackUri,
                segments = without(old.segments, trackUri),
                coverType = if (wasCover) Playlist.COVER_AUTO else old.coverType,
                coverRef = if (wasCover) "" else old.coverRef
            )
        }
    }

    /**
     * Stores a new song order. Songs missing from [ordered] (for example files that are
     * currently not in the library) keep their place at the end. A mashup keeps the order
     * its transitions were chosen for.
     */
    fun reorder(id: Long, ordered: List<String>) {
        update(id) { old ->
            if (old.isMix) {
                null
            } else {
                val known = old.trackUris.toSet()
                val kept = ordered.filter { it in known }.distinct()
                val keptSet = kept.toSet()
                old.copy(trackUris = kept + old.trackUris.filter { it !in keptSet })
            }
        }
    }

    /** @return false when the playlist no longer exists or [type] is not a cover type */
    fun setCover(id: Long, type: Int, ref: String): Boolean {
        if (type !in Playlist.COVER_AUTO..Playlist.COVER_IMAGE) return false
        return update(id) { old ->
            discardImage(old, ref)
            old.copy(coverType = type, coverRef = if (type == Playlist.COVER_AUTO) "" else ref)
        }
    }

    /** Replaces the mix segments of playlist [id]; fewer than two steps turns it into a plain playlist. */
    fun updateSegments(id: Long, nextSegments: List<MixSegment>): Boolean {
        val steps = nextSegments.filter { it.trackUri.isNotEmpty() }.take(MAX_SEGMENTS)
        return update(id) { old ->
            if (steps.size < MIN_MIX_STEPS) {
                old.copy(segments = emptyList())
            } else {
                val uris = steps.map { it.trackUri }.distinct().take(MAX_TRACKS)
                old.copy(trackUris = uris, segments = steps)
            }
        }
    }

    // ------------------------------------------------------------------ changes

    /** Replaces one playlist; [change] returns null to leave it as it is. */
    private fun update(id: Long, change: (Playlist) -> Playlist?): Boolean {
        return mutate { items ->
            val index = items.indexOfFirst { it.id == id }
            val next = if (index < 0) null else change(items[index])
            if (next == null) {
                false
            } else {
                items[index] = next
                true
            }
        }
    }

    /**
     * Runs [change] under the lock; when it reports a change, the new list is written in the
     * background and listeners are told, outside the lock.
     */
    private fun mutate(change: (MutableList<Playlist>) -> Boolean): Boolean {
        val snapshot: List<Playlist> = synchronized(lock) {
            val items = items()
            if (!change(items)) return false
            ArrayList(items)
        }
        scheduleWrite(snapshot)
        onChanged()
        return true
    }

    private fun scheduleWrite(snapshot: List<Playlist>) {
        try {
            writer.execute { write(snapshot) }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "playlist writer unavailable, saving here: ${e.message}")
            write(snapshot)
        }
    }

    /** Must be called with [lock] held. */
    private fun items(): MutableList<Playlist> {
        loaded?.let { return it }
        val read = read()
        loaded = read
        return read
    }

    private fun nextId(items: List<Playlist>): Long {
        return (items.maxOfOrNull { it.id } ?: 0L) + 1L
    }

    /** [candidates] not yet in [playlist], in order and within the room left. */
    private fun newUris(playlist: Playlist, candidates: List<String>): List<String> {
        val existing = playlist.trackUris.toHashSet()
        val room = (MAX_TRACKS - playlist.trackUris.size).coerceAtLeast(0)
        val additions = ArrayList<String>()
        if (room == 0) return additions
        for (uri in candidates) {
            if (uri.isNotEmpty() && existing.add(uri)) {
                additions.add(uri)
                if (additions.size >= room) break
            }
        }
        return additions
    }

    /** Deletes the imported cover image of [playlist], unless it is the one to [keep]. */
    private fun discardImage(playlist: Playlist, keep: String) {
        if (playlist.coverType != Playlist.COVER_IMAGE) return
        if (playlist.coverRef.isEmpty() || playlist.coverRef == keep) return
        if (!File(playlist.coverRef).delete()) Log.w(TAG, "cannot delete old cover ${playlist.coverRef}")
    }

    /** A mashup gains each new song as a whole-song step; a plain playlist has no steps. */
    private fun extended(playlist: Playlist, uris: List<String>): List<MixSegment> {
        if (!playlist.isMix) return emptyList()
        val added = uris.map {
            MixSegment(it, 0L, MixSegment.END_OF_TRACK, MixSegment.DEFAULT_CROSSFADE_MS)
        }
        return (playlist.segments + added).take(MAX_SEGMENTS)
    }

    /** The steps without one song; with fewer than two left the playlist becomes a plain one. */
    private fun without(segments: List<MixSegment>, trackUri: String): List<MixSegment> {
        val kept = segments.filter { it.trackUri != trackUri }
        return if (kept.size >= MIN_MIX_STEPS) kept else emptyList()
    }

    // ------------------------------------------------------------------ file

    private fun read(): MutableList<Playlist> {
        if (!file.baseFile.exists()) return ArrayList()
        return try {
            parse(String(file.readFully(), Charsets.UTF_8))
        } catch (e: IOException) {
            Log.w(TAG, "cannot read the playlists: ${e.message}")
            ArrayList()
        } catch (e: JSONException) {
            Log.w(TAG, "playlist file is damaged: ${e.message}")
            ArrayList()
        }
    }

    private fun parse(text: String): MutableList<Playlist> {
        val array = JSONObject(text).getJSONArray(KEY_PLAYLISTS)
        val count = minOf(array.length(), MAX_PLAYLISTS)
        val playlists = ArrayList<Playlist>(count)
        for (i in 0 until count) {
            val playlist = toPlaylist(array.getJSONObject(i))
            if (playlist != null) playlists.add(playlist)
        }
        return playlists
    }

    private fun toPlaylist(item: JSONObject): Playlist? {
        val id = item.optLong("id")
        val name = Playlist.cleanName(item.optString("name"))
        if (id <= 0L || name.isEmpty()) return null
        val array = item.optJSONArray("tracks") ?: JSONArray()
        val count = minOf(array.length(), MAX_TRACKS)
        val uris = List(count) { array.optString(it) }.filter { it.isNotEmpty() }
        val type = item.optInt("cover").takeIf { it in Playlist.COVER_AUTO..Playlist.COVER_IMAGE }
            ?: Playlist.COVER_AUTO
        val segments = toSegments(item.optJSONArray("segments"))
        return Playlist(id, name, uris, type, item.optString("ref"), segments)
    }

    /** Unusable steps are dropped; fewer than two usable steps reads as a plain playlist. */
    private fun toSegments(array: JSONArray?): List<MixSegment> {
        if (array == null) return emptyList()
        val count = minOf(array.length(), MAX_SEGMENTS)
        val segments = ArrayList<MixSegment>(count)
        for (i in 0 until count) {
            val item = array.optJSONObject(i) ?: continue
            val segment = MixSegment.create(
                trackUri = item.optString("u"),
                startMs = item.optLong("s", 0L),
                endMs = item.optLong("e", MixSegment.END_OF_TRACK),
                crossfadeMs = item.optLong("x", MixSegment.DEFAULT_CROSSFADE_MS),
                tempoRatio = item.optDouble("t", 1.0).toFloat(),
                gainDb = item.optDouble("g", 0.0).toFloat()
            )
            if (segment != null) segments.add(segment)
        }
        return if (segments.size >= MIN_MIX_STEPS) segments else emptyList()
    }

    private fun segmentsJson(segments: List<MixSegment>): JSONArray {
        val array = JSONArray()
        for (segment in segments.take(MAX_SEGMENTS)) {
            array.put(
                JSONObject()
                    .put("u", segment.trackUri)
                    .put("s", segment.startMs)
                    .put("e", segment.endMs)
                    .put("x", segment.crossfadeMs)
                    .put("t", segment.tempoRatio.toDouble())
                    .put("g", segment.gainDb.toDouble())
            )
        }
        return array
    }

    /** Writer thread (or the caller when the writer is gone). */
    private fun write(playlists: List<Playlist>) {
        var stream: FileOutputStream? = null
        try {
            val bytes = serialise(playlists).toByteArray(Charsets.UTF_8)
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            Log.w(TAG, "cannot save the playlists: ${e.message}")
        } catch (e: JSONException) {
            file.failWrite(stream)
            Log.w(TAG, "cannot encode the playlists: ${e.message}")
        }
    }

    private fun serialise(playlists: List<Playlist>): String {
        val array = JSONArray()
        for (playlist in playlists.take(MAX_PLAYLISTS)) {
            array.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("cover", playlist.coverType)
                    .put("ref", playlist.coverRef)
                    .put("tracks", JSONArray(playlist.trackUris.take(MAX_TRACKS)))
                    .put("segments", segmentsJson(playlist.segments))
            )
        }
        return JSONObject().put(KEY_VERSION, VERSION).put(KEY_PLAYLISTS, array).toString()
    }

    private companion object {
        const val TAG = "PlaylistStore"
        const val FILE_NAME = "playlists.json"
        const val VERSION = 1
        const val KEY_VERSION = "version"
        const val KEY_PLAYLISTS = "playlists"
        const val MAX_PLAYLISTS = 500
        const val MAX_TRACKS = 10000
        const val MAX_SEGMENTS = 2000
        const val MIN_MIX_STEPS = 2
    }
}
