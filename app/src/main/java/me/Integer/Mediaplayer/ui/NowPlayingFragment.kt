package me.Integer.Mediaplayer.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.ArtistSplitter
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track

/**
 * The full-screen player. Playback controls and animations live in [NowPlayingController];
 * the inline queue view that replaces the vinyl disc is managed by [NowPlayingQueueController].
 */
class NowPlayingFragment : Fragment(R.layout.fragment_now_playing), PlayerHost.Listener, LibraryRepository.Listener {

    private lateinit var repository: LibraryRepository
    private lateinit var host: PlayerHost
    private lateinit var navigator: Navigator
    private var controller: NowPlayingController? = null
    private var queueController: NowPlayingQueueController? = null
    private var lyricsController: NowPlayingLyricsController? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (lyricsController?.isLyricsVisible == true) {
                lyricsController?.setLyricsVisible(false, animate = true)
                return
            }
            if (queueController?.handleBackPressed() != true) {
                isEnabled = false
                navigator.goBack()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LibraryRepository.get(requireContext())
        host = requireActivity() as PlayerHost
        navigator = requireActivity() as Navigator
        controller = NowPlayingController(view, host, repository)
        queueController = NowPlayingQueueController(view, host, repository) {
            if (queueController?.isQueueVisible == true) {
                lyricsController?.setLyricsVisible(false, animate = false)
            }
            syncBackCallback()
        }
        lyricsController = NowPlayingLyricsController(view, host, repository) {
            syncBackCallback()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        val initialShowQueue = savedInstanceState?.getBoolean(KEY_SHOW_QUEUE)
            ?: arguments?.getBoolean(ARG_SHOW_QUEUE, false)
            ?: false
        val initialShowLyrics = savedInstanceState?.getBoolean(KEY_SHOW_LYRICS) ?: false
        if (initialShowQueue) {
            queueController?.setQueueVisible(true, animate = false)
        } else if (initialShowLyrics) {
            lyricsController?.setLyricsVisible(true, animate = false)
        }

        view.findViewById<View>(R.id.playerClose).setOnClickListener { navigator.goBack() }
        view.findViewById<View>(R.id.playerQueue).setOnClickListener {
            lyricsController?.setLyricsVisible(false, animate = false)
            queueController?.toggleQueue()
        }
        view.findViewById<View>(R.id.playerMore).setOnClickListener { showMoreMenu(it) }
        view.findViewById<View>(R.id.playerAdd).setOnClickListener {
            currentTrack()?.let { PlaylistDialogs.addTrack(requireContext(), repository, it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHOW_QUEUE, queueController?.isQueueVisible == true)
        outState.putBoolean(KEY_SHOW_LYRICS, lyricsController?.isLyricsVisible == true)
    }

    override fun onStart() {
        super.onStart()
        repository.addListener(this)
        host.addPlayerListener(this)
        controller?.start()
        queueController?.render()
    }

    override fun onStop() {
        controller?.stop()
        queueController?.stop()
        lyricsController?.stop()
        host.removePlayerListener(this)
        repository.removeListener(this)
        super.onStop()
    }

    override fun onDestroyView() {
        lyricsController?.destroy()
        lyricsController = null
        controller = null
        queueController = null
        super.onDestroyView()
    }

    override fun onPlayerChanged() {
        controller?.onPlayerChanged()
        queueController?.onPlayerChanged()
        lyricsController?.onPlayerChanged()
    }

    override fun onLibraryChanged() {
        controller?.onPlayerChanged()
        queueController?.onPlayerChanged()
    }

    override fun onScanState(state: LibraryRepository.ScanState) = Unit

    fun showInlineQueue() {
        lyricsController?.setLyricsVisible(false, animate = false)
        queueController?.setQueueVisible(true, animate = true)
    }

    private fun syncBackCallback() {
        val qc = queueController
        val lc = lyricsController
        backCallback.isEnabled = (qc != null && (qc.isQueueVisible || qc.isSelectionActive)) ||
            (lc != null && lc.isLyricsVisible)
    }

    // ------------------------------------------------------------------ top-right menu

    private fun showMoreMenu(anchor: View) {
        val state = host.nowPlaying
        if (!state.active) return
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, ID_VIEW_LYRICS, 0, R.string.menu_view_lyrics)
        popup.menu.add(0, ID_VIEW_ALBUM, 1, R.string.menu_view_album)
        popup.menu.add(0, ID_VIEW_ARTIST, 2, R.string.menu_view_artist)
        popup.menu.add(0, ID_CURRENT_QUEUE, 3, R.string.menu_current_queue)
        popup.menu.add(0, ID_SLEEP_TIMER, 4, R.string.menu_sleep_timer)
        popup.menu.add(0, ID_SPLIT_ARTISTS, 5, R.string.menu_split_artists)
        popup.menu.add(0, ID_TRACK_DETAILS, 6, R.string.menu_track_details)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_VIEW_LYRICS -> lyricsController?.toggleLyrics { queueController?.setQueueVisible(false, animate = false) }
                ID_VIEW_ALBUM -> openAlbumForCurrent()
                ID_VIEW_ARTIST -> openArtistForCurrent()
                ID_CURRENT_QUEUE -> {
                    lyricsController?.setLyricsVisible(false, animate = false)
                    queueController?.toggleQueue()
                }
                ID_SLEEP_TIMER -> SleepTimerDialog.show(requireContext(), host.connection)
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

    companion object {
        private const val ARG_SHOW_QUEUE = "show_queue"
        private const val KEY_SHOW_QUEUE = "state_show_queue"
        private const val KEY_SHOW_LYRICS = "state_show_lyrics"
        private const val ID_VIEW_ALBUM = 1
        private const val ID_VIEW_ARTIST = 2
        private const val ID_CURRENT_QUEUE = 3
        private const val ID_SPLIT_ARTISTS = 4
        private const val ID_TRACK_DETAILS = 5
        private const val ID_VIEW_LYRICS = 6
        private const val ID_SLEEP_TIMER = 7

        fun create(showQueue: Boolean = false): NowPlayingFragment {
            return NowPlayingFragment().apply {
                if (showQueue) arguments = bundleOf(ARG_SHOW_QUEUE to true)
            }
        }
    }
}
