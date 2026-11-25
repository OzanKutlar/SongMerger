package me.Integer.Mediaplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.library.CoverArtLoader
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.MixSegment
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.playback.NowPlaying
import me.Integer.Mediaplayer.playback.NowPlayingListener
import me.Integer.Mediaplayer.playback.PlayerConnection
import me.Integer.Mediaplayer.ui.AiMashupMenuFragment
import me.Integer.Mediaplayer.ui.AlbumFragment
import me.Integer.Mediaplayer.ui.ArtistFragment
import me.Integer.Mediaplayer.ui.LibraryFragment
import me.Integer.Mediaplayer.ui.MiniPlayerBar
import me.Integer.Mediaplayer.ui.Navigator
import me.Integer.Mediaplayer.ui.NowPlayingFragment
import me.Integer.Mediaplayer.ui.PlayerExpand
import me.Integer.Mediaplayer.ui.PlayerHost
import me.Integer.Mediaplayer.ui.PlaylistFragment
import me.Integer.Mediaplayer.ui.SettingsFragment
import me.Integer.Mediaplayer.ui.SongLaunch
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
    private lateinit var songLaunch: SongLaunch
    private lateinit var expander: PlayerExpand
    private val listeners = ArrayList<PlayerHost.Listener>()

    /** A notification tap asked for the player; it opens once the activity is resumed. */
    private var pendingOpenPlayer = false

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
            val hide = f is NowPlayingFragment || f is SettingsFragment || f is AiMashupMenuFragment
            miniBar.setSuppressed(hide)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repository = LibraryRepository.get(this)
        connection = PlayerConnection(this, this) { repository.settings.crossfadeMs }
        artLoader = CoverArtLoader(this, (ART_SIZE_DP * resources.displayMetrics.density).roundToInt())
        miniBar = MiniPlayerBar(findViewById(R.id.miniBar), this, this)
        val launchLayer = findViewById<FrameLayout>(R.id.launchLayer)
        val screens = findViewById<android.view.ViewGroup>(R.id.mainContainer)
        expander = PlayerExpand(launchLayer, screens, miniBar, this)
        songLaunch = SongLaunch(launchLayer, miniBar, this) { openNowPlaying() }
        supportFragmentManager.registerFragmentLifecycleCallbacks(screenWatcher, false)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.mainContainer, LibraryFragment())
                .commitNow()
        }
        window.decorView.post {
            repository.loadIfNeeded()
            expander.warmUp()
            songLaunch.warmUp()
        }
        if (savedInstanceState == null && wantsPlayer(intent)) pendingOpenPlayer = true
    }

    /** A notification tap while the app is already open brings the player to the front. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (wantsPlayer(intent)) pendingOpenPlayer = true
    }

    /** Screens can only change once the activity is resumed, so a pending request runs here. */
    override fun onResume() {
        super.onResume()
        if (!pendingOpenPlayer) return
        pendingOpenPlayer = false
        openNowPlaying()
    }

    /** True for a notification tap, but not when Android relaunches the app from Recents. */
    private fun wantsPlayer(intent: Intent?): Boolean {
        if (intent == null || intent.action != ACTION_OPEN_PLAYER) return false
        return (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
    }

    override fun onStart() {
        super.onStart()
        connection.connect()
        miniBar.render()
    }

    override fun onStop() {
        songLaunch.cancel(runPending = true)
        expander.cancel()
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

    override fun launchFromList(list: RecyclerView, row: Int, play: () -> Unit) {
        songLaunch.launch(list, row, play)
    }

    override fun playAll(tracks: List<Track>, shuffle: Boolean, startIndex: Int) {
        if (tracks.isEmpty()) return
        ensureNotificationPermission()
        val start = when {
            startIndex in tracks.indices -> startIndex
            shuffle -> Random.nextInt(tracks.size)
            else -> 0
        }
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

    /** Grows the mini player into the full player when it is on screen; slides up otherwise. */
    override fun openNowPlaying() {
        if (expander.expand { open(KEY_PLAYER, STYLE_REVEAL) { NowPlayingFragment() } }) return
        open(KEY_PLAYER, STYLE_SHEET) { NowPlayingFragment() }
    }

    override fun openArtist(name: String) {
        open(KEY_ARTIST + name.lowercase(), STYLE_SIDE) { ArtistFragment.create(name) }
    }

    override fun openAlbum(albumName: String) {
        open(KEY_ALBUM + albumName.lowercase(), STYLE_SIDE) { AlbumFragment.create(albumName) }
    }

    override fun openPlaylist(playlistId: Long) {
        open(KEY_PLAYLIST + playlistId, STYLE_SIDE) { PlaylistFragment.create(playlistId) }
    }

    override fun openQueue() {
        val current = supportFragmentManager.findFragmentById(R.id.mainContainer)
        if (current is NowPlayingFragment) {
            current.showInlineQueue()
            return
        }
        open(KEY_PLAYER, STYLE_SHEET) { NowPlayingFragment.create(showQueue = true) }
    }

    override fun openSettings() {
        open(KEY_SETTINGS, STYLE_SIDE) { SettingsFragment() }
    }

    override fun openAiMashupMenu(sourcePlaylistId: Long) {
        open(KEY_AI_MENU + sourcePlaylistId, STYLE_SIDE) {
            AiMashupMenuFragment.create(sourcePlaylistId)
        }
    }

    override fun goBack() {
        onBackPressedDispatcher.onBackPressed()
    }

    /**
     * Shows a screen once. If [key] is already on the back stack, the screens above it are
     * closed and that one comes back to the front, instead of a second copy being stacked.
     *
     * @param style [STYLE_SIDE] slides sideways, [STYLE_SHEET] rises from the bottom, and
     *     [STYLE_REVEAL] appears at once under the card growing out of the mini player.
     *     Leaving the player always slides it down.
     * @return false when screens cannot change right now
     */
    private fun open(key: String, style: Int, create: () -> Fragment): Boolean {
        val manager = supportFragmentManager
        if (manager.isStateSaved) return false
        if (isOnBackStack(key)) {
            manager.popBackStack(key, 0)
            return true
        }
        val transaction = manager.beginTransaction().setReorderingAllowed(true)
        when (style) {
            STYLE_SHEET -> transaction.setCustomAnimations(
                R.anim.sheet_enter, R.anim.screen_hold,
                R.anim.screen_hold, R.anim.sheet_exit
            )
            STYLE_REVEAL -> transaction.setCustomAnimations(
                0, R.anim.screen_hold,
                R.anim.screen_hold, R.anim.sheet_exit
            )
            else -> transaction.setCustomAnimations(
                R.anim.screen_enter, R.anim.screen_exit,
                R.anim.screen_pop_enter, R.anim.screen_pop_exit
            )
        }
        transaction.replace(R.id.mainContainer, create()).addToBackStack(key).commit()
        return true
    }

    private fun isOnBackStack(key: String): Boolean {
        val manager = supportFragmentManager
        for (i in 0 until manager.backStackEntryCount) {
            if (manager.getBackStackEntryAt(i).name == key) return true
        }
        return false
    }

    companion object {
        /** The playback notification opens the app with this action to show the full-screen player. */
        const val ACTION_OPEN_PLAYER = "me.Integer.Mediaplayer.OPEN_PLAYER"

        private const val TAG = "MainActivity"
        private const val ART_SIZE_DP = 360

        // Back stack names: one entry per screen, so a screen is never stacked twice.
        private const val KEY_PLAYER = "player"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_AI_MENU = "ai_menu:"
        private const val KEY_ARTIST = "artist:"
        private const val KEY_ALBUM = "album:"
        private const val KEY_PLAYLIST = "playlist:"

        // How a screen comes in.
        private const val STYLE_SIDE = 0
        private const val STYLE_SHEET = 1
        private const val STYLE_REVEAL = 2
    }
}
