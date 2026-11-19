package me.Integer.Mediaplayer.library

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds the audio files under the picked folders and turns them into tracks.
 * Files already known with the same size and modification time are reused, so only new
 * or changed files have their tags read. Every walk is bounded in depth, folder count
 * and file count. Blocking: call it off the main thread.
 */
class LibraryScanner(context: Context, private val tagReader: TagReader) {

    fun interface Progress {
        fun onProgress(done: Int, total: Int)
    }

    private data class FoundFile(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val folderUri: String
    )

    /** One folder tree being walked: folders still to list, and where results go. */
    private class Walk(val tree: Uri, val out: MutableList<FoundFile>, val seen: MutableSet<String>) {
        val pending = ArrayDeque<Pair<String, Int>>()
    }

    private val resolver = context.applicationContext.contentResolver

    /** @return the new track list sorted by title, or null when the scan was cancelled */
    fun scan(
        folders: List<Uri>,
        known: List<Track>,
        cancelled: AtomicBoolean,
        progress: Progress
    ): List<Track>? {
        val files = ArrayList<FoundFile>()
        val seen = HashSet<String>()
        for (folder in folders.take(MAX_ROOTS)) {
            if (cancelled.get()) return null
            walk(Walk(folder, files, seen), cancelled)
        }
        val tracks = reconcile(files, known, cancelled, progress) ?: return null
        return tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    // ------------------------------------------------------------------ one file by name

    /**
     * Songs called [fileName] (ignoring case; an exact-case match wins) in the picked [folders].
     * The top level of every folder is checked first; only when nothing is there are their
     * subfolders searched, stopping at the first folder with a match. Only matches have
     * their tags read.
     */
    fun findByName(folders: List<Uri>, fileName: String, cancelled: AtomicBoolean): List<Track> {
        if (fileName.isEmpty()) return emptyList()
        val found = ArrayList<FoundFile>()
        val subfolders = LinkedHashMap<Uri, MutableList<String>>()
        for (tree in folders.take(MAX_ROOTS)) {
            if (cancelled.get()) return emptyList()
            val rootId = rootIdOf(tree) ?: continue
            val below = ArrayList<String>()
            matchChildren(tree, rootId, fileName, found, below)
            subfolders[tree] = below
        }
        if (found.isEmpty()) {
            for ((tree, start) in subfolders) {
                if (cancelled.get() || found.isNotEmpty()) break
                searchBelow(tree, start, fileName, found, cancelled)
            }
        }
        val exact = found.filter { it.name == fileName }
        return exact.ifEmpty { found }.distinctBy { it.uri }.map { buildTrack(it) }
    }

    /** Lists one folder: files called [fileName] go to [out], subfolder ids to [subfolders]. */
    private fun matchChildren(
        tree: Uri,
        documentId: String,
        fileName: String,
        out: MutableList<FoundFile>,
        subfolders: MutableList<String>?
    ) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        try {
            resolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
                var rows = 0
                while (rows < MAX_CHILDREN && cursor.moveToNext()) {
                    rows++
                    matchRow(tree, cursor, fileName, out, subfolders)
                }
            }
        } catch (e: RuntimeException) {
            // SecurityException when access was revoked, or a failing storage provider.
            Log.w(TAG, "cannot list $documentId: ${e.message}")
        }
    }

    private fun matchRow(
        tree: Uri,
        cursor: Cursor,
        fileName: String,
        out: MutableList<FoundFile>,
        subfolders: MutableList<String>?
    ) {
        val id = cursor.getString(COLUMN_ID) ?: return
        val name = cursor.getString(COLUMN_NAME) ?: return
        if (name.startsWith(".")) return
        val mime = cursor.getString(COLUMN_MIME)
        if (mime == Document.MIME_TYPE_DIR) {
            subfolders?.add(id)
            return
        }
        if (out.size >= MAX_NAME_MATCHES) return
        if (!name.equals(fileName, ignoreCase = true) || !isAudio(name, mime)) return
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        out.add(FoundFile(uri, name, cursor.getLong(COLUMN_SIZE), cursor.getLong(COLUMN_MODIFIED), tree.toString()))
    }

    /** Breadth first below one picked folder, within the usual limits; stops once a match is found. */
    private fun searchBelow(
        tree: Uri,
        start: List<String>,
        fileName: String,
        out: MutableList<FoundFile>,
        cancelled: AtomicBoolean
    ) {
        val pending = ArrayDeque<Pair<String, Int>>()
        for (id in start) pending.addLast(id to 1)
        for (visited in 0 until MAX_FOLDERS) {
            if (cancelled.get() || out.isNotEmpty()) return
            val (documentId, depth) = pending.removeFirstOrNull() ?: return
            val below = if (depth < MAX_DEPTH) ArrayList<String>() else null
            matchChildren(tree, documentId, fileName, out, below)
            below?.forEach { pending.addLast(it to depth + 1) }
        }
    }

    private fun rootIdOf(tree: Uri): String? {
        return try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "not a folder: $tree")
            null
        }
    }

    // ------------------------------------------------------------------ folder walk

    private fun walk(walk: Walk, cancelled: AtomicBoolean) {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(walk.tree)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "not a folder: ${walk.tree}")
            return
        }
        walk.pending.addLast(rootId to 0)
        for (visited in 0 until MAX_FOLDERS) {
            if (cancelled.get()) return
            val (documentId, depth) = walk.pending.removeFirstOrNull() ?: return
            listChildren(walk, documentId, depth)
        }
        Log.w(TAG, "folder limit reached under ${walk.tree}, the rest is skipped")
    }

    private fun listChildren(walk: Walk, documentId: String, depth: Int) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(walk.tree, documentId)
        try {
            resolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
                var rows = 0
                while (rows < MAX_CHILDREN && cursor.moveToNext()) {
                    rows++
                    acceptRow(walk, cursor, depth)
                }
            }
        } catch (e: RuntimeException) {
            // SecurityException when access was revoked, or a failing storage provider.
            Log.w(TAG, "cannot list $documentId: ${e.message}")
        }
    }

    private fun acceptRow(walk: Walk, cursor: Cursor, depth: Int) {
        val id = cursor.getString(COLUMN_ID) ?: return
        val name = cursor.getString(COLUMN_NAME) ?: return
        if (name.startsWith(".")) return
        val mime = cursor.getString(COLUMN_MIME)
        if (mime == Document.MIME_TYPE_DIR) {
            if (depth < MAX_DEPTH) walk.pending.addLast(id to depth + 1)
            return
        }
        if (!isAudio(name, mime) || walk.out.size >= MAX_FILES || !walk.seen.add(id)) return
        val uri = DocumentsContract.buildDocumentUriUsingTree(walk.tree, id)
        walk.out.add(
            FoundFile(uri, name, cursor.getLong(COLUMN_SIZE), cursor.getLong(COLUMN_MODIFIED), walk.tree.toString())
        )
    }

    private fun isAudio(name: String, mime: String?): Boolean {
        if (mime != null && mime.startsWith("audio/")) return true
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in AUDIO_EXTENSIONS
    }

    // ------------------------------------------------------------------ tracks

    private fun reconcile(
        files: List<FoundFile>,
        known: List<Track>,
        cancelled: AtomicBoolean,
        progress: Progress
    ): List<Track>? {
        val knownByUri = known.associateBy { it.uri }
        val tracks = ArrayList<Track>(files.size)
        for ((index, file) in files.withIndex()) {
            if (cancelled.get()) return null
            val old = knownByUri[file.uri.toString()]
            tracks.add(if (old != null && isUnchanged(old, file)) old else buildTrack(file))
            if (index % PROGRESS_STEP == 0) progress.onProgress(index + 1, files.size)
        }
        progress.onProgress(files.size, files.size)
        return tracks
    }

    private fun isUnchanged(old: Track, file: FoundFile): Boolean {
        return old.sizeBytes == file.sizeBytes && old.modifiedMs == file.modifiedMs
    }

    /** Tags first; whatever they lack comes from the file name. */
    private fun buildTrack(file: FoundFile): Track {
        val tags = tagReader.read(file.uri)
        val guess = TitleGuesser.fromFileName(file.name)
        return Track(
            uri = file.uri.toString(),
            title = tags?.title ?: guess.title.ifEmpty { file.name },
            artistTag = tags?.artist ?: guess.artistTag,
            album = tags?.album.orEmpty(),
            durationMs = tags?.durationMs ?: 0L,
            folderUri = file.folderUri,
            fileName = file.name,
            sizeBytes = file.sizeBytes,
            modifiedMs = file.modifiedMs
        )
    }

    private companion object {
        const val TAG = "LibraryScanner"
        const val MAX_ROOTS = 32
        const val MAX_DEPTH = 12
        const val MAX_FOLDERS = 5000
        const val MAX_CHILDREN = 20000
        const val MAX_FILES = 50000
        const val PROGRESS_STEP = 10
        const val MAX_NAME_MATCHES = 16
        const val COLUMN_ID = 0
        const val COLUMN_NAME = 1
        const val COLUMN_MIME = 2
        const val COLUMN_SIZE = 3
        const val COLUMN_MODIFIED = 4
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
        )
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma")
    }
}
