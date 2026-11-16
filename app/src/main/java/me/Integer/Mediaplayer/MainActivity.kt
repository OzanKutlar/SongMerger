package me.Integer.Mediaplayer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import me.Integer.Mediaplayer.library.CoverArtLoader
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.MixSegment
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.playback.NowPlaying
import me.Integer.Mediaplayer.playback.NowPlayingListener
import me.Integer.Mediaplayer.playback.PlayerConnection
import me.Integer.Mediaplayer.ui.AlbumFragment
import me.Integer.Mediaplayer.ui.ArtistFragment
import me.Integer.Mediaplayer.ui.LibraryFragment
import me.Integer.Mediaplayer.ui.MiniPlayerBar
import me.Integer.Mediaplayer.ui.Navigator
import me.Integer.Mediaplayer.ui.NowPlayingFragment
import me.Integer.Mediaplayer.ui.PlayerHost
import me.Integer.Mediaplayer.ui.PlaylistFragment
import me.Integer.Mediaplayer.ui.QueueFragment
import me.Integer.Mediaplayer.ui.SettingsFragment
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Hosts the screens and the floating mini player. It owns the one connection to the
 * playback service and the cover of the current song, and shares both through [PlayerHost].
 */
class MainActivity : AppCompatActivity(), PlayerHost, Navigator, NowPlayingListener {

    private lateinit var repository: LibraryRepository
    private lateinit var artLoader: CoverArtLoader
    private lateinit var miniBar: MiniPlayerBar
    private val listeners = ArrayList<PlayerHost.Listener>()

    override lateinit var connection: PlayerConnection
        private set
    override var nowPlaying: NowPlaying = NowPlaying.IDLE
        private set
    override var currentArt: Bitmap? = null
        private set
    override var isArtResolved: Boolean = true
        private set

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Log.w(TAG, "notification permission denied, playback still works")
        }

    /** The mini player hides on screens that have their own controls or need the space. */
    private val screenWatcher = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (f.id != R.id.mainContainer) return
            val hide = f is NowPlayingFragment || f is SettingsFragment || f is QueueFragment
            miniBar.setSuppressed(hide)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repository = LibraryRepository.get(this)
        connection = PlayerConnection(this, this)
        artLoader = CoverArtLoader(this, (ART_SIZE_DP * resources.displayMetrics.density).roundToInt())
        miniBar = MiniPlayerBar(findViewById(R.id.miniBar), this, this)
        supportFragmentManager.registerFragmentLifecycleCallbacks(screenWatcher, false)
        repository.loadIfNeeded()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.mainContainer, LibraryFragment())
                .commit()
        }
    }

    override fun onStart() {
        super.onStart()
        connection.connect()
        miniBar.render()
    }

    override fun onStop() {
        miniBar.stop()
        connection.release()
        super.onStop()
    }

    override fun onDestroy() {
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(screenWatcher)
        artLoader.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ player host

    override fun addPlayerListener(listener: PlayerHost.Listener) {
        if (listener !in listeners) listeners.add(listener)
    }

    override fun removePlayerListener(listener: PlayerHost.Listener) {
        listeners.remove(listener)
    }

    override fun playTracks(tracks: List<Track>, startIndex: Int) {
        ensureNotificationPermission()
        connection.playTracks(tracks, startIndex) { repository.displayArtist(it) }
    }

    override fun playAll(tracks: List<Track>, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        ensureNotificationPermission()
        val start = if (shuffle) Random.nextInt(tracks.size) else 0
        connection.playTracks(tracks, start, shuffle) { repository.displayArtist(it) }
    }

    override fun playMix(segments: List<MixSegment>, startIndex: Int) {
        val steps = ArrayList<Pair<Track, MixSegment>>(segments.size)
        var keptBefore = 0
        for ((index, segment) in segments.withIndex()) {
            val track = repository.findTrack(segment.trackUri) ?: continue
            if (index < startIndex) keptBefore++
            steps.add(track to segment)
        }
        if (steps.isEmpty()) return
        ensureNotificationPermission()
        val start = keptBefore.coerceAtMost(steps.size - 1)
        connection.playMix(steps, start) { repository.displayArtist(it) }
    }

    override fun onNowPlaying(state: NowPlaying) {
        val trackChanged = state.trackUri != nowPlaying.trackUri
        nowPlaying = state
        if (trackChanged) refreshArt(state.trackUri)
        notifyPlayerChanged()
    }

    private fun refreshArt(trackUri: String) {
        currentArt = null
        isArtResolved = trackUri.isEmpty()
        if (trackUri.isEmpty()) return
        artLoader.load(trackUri) { loadedUri, art -> onArtLoaded(loadedUri, art) }
    }

    private fun onArtLoaded(trackUri: String, art: Bitmap?) {
        if (trackUri != nowPlaying.trackUri) return
        val firstAnswer = !isArtResolved
        currentArt = art
        isArtResolved = true
        if (firstAnswer) notifyPlayerChanged()
    }

    private fun notifyPlayerChanged() {
        for (listener in ArrayList(listeners)) listener.onPlayerChanged()
        miniBar.render()
    }

    /** Android 13+ only shows the playback notification after this permission is granted. */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return
        askNotifications.launch(permission)
    }

    // ------------------------------------------------------------------ navigation

    override fun openNowPlaying() {
        open(KEY_PLAYER, true) { NowPlayingFragment() }
    }

    override fun openArtist(name: String) {
        open(KEY_ARTIST + name.lowercase(), false) { ArtistFragment.create(name) }
    }

    override fun openAlbum(albumName: String) {
        open(KEY_ALBUM + albumName.lowercase(), false) { AlbumFragment.create(albumName) }
    }

    override fun openPlaylist(playlistId: Long) {
        open(KEY_PLAYLIST + playlistId, false) { PlaylistFragment.create(playlistId) }
    }

    override fun openQueue() {
        open(KEY_QUEUE, false) { QueueFragment() }
    }

    override fun openSettings() {
        open(KEY_SETTINGS, false) { SettingsFragment() }
    }

    override fun goBack() {
        onBackPressedDispatcher.onBackPressed()
    }

    /**
     * Shows a screen once. If [key] is already on the back stack, the screens above it are
     * closed and that one comes back to the front, instead of a second copy being stacked.
     *
     * @param slideUp true for the player, which rises from the bottom; other screens slide sideways
     */
    private fun open(key: String, slideUp: Boolean, create: () -> Fragment) {
        val manager = supportFragmentManager
        if (manager.isStateSaved) return
        if (isOnBackStack(key)) {
            manager.popBackStack(key, 0)
            return
        }
        val transaction = manager.beginTransaction().setReorderingAllowed(true)
        if (slideUp) {
            transaction.setCustomAnimations(
                R.anim.sheet_enter, R.anim.screen_hold,
                R.anim.screen_hold, R.anim.sheet_exit
            )
        } else {
            transaction.setCustomAnimations(
                R.anim.screen_enter, R.anim.screen_exit,
                R.anim.screen_pop_enter, R.anim.screen_pop_exit
            )
        }
        transaction.replace(R.id.mainContainer, create()).addToBackStack(key).commit()
    }

    private fun isOnBackStack(key: String): Boolean {
        val manager = supportFragmentManager
        for (i in 0 until manager.backStackEntryCount) {
            if (manager.getBackStackEntryAt(i).name == key) return true
        }
        return false
    }

    private companion object {
        const val TAG = "MainActivity"
        const val ART_SIZE_DP = 360

        // Back stack names: one entry per screen, so a screen is never stacked twice.
        const val KEY_PLAYER = "player"
        const val KEY_QUEUE = "queue"
        const val KEY_SETTINGS = "settings"
        const val KEY_ARTIST = "artist:"
        const val KEY_ALBUM = "album:"
        const val KEY_PLAYLIST = "playlist:"
    }
}
