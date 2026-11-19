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
        return useRetriever(appContext, uri) { retriever ->
            val artist = retriever.extractText(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractText(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val duration = retriever.extractText(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            Tags(
                title = retriever.extractText(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = artist,
                album = retriever.extractText(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = maxOf(0L, duration ?: 0L)
            )
        }
    }

    companion object {
        private const val TAG = "TagReader"

        fun <T> useRetriever(context: Context, uri: Uri, block: (MediaMetadataRetriever) -> T?): T? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context.applicationContext, uri)
                block(retriever)
            } catch (e: RuntimeException) {
                Log.w(TAG, "cannot read metadata of $uri: ${e.message}")
                null
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    Log.w(TAG, "cannot release metadata reader: ${e.message}")
                }
            }
        }

        fun MediaMetadataRetriever.extractText(key: Int): String? {
            return extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
