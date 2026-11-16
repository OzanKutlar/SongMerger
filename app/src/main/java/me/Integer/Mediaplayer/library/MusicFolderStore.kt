package me.Integer.Mediaplayer.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * The folders the user picked with the system folder picker. Access is kept across
 * restarts through persisted read permissions; a folder whose permission is gone is no
 * longer listed.
 */
class MusicFolderStore(context: Context) {

    data class Folder(val uri: Uri, val name: String)

    private val resolver = context.applicationContext.contentResolver
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** @return false when the system refused to keep access to the folder */
    fun add(uri: Uri): Boolean {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot keep access to $uri: ${e.message}")
            return false
        }
        save(stored() + uri.toString())
        return true
    }

    fun remove(uri: Uri) {
        save(stored() - uri.toString())
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to release for $uri: ${e.message}")
        }
    }

    /** Picked folders that are still readable, in a stable order. */
    fun folders(): List<Folder> {
        val readable = resolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        return stored().filter { it in readable }.sorted().take(MAX_FOLDERS).map { text ->
            val uri = Uri.parse(text)
            Folder(uri, displayName(uri))
        }
    }

    private fun stored(): Set<String> {
        return prefs.getStringSet(KEY_FOLDERS, null)?.toSet() ?: emptySet()
    }

    private fun save(folders: Set<String>) {
        prefs.edit().putStringSet(KEY_FOLDERS, folders).apply()
    }

    /** "primary:Music/Rap" reads as "Music/Rap". */
    private fun displayName(uri: Uri): String {
        return try {
            val id = DocumentsContract.getTreeDocumentId(uri)
            id.substringAfter(':').ifEmpty { id }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "not a folder uri: $uri")
            uri.toString()
        }
    }

    private companion object {
        const val TAG = "MusicFolderStore"
        const val FILE = "music_folders"
        const val KEY_FOLDERS = "folders"
        const val MAX_FOLDERS = 32
    }
}
