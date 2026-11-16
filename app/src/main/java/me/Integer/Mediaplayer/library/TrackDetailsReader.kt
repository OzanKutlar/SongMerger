package me.Integer.Mediaplayer.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import me.Integer.Mediaplayer.playback.PlaybackClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads extended file and tag metadata for the Track Details dialog.
 */
object TrackDetailsReader {

    data class Details(
        val title: String,
        val artists: String,
        val rawArtistTag: String,
        val album: String,
        val year: String,
        val genre: String,
        val trackNumber: String,
        val duration: String,
        val bitrate: String,
        val sampleRate: String,
        val format: String,
        val fileSize: String,
        val modifiedDate: String,
        val location: String
    )

    private const val TAG = "TrackDetailsReader"
    private const val BYTES_PER_KB = 1024.0
    private const val BYTES_PER_MB = 1024.0 * 1024.0

    fun read(context: Context, track: Track, formattedArtists: String): Details {
        val uri = Uri.parse(track.uri)
        val extra = readExtraTags(context, uri)
        return Details(
            title = track.title,
            artists = formattedArtists,
            rawArtistTag = track.artistTag,
            album = track.album,
            year = extra.year,
            genre = extra.genre,
            trackNumber = extra.trackNumber,
            duration = if (track.durationMs > 0L) PlaybackClock.format(track.durationMs) else "",
            bitrate = extra.bitrate,
            sampleRate = extra.sampleRate,
            format = extra.mimeType,
            fileSize = formatSize(track.sizeBytes),
            modifiedDate = formatDate(track.modifiedMs),
            location = readableLocation(uri, track.fileName)
        )
    }

    private class ExtraTags(
        val year: String = "",
        val genre: String = "",
        val trackNumber: String = "",
        val bitrate: String = "",
        val sampleRate: String = "",
        val mimeType: String = ""
    )

    private fun readExtraTags(context: Context, uri: Uri): ExtraTags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context.applicationContext, uri)
            val year = text(retriever, MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: text(retriever, MediaMetadataRetriever.METADATA_KEY_DATE).orEmpty()
            val bitrateRaw = text(retriever, MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            val bitrate = if (bitrateRaw != null && bitrateRaw > 0L) "${bitrateRaw / 1000L} kbps" else ""
            val sampleRaw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                text(retriever, MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toLongOrNull()
            } else {
                null
            }
            val sampleRate = if (sampleRaw != null && sampleRaw > 0L) "$sampleRaw Hz" else ""
            ExtraTags(
                year = year,
                genre = text(retriever, MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty(),
                trackNumber = text(retriever, MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).orEmpty(),
                bitrate = bitrate,
                sampleRate = sampleRate,
                mimeType = text(retriever, MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot read extra tags for $uri: ${e.message}")
            ExtraTags()
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "cannot release tag reader: ${e.message}")
            }
        }
    }

    private fun text(retriever: MediaMetadataRetriever, key: Int): String? {
        return retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        if (bytes >= BYTES_PER_MB) {
            return String.format(Locale.US, "%.2f MB (%d bytes)", bytes / BYTES_PER_MB, bytes)
        }
        return String.format(Locale.US, "%.1f KB (%d bytes)", bytes / BYTES_PER_KB, bytes)
    }

    private fun formatDate(modifiedMs: Long): String {
        if (modifiedMs <= 0L) return ""
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(modifiedMs))
        } catch (e: RuntimeException) {
            ""
        }
    }

    private fun readableLocation(uri: Uri, fallbackName: String): String {
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            if (docId.startsWith("primary:")) {
                "/storage/emulated/0/" + docId.removePrefix("primary:")
            } else {
                docId.replace(':', '/')
            }
        } catch (e: RuntimeException) {
            uri.path ?: fallbackName
        }
    }
}
