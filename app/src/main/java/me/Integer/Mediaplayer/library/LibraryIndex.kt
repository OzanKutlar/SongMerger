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
 * The scanned library cached as one JSON file, so the app opens without rescanning.
 * Writes are atomic: a crash mid-write leaves the previous file intact.
 * Blocking: call it off the main thread.
 */
class LibraryIndex(context: Context) {

    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))

    /** The cached tracks; empty when there is no cache yet or it cannot be read. */
    fun load(): List<Track> {
        if (!file.baseFile.exists()) return emptyList()
        return try {
            parse(String(file.readFully(), Charsets.UTF_8))
        } catch (e: IOException) {
            Log.w(TAG, "cannot read the library cache: ${e.message}")
            emptyList()
        } catch (e: JSONException) {
            Log.w(TAG, "library cache is damaged, a rescan will rebuild it: ${e.message}")
            emptyList()
        }
    }

    /** @return false when the cache could not be written */
    fun save(tracks: List<Track>): Boolean {
        var stream: FileOutputStream? = null
        return try {
            val bytes = serialise(tracks).toByteArray(Charsets.UTF_8)
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
            true
        } catch (e: IOException) {
            file.failWrite(stream)
            Log.w(TAG, "cannot write the library cache: ${e.message}")
            false
        } catch (e: JSONException) {
            file.failWrite(stream)
            Log.w(TAG, "cannot encode the library cache: ${e.message}")
            false
        }
    }

    private fun parse(text: String): List<Track> {
        val root = JSONObject(text)
        if (root.optInt(KEY_VERSION) != VERSION) return emptyList()
        val array = root.getJSONArray(KEY_TRACKS)
        val count = minOf(array.length(), MAX_TRACKS)
        val tracks = ArrayList<Track>(count)
        for (i in 0 until count) {
            val track = toTrack(array.getJSONObject(i))
            if (track != null) tracks.add(track)
        }
        return tracks
    }

    private fun toTrack(item: JSONObject): Track? {
        val uri = item.optString("u")
        if (uri.isEmpty()) return null
        return Track(
            uri = uri,
            title = item.optString("t"),
            artistTag = item.optString("a"),
            album = item.optString("al"),
            durationMs = item.optLong("d"),
            folderUri = item.optString("f"),
            fileName = item.optString("n"),
            sizeBytes = item.optLong("s"),
            modifiedMs = item.optLong("m")
        )
    }

    private fun serialise(tracks: List<Track>): String {
        val array = JSONArray()
        for (track in tracks.take(MAX_TRACKS)) array.put(toJson(track))
        return JSONObject().put(KEY_VERSION, VERSION).put(KEY_TRACKS, array).toString()
    }

    private fun toJson(track: Track): JSONObject {
        return JSONObject()
            .put("u", track.uri)
            .put("t", track.title)
            .put("a", track.artistTag)
            .put("al", track.album)
            .put("d", track.durationMs)
            .put("f", track.folderUri)
            .put("n", track.fileName)
            .put("s", track.sizeBytes)
            .put("m", track.modifiedMs)
    }

    private companion object {
        const val TAG = "LibraryIndex"
        const val FILE_NAME = "library.json"
        const val VERSION = 1
        const val KEY_VERSION = "version"
        const val KEY_TRACKS = "tracks"
        const val MAX_TRACKS = 50000
    }
}
