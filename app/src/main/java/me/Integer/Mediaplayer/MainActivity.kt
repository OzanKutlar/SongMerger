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
import me.Integer.Mediaplayer.library.CoverArtLoader
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.playback.NowPlaying
import me.Integer.Mediaplayer.playback.NowPlayingListener
import me.Integer.Mediaplayer.playback.PlayerConnection
import me.Integer.Mediaplayer.ui.ArtistFragment
import me.Integer.Mediaplayer.ui.LibraryFragment
import me.Integer.Mediaplayer.ui.Navigator
import me.Integer.Mediaplayer.ui.NowPlayingFragment
import me.Integer.Mediaplayer.ui.PlayerHost
import me.Integer.Mediaplayer.ui.SettingsFragment
import kotlin.math.roundToInt

/**
 * Hosts the screens. It owns the one connection to the playback service and the cover of
 * the current song, and shares both with the fragments through [PlayerHost].
 */
class MainActivity : AppCompatActivity(), PlayerHost, Navigator, NowPlayingListener {

    private lateinit var repository: LibraryRepository
    private lateinit var artLoader: CoverArtLoader
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repository = LibraryRepository.get(this)
        connection = PlayerConnection(this, this)
        artLoader = CoverArtLoader(this, (ART_SIZE_DP * resources.displayMetrics.density).roundToInt())
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
    }

    override fun onStop() {
        connection.release()
        super.onStop()
    }

    override fun onDestroy() {
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
        if (trackUri != nowPlaying.trackUri) return // an older request finished late
        val firstAnswer = !isArtResolved
        currentArt = art
        isArtResolved = true
        if (firstAnswer) notifyPlayerChanged()
    }

    private fun notifyPlayerChanged() {
        for (listener in ArrayList(listeners)) listener.onPlayerChanged()
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
        if (supportFragmentManager.findFragmentById(R.id.mainContainer) is NowPlayingFragment) return
        open(NowPlayingFragment())
    }

    override fun openArtist(name: String) {
        open(ArtistFragment.create(name))
    }

    override fun openSettings() {
        open(SettingsFragment())
    }

    override fun goBack() {
        onBackPressedDispatcher.onBackPressed()
    }

    private fun open(fragment: Fragment) {
        if (supportFragmentManager.isStateSaved) return
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in, android.R.anim.fade_out,
                android.R.anim.fade_in, android.R.anim.fade_out
            )
            .replace(R.id.mainContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val ART_SIZE_DP = 360
    }
}
