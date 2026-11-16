package me.Integer.Mediaplayer.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

/** Reads the tags of one audio file. Blocking: call it off the main thread. */
class TagReader(context: Context) {

    data class Tags(val title: String?, val artist: String?, val album: String?, val durationMs: Long)

    private val appContext = context.applicationContext

    /** @return null when the file cannot be opened or is not readable audio */
    fun read(uri: Uri): Tags? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            extract(retriever)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot read tags of $uri: ${e.message}")
            null
        } finally {
            release(retriever)
        }
    }

    private fun extract(retriever: MediaMetadataRetriever): Tags {
        val artist = text(retriever, MediaMetadataRetriever.METADATA_KEY_ARTIST)
            ?: text(retriever, MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        val duration = text(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        return Tags(
            title = text(retriever, MediaMetadataRetriever.METADATA_KEY_TITLE),
            artist = artist,
            album = text(retriever, MediaMetadataRetriever.METADATA_KEY_ALBUM),
            durationMs = maxOf(0L, duration ?: 0L)
        )
    }

    private fun text(retriever: MediaMetadataRetriever, key: Int): String? {
        return retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun release(retriever: MediaMetadataRetriever) {
        try {
            retriever.release()
        } catch (e: Exception) {
            Log.w(TAG, "cannot release the tag reader: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "TagReader"
    }
}
