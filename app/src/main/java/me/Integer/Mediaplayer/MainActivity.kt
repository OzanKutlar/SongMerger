package me.Integer.Mediaplayer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import me.Integer.Mediaplayer.library.Artist
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.playback.NowPlaying
import me.Integer.Mediaplayer.playback.NowPlayingListener
import me.Integer.Mediaplayer.playback.PlaybackClock
import me.Integer.Mediaplayer.playback.PlayerConnection

/**
 * Temporary check screen: add a folder, scan it, list the detected artists and start
 * playback. It exists so the library and playback layers can be verified on a phone
 * before the player UI lands.
 */
class MainActivity : AppCompatActivity(), LibraryRepository.Listener, NowPlayingListener {

    private lateinit var repository: LibraryRepository
    private lateinit var connection: PlayerConnection
    private lateinit var statusView: TextView
    private lateinit var listView: TextView
    private lateinit var nowView: TextView
    private lateinit var rescanButton: View
    private var nowPlaying = NowPlaying.IDLE

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> onFolderPicked(uri) }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Log.w(TAG, "notification permission denied, playback still works")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repository = LibraryRepository.get(this)
        connection = PlayerConnection(this, this)
        statusView = findViewById(R.id.libraryStatus)
        listView = findViewById(R.id.libraryList)
        nowView = findViewById(R.id.playerNow)
        rescanButton = findViewById(R.id.libraryRescan)
        wireButtons()
    }

    private fun wireButtons() {
        findViewById<View>(R.id.libraryAddFolder).setOnClickListener { pickFolder.launch(null) }
        rescanButton.setOnClickListener { repository.rescan() }
        findViewById<View>(R.id.playerPlayAll).setOnClickListener { playAll() }
        findViewById<View>(R.id.playerToggle).setOnClickListener { togglePlayback() }
        findViewById<View>(R.id.playerNext).setOnClickListener { connection.next() }
    }

    override fun onStart() {
        super.onStart()
        repository.addListener(this)
        repository.loadIfNeeded()
        connection.connect()
        render()
        renderPlayer()
    }

    override fun onStop() {
        connection.release()
        repository.removeListener(this)
        super.onStop()
    }

    override fun onLibraryChanged() {
        render()
    }

    override fun onScanState(state: LibraryRepository.ScanState) {
        render()
    }

    override fun onNowPlaying(state: NowPlaying) {
        nowPlaying = state
        renderPlayer()
    }

    // ------------------------------------------------------------------ actions

    private fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        if (!repository.folders.add(uri)) {
            Toast.makeText(this, R.string.library_folder_failed, Toast.LENGTH_LONG).show()
            return
        }
        repository.rescan()
    }

    private fun playAll() {
        ensureNotificationPermission()
        connection.playTracks(repository.tracks, 0) { repository.displayArtist(it) }
    }

    private fun togglePlayback() {
        if (nowPlaying.playing) connection.pause() else connection.play()
    }

    /** Android 13+ only shows the playback notification after this permission is granted. */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return
        askNotifications.launch(permission)
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        val artists = repository.artists()
        val scan = repository.scanState
        rescanButton.isEnabled = !scan.running
        statusView.text = statusText(scan, artists.size)
        listView.text = if (artists.isEmpty() && !scan.running) {
            getString(R.string.library_empty)
        } else {
            artistLines(artists)
        }
    }

    private fun renderPlayer() {
        val state = nowPlaying
        nowView.text = if (!state.active) {
            getString(R.string.player_idle)
        } else {
            val artist = state.artist.ifEmpty { getString(R.string.library_unknown_artist) }
            getString(R.string.player_line, state.title, artist, PlaybackClock.format(state.durationMs))
        }
    }

    private fun statusText(scan: LibraryRepository.ScanState, artistCount: Int): String {
        if (!scan.running) {
            return getString(
                R.string.library_status,
                repository.tracks.size, artistCount, repository.folders.folders().size
            )
        }
        if (scan.total <= 0) return getString(R.string.library_scanning_folders)
        return getString(R.string.library_scanning, scan.done, scan.total)
    }

    private fun artistLines(artists: List<Artist>): String {
        val unknown = getString(R.string.library_unknown_artist)
        return artists.take(MAX_LINES).joinToString("\n") { artist ->
            getString(R.string.library_artist_line, artist.name.ifEmpty { unknown }, artist.tracks.size)
        }
    }

    private companion object {
        const val TAG = "MainActivity"
        const val MAX_LINES = 500
    }
}
