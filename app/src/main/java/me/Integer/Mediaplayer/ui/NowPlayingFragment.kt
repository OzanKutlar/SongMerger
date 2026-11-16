package me.Integer.Mediaplayer.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.ArtistSplitter
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track

/**
 * The full-screen player. Playback controls and animations live in [NowPlayingController];
 * the top bar's Queue and three-dot menu actions are handled here.
 */
class NowPlayingFragment : Fragment(R.layout.fragment_now_playing), PlayerHost.Listener, LibraryRepository.Listener {

    private lateinit var repository: LibraryRepository
    private lateinit var host: PlayerHost
    private lateinit var navigator: Navigator
    private var controller: NowPlayingController? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LibraryRepository.get(requireContext())
        host = requireActivity() as PlayerHost
        navigator = requireActivity() as Navigator
        controller = NowPlayingController(view, host, repository)
        view.findViewById<View>(R.id.playerClose).setOnClickListener { navigator.goBack() }
        view.findViewById<View>(R.id.playerQueue).setOnClickListener { navigator.openQueue() }
        view.findViewById<View>(R.id.playerMore).setOnClickListener { showMoreMenu(it) }
    }

    override fun onStart() {
        super.onStart()
        repository.addListener(this)
        host.addPlayerListener(this)
        controller?.start()
    }

    override fun onStop() {
        controller?.stop()
        host.removePlayerListener(this)
        repository.removeListener(this)
        super.onStop()
    }

    override fun onDestroyView() {
        controller = null
        super.onDestroyView()
    }

    override fun onPlayerChanged() {
        controller?.onPlayerChanged()
    }

    override fun onLibraryChanged() {
        controller?.onPlayerChanged()
    }

    override fun onScanState(state: LibraryRepository.ScanState) = Unit

    // ------------------------------------------------------------------ top-right menu

    private fun showMoreMenu(anchor: View) {
        val state = host.nowPlaying
        if (!state.active) return
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, ID_VIEW_ALBUM, 0, R.string.menu_view_album)
        popup.menu.add(0, ID_VIEW_ARTIST, 1, R.string.menu_view_artist)
        popup.menu.add(0, ID_CURRENT_QUEUE, 2, R.string.menu_current_queue)
        popup.menu.add(0, ID_ADD_PLAYLIST, 3, R.string.menu_add_playlist)
        popup.menu.add(0, ID_SPLIT_ARTISTS, 3, R.string.menu_split_artists)
        popup.menu.add(0, ID_TRACK_DETAILS, 4, R.string.menu_track_details)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_VIEW_ALBUM -> openAlbumForCurrent()
                ID_VIEW_ARTIST -> openArtistForCurrent()
                ID_CURRENT_QUEUE -> navigator.openQueue()
                ID_ADD_PLAYLIST -> currentTrack()?.let { PlaylistDialogs.addTrack(requireContext(), repository, it) }
                ID_SPLIT_ARTISTS -> currentTrack()?.let { SplitArtistsDialog.show(requireContext(), repository, it) }
                ID_TRACK_DETAILS -> currentTrack()?.let { TrackDetailsDialog.show(requireContext(), repository, it) }
            }
            true
        }
        popup.show()
    }

    private fun openAlbumForCurrent() {
        val track = currentTrack()
        val album = track?.album ?: host.nowPlaying.album
        if (album.isEmpty() && repository.tracksInAlbum("").isEmpty()) {
            Toast.makeText(requireContext(), R.string.album_unknown, Toast.LENGTH_SHORT).show()
            return
        }
        navigator.openAlbum(album)
    }

    private fun openArtistForCurrent() {
        val track = currentTrack()
        val artists = if (track != null) {
            repository.artistsOf(track)
        } else {
            ArtistSplitter.split(host.nowPlaying.artist, repository.settings.artistSeparator)
        }
        if (artists.size <= 1) {
            navigator.openArtist(artists.firstOrNull().orEmpty())
            return
        }
        val labels = artists.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.choose_artist_title)
            .setItems(labels) { _, which ->
                if (which in artists.indices) navigator.openArtist(artists[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun currentTrack(): Track? {
        val state = host.nowPlaying
        if (!state.active) return null
        return repository.findTrack(state.trackUri) ?: Track(
            uri = state.trackUri,
            title = state.title,
            artistTag = state.artist,
            album = state.album,
            durationMs = state.durationMs,
            folderUri = "",
            fileName = state.title,
            sizeBytes = 0L,
            modifiedMs = 0L
        )
    }

    private companion object {
        const val ID_VIEW_ALBUM = 1
        const val ID_VIEW_ARTIST = 2
        const val ID_CURRENT_QUEUE = 3
        const val ID_SPLIT_ARTISTS = 4
        const val ID_TRACK_DETAILS = 5
        const val ID_ADD_PLAYLIST = 6
    }
}
