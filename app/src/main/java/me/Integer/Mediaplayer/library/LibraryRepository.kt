package me.Integer.Mediaplayer.library

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's music library: cached tracks, scanning, favorites, and the artist/album views.
 * One instance per process, shared by the screens and the playback service.
 *
 * All public members are main thread only. Loading and scanning run on one worker thread
 * and report back on the main thread.
 */
class LibraryRepository private constructor(appContext: Context) {

    interface Listener {
        /** Tracks, favorites, the separator or a manual artist split changed. */
        fun onLibraryChanged()

        fun onScanState(state: ScanState)
    }

    /** [total] is 0 while the folders are still being listed. */
    data class ScanState(val running: Boolean, val done: Int, val total: Int)

    val settings = PlayerSettings(appContext)
    val folders = MusicFolderStore(appContext)
    val overrides = ArtistOverrides(appContext)
    val favorites = FavoritesStore(appContext)
    val playlists = PlaylistStore(appContext) { notifyArtistRulesChanged() }

    private val index = LibraryIndex(appContext)
    private val scanner = LibraryScanner(appContext, TagReader(appContext))
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val cancelled = AtomicBoolean(false)
    private var loadStarted = false
    private var uriIndex: Map<String, Track>? = null

    var tracks: List<Track> = emptyList()
        private set
    var scanState = ScanState(false, 0, 0)
        private set

    // ------------------------------------------------------------------ listeners

    fun addListener(listener: Listener) {
        if (listener !in listeners) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // ------------------------------------------------------------------ loading & scanning

    /** Loads the cached library once per process. */
    fun loadIfNeeded() {
        if (loadStarted) return
        loadStarted = true
        worker.execute {
            val cached = index.load()
            main.post { if (tracks.isEmpty() && cached.isNotEmpty()) publish(cached) }
        }
    }

    /** Scans the picked folders. Ignored while a scan is already running. */
    fun rescan() {
        if (scanState.running) return
        val roots = folders.folders().map { it.uri }
        cancelled.set(false)
        setScanState(ScanState(true, 0, 0))
        worker.execute { runScan(roots) }
    }

    fun cancelScan() {
        cancelled.set(true)
    }

    /** Worker thread. Reads the cache itself, so a scan right after launch stays incremental. */
    private fun runScan(roots: List<Uri>) {
        val result = try {
            scanner.scan(roots, index.load(), cancelled) { done, total ->
                main.post { if (scanState.running) setScanState(ScanState(true, done, total)) }
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "scan failed", e)
            null
        }
        if (result != null) index.save(result)
        main.post {
            if (result != null) publish(result)
            setScanState(ScanState(false, 0, 0))
        }
    }

    // ------------------------------------------------------------------ queries & favorites

    fun findTrack(trackUri: String): Track? {
        if (trackUri.isEmpty()) return null
        return tracks.firstOrNull { it.uri == trackUri }
    }

    fun isFavorite(trackUri: String): Boolean {
        return favorites.isFavorite(trackUri)
    }

    fun toggleFavorite(trackUri: String): Boolean {
        val state = favorites.toggle(trackUri)
        notifyArtistRulesChanged()
        return state
    }

    fun favoriteTracks(): List<Track> {
        val favUris = favorites.all()
        if (favUris.isEmpty()) return emptyList()
        return tracks.filter { it.uri in favUris }
    }

    fun tracksInAlbum(album: String): List<Track> {
        return tracks.filter { it.album.equals(album, ignoreCase = true) }
    }

    /** The library songs for [uris], in that order. Songs no longer in the library are skipped. */
    fun resolveTracks(uris: List<String>): List<Track> {
        val index = uriIndex ?: tracks.associateBy { it.uri }.also { uriIndex = it }
        return uris.mapNotNull { index[it] }
    }

    // ------------------------------------------------------------------ artists

    /** The individual artists of a song: the manual split if there is one, else automatic. */
    fun artistsOf(track: Track): List<String> {
        return overrides.get(track.uri) ?: ArtistSplitter.split(track.artistTag, settings.artistSeparator)
    }

    /** The artists of a song as one display line; empty when unknown. */
    fun displayArtist(track: Track): String {
        return ArtistSplitter.join(artistsOf(track), settings.artistSeparator)
    }

    /** Every artist with their songs. A song with several artists is listed under each. */
    fun artists(): List<Artist> {
        return ArtistGrouper.group(tracks) { artistsOf(it) }
    }

    /** Call after changing the separator, favorites or a manual split, so the screens update. */
    fun notifyArtistRulesChanged() {
        for (listener in ArrayList(listeners)) listener.onLibraryChanged()
    }

    // ------------------------------------------------------------------ helpers

    private fun publish(next: List<Track>) {
        tracks = next
        uriIndex = null
        for (listener in ArrayList(listeners)) listener.onLibraryChanged()
    }

    private fun setScanState(next: ScanState) {
        scanState = next
        for (listener in ArrayList(listeners)) listener.onScanState(next)
    }

    companion object {
        private const val TAG = "LibraryRepository"

        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository {
            return instance ?: synchronized(this) {
                instance ?: LibraryRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
