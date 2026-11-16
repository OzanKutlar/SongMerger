package me.Integer.Mediaplayer.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.ui.widgets.PressFeedback

/**
 * Shows one album and every song in the library belonging to that album.
 */
class AlbumFragment : Fragment(R.layout.fragment_artist), LibraryRepository.Listener, PlayerHost.Listener {

    private lateinit var repository: LibraryRepository
    private lateinit var host: PlayerHost
    private lateinit var adapter: TrackAdapter
    private lateinit var countView: TextView
    private var albumName = ""
    private var tracks: List<Track> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LibraryRepository.get(requireContext())
        host = requireActivity() as PlayerHost
        albumName = arguments?.getString(ARG_ALBUM).orEmpty()
        countView = view.findViewById(R.id.artistSongCount)
        view.findViewById<TextView>(R.id.artistTitle).text =
            albumName.ifEmpty { getString(R.string.album_unknown) }
        view.findViewById<View>(R.id.artistBack).setOnClickListener { (requireActivity() as Navigator).goBack() }
        wireButtons(view)
        adapter = TrackAdapter(
            thumbnails = ThumbnailLoader.get(requireContext()),
            artistLine = { track -> repository.displayArtist(track).ifEmpty { getString(R.string.library_unknown_artist) } },
            onClick = { index -> host.playTracks(tracks, index) },
            onPlayNext = { track -> TrackMenu.playNext(requireContext(), track, host, repository) },
            onMenu = { track, anchor -> TrackMenu.show(anchor, track, host, repository) }
        )
        val list = view.findViewById<RecyclerView>(R.id.artistList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
    }

    private fun wireButtons(view: View) {
        val shuffle = view.findViewById<View>(R.id.artistShuffle)
        val play = view.findViewById<View>(R.id.artistPlayAll)
        PressFeedback.attach(shuffle, null, ROUND_PRESS_SCALE)
        PressFeedback.attach(play, null, ROUND_PRESS_SCALE)
        shuffle.setOnClickListener { host.playAll(tracks, true) }
        play.setOnClickListener { host.playAll(tracks, false) }
    }

    override fun onStart() {
        super.onStart()
        repository.addListener(this)
        host.addPlayerListener(this)
        refresh()
        onPlayerChanged()
    }

    override fun onStop() {
        host.removePlayerListener(this)
        repository.removeListener(this)
        super.onStop()
    }

    override fun onLibraryChanged() {
        refresh()
    }

    override fun onScanState(state: LibraryRepository.ScanState) = Unit

    override fun onPlayerChanged() {
        adapter.setPlaying(host.nowPlaying.trackUri)
    }

    private fun refresh() {
        tracks = repository.tracksInAlbum(albumName)
        adapter.submit(tracks)
        countView.text = resources.getQuantityString(R.plurals.library_song_count, tracks.size, tracks.size)
    }

    companion object {
        private const val ARG_ALBUM = "album_name"
        private const val ROUND_PRESS_SCALE = 0.9f

        fun create(albumName: String): AlbumFragment {
            return AlbumFragment().apply {
                arguments = Bundle().apply { putString(ARG_ALBUM, albumName) }
            }
        }
    }
}
