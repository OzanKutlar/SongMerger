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

    /** What [importByName] did with one file name. */
    enum class ImportOutcome { ADDED, UPDATED, NOT_FOUND, NO_FOLDERS }

    val settings = PlayerSettings(appContext)
    val folders = MusicFolderStore(appContext)
    val overrides = ArtistOverrides(appContext)
    val favorites = FavoritesStore(appContext)
    val playlists = PlaylistStore(appContext) { notifyArtistRulesChanged() }
    val history = ListeningHistoryStore(appContext) { main.post { notifyListenersChanged() } }

    private val index = LibraryIndex(appContext)
    private val scanner = LibraryScanner(appContext, TagReader(appContext))
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val cancelled = AtomicBoolean(false)
    private var loadStarted = false
    private var uriIndex: Map<String, Track>? = null
    private var artistsCache: List<Artist>? = null
    private var albumsCache: List<Album>? = null
    private val artistsOfCache = HashMap<String, List<String>>()
    private val displayArtistCache = HashMap<String, String>()

    private val cacheLock = Any()

    @Volatile
    var isLoaded: Boolean = false
        private set
    @Volatile
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
            playlists.preload()
            history.preload()
            for (track in cached) {
                displayArtist(track)
            }
            main.post {
                isLoaded = true
                if (tracks.isEmpty() && cached.isNotEmpty()) {
                    publish(cached)
                } else {
                    notifyListenersChanged()
                }
            }
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

    /**
     * Adds the song file called [fileName] from the picked folders, without a rescan. It runs
     * on the library worker after any load or scan already waiting there, so it never overlaps
     * them. [done] gets the outcome on the main thread.
     */
    fun importByName(fileName: String, done: (ImportOutcome) -> Unit) {
        val roots = folders.folders().map { it.uri }
        if (roots.isEmpty()) {
            done(ImportOutcome.NO_FOLDERS)
            return
        }
        worker.execute { runImport(roots, fileName, done) }
    }

    /**
     * Worker thread. The saved index is the base: every scan and import saves it on this same
     * thread, and it works even when the app was started only for this import.
     */
    private fun runImport(roots: List<Uri>, fileName: String, done: (ImportOutcome) -> Unit) {
        val found = try {
            scanner.findByName(roots, fileName, AtomicBoolean(false))
        } catch (e: RuntimeException) {
            Log.e(TAG, "looking for $fileName failed", e)
            emptyList()
        }
        if (found.isEmpty()) {
            main.post { done(ImportOutcome.NOT_FOUND) }
            return
        }
        val base = index.load().ifEmpty { tracks }
        val known = base.mapTo(HashSet()) { it.uri }
        val replaced = found.any { it.uri in known }
        val merged = mergeTracks(base, found)
        if (!index.save(merged)) Log.w(TAG, "$fileName is shown but could not be cached")
        main.post {
            isLoaded = true
            publish(merged)
            done(if (replaced) ImportOutcome.UPDATED else ImportOutcome.ADDED)
        }
    }

    /** [base] with [found] added, or replacing the same file, sorted by title like a scan. */
    private fun mergeTracks(base: List<Track>, found: List<Track>): List<Track> {
        val byUri = LinkedHashMap<String, Track>(base.size + found.size)
        for (track in base) byUri[track.uri] = track
        for (track in found) byUri[track.uri] = track
        return byUri.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
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
            isLoaded = true
            if (result != null) publish(result) else notifyListenersChanged()
            setScanState(ScanState(false, 0, 0))
        }
    }

    // ------------------------------------------------------------------ queries & favorites

    fun findTrack(trackUri: String): Track? {
        if (trackUri.isEmpty()) return null
        val index = uriIndex ?: tracks.associateBy { it.uri }.also { uriIndex = it }
        return index[trackUri]
    }

    fun isFavorite(trackUri: String): Boolean {
        return favorites.isFavorite(trackUri)
    }

    fun toggleFavorite(trackUri: String): Boolean {
        val state = favorites.toggle(trackUri)
        notifyListenersChanged()
        return state
    }

    /** Marks all [trackUris] as favorites and notifies listeners if any were added. */
    fun addFavorites(trackUris: Collection<String>): Int {
        val added = favorites.addAll(trackUris)
        if (added > 0) notifyListenersChanged()
        return added
    }

    /** Unmarks all [trackUris] as favorites and notifies listeners if any were removed. */
    fun removeFavorites(trackUris: Collection<String>): Int {
        val removed = favorites.removeAll(trackUris)
        if (removed > 0) notifyListenersChanged()
        return removed
    }

    fun favoriteTracks(): List<Track> {
        val favUris = favorites.all()
        if (favUris.isEmpty()) return emptyList()
        return tracks.filter { it.uri in favUris }
    }

    /** Builds the system playlists and seasonal/monthly Recaps for the Favorites tab. */
    fun favoritesShowcase(): ListeningHistoryStore.FavoritesShowcase {
        return history.buildShowcase(favoriteTracks(), tracks)
    }

    /** Returns either a user playlist (`id > 0`) or a system/recap playlist (`id < 0`). */
    fun getPlaylistOrSystem(id: Long): Playlist? {
        if (id >= 0L) return playlists.get(id)
        return history.resolveSystemPlaylist(id, favoriteTracks(), tracks)?.playlist
    }

    /** For system/recap playlists (`id < 0`), returns the per-song listen stats in that timeframe. */
    fun systemPlaylistStats(id: Long): Map<String, ListeningHistoryStore.TrackListenStats> {
        if (id >= 0L) return emptyMap()
        return history.resolveSystemPlaylist(id, favoriteTracks(), tracks)?.statsByUri.orEmpty()
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
        synchronized(cacheLock) {
            return artistsOfCache.getOrPut(track.uri) {
                overrides.get(track.uri) ?: ArtistSplitter.split(track.artistTag, settings.artistSeparator)
            }
        }
    }

    /** The artists of a song as one display line; empty when unknown. */
    fun displayArtist(track: Track): String {
        synchronized(cacheLock) {
            return displayArtistCache.getOrPut(track.uri) {
                ArtistSplitter.join(artistsOf(track), settings.artistSeparator)
            }
        }
    }

    /** Every artist with their songs. A song with several artists is listed under each. */
    fun artists(): List<Artist> {
        synchronized(cacheLock) {
            return artistsCache ?: ArtistGrouper.group(tracks) { artistsOf(it) }.also { artistsCache = it }
        }
    }

    /** Every album with its primary display artist and songs. */
    fun albums(): List<Album> {
        synchronized(cacheLock) {
            return albumsCache ?: AlbumGrouper.group(tracks) { displayArtist(it) }.also { albumsCache = it }
        }
    }

    /** Call after changing the separator, favorites or a manual split, so the screens update. */
    fun notifyArtistRulesChanged() {
        invalidateArtistCaches()
        notifyListenersChanged()
    }

    // ------------------------------------------------------------------ helpers

    private fun invalidateArtistCaches() {
        synchronized(cacheLock) {
            artistsCache = null
            albumsCache = null
            artistsOfCache.clear()
            displayArtistCache.clear()
        }
    }

    private fun notifyListenersChanged() {
        for (listener in ArrayList(listeners)) listener.onLibraryChanged()
    }

    private fun publish(next: List<Track>) {
        tracks = next
        uriIndex = null
        invalidateArtistCaches()
        notifyListenersChanged()
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
