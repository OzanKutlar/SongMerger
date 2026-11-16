package me.Integer.Mediaplayer

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import me.Integer.Mediaplayer.library.Artist
import me.Integer.Mediaplayer.library.LibraryRepository

/**
 * Temporary library check screen: add a folder, scan it and list the detected artists.
 * It exists so the library layer can be verified on a phone before the player UI lands.
 */
class MainActivity : AppCompatActivity(), LibraryRepository.Listener {

    private lateinit var repository: LibraryRepository
    private lateinit var statusView: TextView
    private lateinit var listView: TextView
    private lateinit var rescanButton: View

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> onFolderPicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repository = LibraryRepository.get(this)
        statusView = findViewById(R.id.libraryStatus)
        listView = findViewById(R.id.libraryList)
        rescanButton = findViewById(R.id.libraryRescan)
        findViewById<View>(R.id.libraryAddFolder).setOnClickListener { pickFolder.launch(null) }
        rescanButton.setOnClickListener { repository.rescan() }
    }

    override fun onStart() {
        super.onStart()
        repository.addListener(this)
        repository.loadIfNeeded()
        render()
    }

    override fun onStop() {
        repository.removeListener(this)
        super.onStop()
    }

    override fun onLibraryChanged() {
        render()
    }

    override fun onScanState(state: LibraryRepository.ScanState) {
        render()
    }

    private fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        if (!repository.folders.add(uri)) {
            Toast.makeText(this, R.string.library_folder_failed, Toast.LENGTH_LONG).show()
            return
        }
        repository.rescan()
    }

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
        const val MAX_LINES = 500
    }
}
