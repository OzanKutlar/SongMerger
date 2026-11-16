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

/**
 * The user's playlists, kept in one small JSON file inside the app. Playlists hold song
 * URIs, so they survive rescans. Main thread only; [onChanged] runs after every change.
 */
class PlaylistStore(context: Context, private val onChanged: () -> Unit) {

    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
    private var loaded: MutableList<Playlist>? = null

    /** Every playlist, sorted by name. */
    fun all(): List<Playlist> {
        return items().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun get(id: Long): Playlist? {
        return items().firstOrNull { it.id == id }
    }

    /** @return null when the name is blank or there are too many playlists */
    fun create(rawName: String?): Playlist? {
        val name = Playlist.cleanName(rawName)
        val items = items()
        if (name.isEmpty() || items.size >= MAX_PLAYLISTS) return null
        val id = (items.maxOfOrNull { it.id } ?: 0L) + 1L
        val playlist = Playlist(id, name, emptyList())
        items.add(playlist)
        commit()
        return playlist
    }

    fun rename(id: Long, rawName: String?): Boolean {
        val name = Playlist.cleanName(rawName)
        if (name.isEmpty()) return false
        return update(id) { it.copy(name = name) }
    }

    fun delete(id: Long) {
        val items = items()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        discardImage(items.removeAt(index), "")
        commit()
    }

    /** @return false when the song is already in the playlist or the playlist is full */
    fun addTrack(id: Long, trackUri: String): Boolean {
        val playlist = get(id) ?: return false
        if (trackUri.isEmpty() || trackUri in playlist.trackUris) return false
        if (playlist.trackUris.size >= MAX_TRACKS) return false
        return update(id) { it.copy(trackUris = it.trackUris + trackUri) }
    }

    /** Removing the song whose cover the playlist uses puts the cover back on automatic. */
    fun removeTrack(id: Long, trackUri: String) {
        update(id) { old ->
            val wasCover = old.coverType == Playlist.COVER_TRACK && old.coverRef == trackUri
            old.copy(
                trackUris = old.trackUris - trackUri,
                coverType = if (wasCover) Playlist.COVER_AUTO else old.coverType,
                coverRef = if (wasCover) "" else old.coverRef
            )
        }
    }

    /**
     * Stores a new song order. Songs missing from [ordered] (for example files that are
     * currently not in the library) keep their place at the end.
     */
    fun reorder(id: Long, ordered: List<String>) {
        update(id) { old ->
            val known = old.trackUris.toSet()
            val kept = ordered.filter { it in known }.distinct()
            val keptSet = kept.toSet()
            old.copy(trackUris = kept + old.trackUris.filter { it !in keptSet })
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

    // ------------------------------------------------------------------ helpers

    private fun update(id: Long, change: (Playlist) -> Playlist): Boolean {
        val items = items()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        items[index] = change(items[index])
        commit()
        return true
    }

    /** Deletes the imported cover image of [playlist], unless it is the one to [keep]. */
    private fun discardImage(playlist: Playlist, keep: String) {
        if (playlist.coverType != Playlist.COVER_IMAGE) return
        if (playlist.coverRef.isEmpty() || playlist.coverRef == keep) return
        if (!File(playlist.coverRef).delete()) Log.w(TAG, "cannot delete old cover ${playlist.coverRef}")
    }

    private fun commit() {
        write(items())
        onChanged()
    }

    private fun items(): MutableList<Playlist> {
        loaded?.let { return it }
        val read = read()
        loaded = read
        return read
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
        return Playlist(id, name, uris, type, item.optString("ref"))
    }

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
    }
}
