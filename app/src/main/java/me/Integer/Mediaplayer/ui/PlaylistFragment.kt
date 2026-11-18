package me.Integer.Mediaplayer.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.MixSegment
import me.Integer.Mediaplayer.library.Playlist
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.ui.widgets.PressFeedback
import java.io.File

/**
 * One playlist: its cover, name and songs. Songs are reordered by holding and dragging a
 * row; the cover is changed by tapping it.
 */
class PlaylistFragment : Fragment(R.layout.fragment_playlist), LibraryRepository.Listener, PlayerHost.Listener {

    private lateinit var repository: LibraryRepository
    private lateinit var host: PlayerHost
    private lateinit var navigator: Navigator
    private lateinit var covers: PlaylistCoverLoader
    private lateinit var adapter: TrackAdapter
    private lateinit var list: RecyclerView
    private lateinit var coverView: ImageView
    private lateinit var nameView: TextView
    private lateinit var countView: TextView
    private lateinit var emptyView: TextView
    private lateinit var shuffleButton: View
    private lateinit var playButton: View

    private var tracks: List<Track> = emptyList()
    private var dragging = false
    private var dragMoved = false

    /** The steps of a mashup playlist; empty for a plain one. */
    private var mix: List<MixSegment> = emptyList()

    /** Per song, the parts of it the mashup plays, such as 0:33 to 2:45. */
    private var cueText: Map<String, String> = emptyMap()

    private val playlistId: Long
        get() = arguments?.getLong(ARG_ID) ?: 0L

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> onImagePicked(uri) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LibraryRepository.get(requireContext())
        host = requireActivity() as PlayerHost
        navigator = requireActivity() as Navigator
        covers = PlaylistCoverLoader.get(requireContext())
        bindViews(view)
        wireHeader(view)
        createList()
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

    // ------------------------------------------------------------------ setup

    private fun bindViews(view: View) {
        list = view.findViewById(R.id.playlistList)
        coverView = view.findViewById(R.id.playlistCover)
        nameView = view.findViewById(R.id.playlistName)
        countView = view.findViewById(R.id.playlistCount)
        emptyView = view.findViewById(R.id.playlistEmpty)
        shuffleButton = view.findViewById(R.id.playlistShuffle)
        playButton = view.findViewById(R.id.playlistPlayAll)
    }

    private fun wireHeader(view: View) {
        val coverFrame = view.findViewById<View>(R.id.playlistCoverFrame)
        coverFrame.clipToOutline = true
        PressFeedback.attach(coverFrame, null, COVER_PRESS_SCALE)
        PressFeedback.attach(shuffleButton, null, ROUND_PRESS_SCALE)
        PressFeedback.attach(playButton, null, ROUND_PRESS_SCALE)
        coverFrame.setOnClickListener { changeCover() }
        view.findViewById<View>(R.id.playlistBack).setOnClickListener { navigator.goBack() }
        view.findViewById<View>(R.id.playlistMore).setOnClickListener { showMoreMenu(it) }
        shuffleButton.setOnClickListener { host.launchAll(list, tracks, true) }
        playButton.setOnClickListener {
            if (mix.isEmpty()) {
                host.launchAll(list, tracks, false)
            } else {
                val firstRow = tracks.indexOfFirst { it.uri == mix.first().trackUri }.coerceAtLeast(0)
                host.launchFromList(list, firstRow) { host.playMix(mix, 0) }
            }
        }
    }

    private fun createList() {
        adapter = TrackAdapter(
            thumbnails = ThumbnailLoader.get(requireContext()),
            artistLine = { track -> artistLineOf(track) },
            onClick = { index -> playRow(index) },
            onPlayNext = { track -> TrackMenu.playNext(requireContext(), track, host, repository) },
            onMenu = { track, anchor -> showTrackMenu(track, anchor) }
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        createDragHelper().attachToRecyclerView(list)
    }

    // ------------------------------------------------------------------ rendering

    private fun refresh() {
        if (dragging) return
        val playlist = repository.playlists.get(playlistId)
        tracks = if (playlist == null) emptyList() else repository.resolveTracks(playlist.trackUris)
        mix = playlist?.segments.orEmpty()
        cueText = cuesOf(mix)
        adapter.submit(tracks)
        nameView.text = playlist?.name.orEmpty()
        val songCount = resources.getQuantityString(R.plurals.library_song_count, tracks.size, tracks.size)
        countView.text = if (mix.isEmpty()) songCount else getString(R.string.playlist_mix_count, songCount, mix.size)
        emptyView.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        covers.bind(coverView, playlist)
        val usable = tracks.isNotEmpty()
        shuffleButton.isEnabled = usable
        playButton.isEnabled = usable
        shuffleButton.alpha = if (usable) 1f else DISABLED_ALPHA
        playButton.alpha = if (usable) 1f else DISABLED_ALPHA
    }

    /** A plain playlist plays from the tapped song; a mashup from that song's first step. */
    private fun playRow(index: Int) {
        val track = tracks.getOrNull(index) ?: return
        if (mix.isEmpty()) {
            host.launchRow(list, tracks, index)
            return
        }
        val step = mix.indexOfFirst { it.trackUri == track.uri }.coerceAtLeast(0)
        host.launchFromList(list, index) { host.playMix(mix, step) }
    }

    private fun artistLineOf(track: Track): String {
        val artist = repository.displayArtist(track).ifEmpty { getString(R.string.library_unknown_artist) }
        val cues = cueText[track.uri] ?: return artist
        return getString(R.string.playlist_mix_cues, artist, cues)
    }

    private fun cuesOf(steps: List<MixSegment>): Map<String, String> {
        if (steps.isEmpty()) return emptyMap()
        val parts = LinkedHashMap<String, MutableList<String>>()
        for (step in steps.take(MAX_CUE_STEPS)) {
            val endMs = if (step.playsToEnd) repository.findTrack(step.trackUri)?.durationMs ?: 0L else step.endMs
            val end = if (endMs > 0L) MixSegment.formatTime(endMs) else getString(R.string.playlist_mix_end)
            val cue = "${MixSegment.formatTime(step.startMs)} \u2192 $end"
            parts.getOrPut(step.trackUri) { ArrayList() }.add(cue)
        }
        return parts.mapValues { it.value.joinToString(", ") }
    }

    // ------------------------------------------------------------------ menus

    private fun showMoreMenu(anchor: View) {
        val playlist = repository.playlists.get(playlistId) ?: return
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, ID_RENAME, 0, R.string.playlist_rename)
        popup.menu.add(0, ID_COVER, 1, R.string.playlist_change_cover)
        popup.menu.add(0, ID_DELETE, 2, R.string.playlist_delete)
        popup.menu.add(0, ID_MASHUP, 3, R.string.mashup_from_playlist)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_RENAME -> PlaylistDialogs.rename(requireContext(), repository, playlist)
                ID_COVER -> changeCover()
                ID_DELETE -> PlaylistDialogs.confirmDelete(requireContext(), repository, playlist) { navigator.goBack() }
                ID_MASHUP -> AiMashupDialog.startFrom(requireContext(), repository, host, tracks)
            }
            true
        }
        popup.show()
    }

    private fun showTrackMenu(track: Track, anchor: View) {
        val context = requireContext()
        val id = playlistId
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, ID_PLAY_NEXT, 0, R.string.menu_play_next)
        popup.menu.add(0, ID_ADD_QUEUE, 1, R.string.menu_add_queue)
        popup.menu.add(0, ID_USE_COVER, 2, R.string.playlist_use_cover)
        popup.menu.add(0, ID_REMOVE, 3, R.string.playlist_remove_track)
        popup.menu.add(0, ID_DETAILS, 4, R.string.menu_track_details)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_PLAY_NEXT -> TrackMenu.playNext(context, track, host, repository)
                ID_ADD_QUEUE -> host.connection.addToQueue(track, repository.displayArtist(track))
                ID_USE_COVER -> repository.playlists.setCover(id, Playlist.COVER_TRACK, track.uri)
                ID_REMOVE -> repository.playlists.removeTrack(id, track.uri)
                ID_DETAILS -> TrackDetailsDialog.show(context, repository, track)
            }
            true
        }
        popup.show()
    }

    // ------------------------------------------------------------------ cover

    private fun changeCover() {
        val playlist = repository.playlists.get(playlistId) ?: return
        PlaylistDialogs.chooseCover(requireContext(), repository, playlist) { pickImage.launch(IMAGE_TYPE) }
    }

    /** The copy runs in the background and may finish after this screen is gone. */
    private fun onImagePicked(uri: Uri?) {
        if (uri == null) return
        val appContext = requireContext().applicationContext
        val store = LibraryRepository.get(appContext).playlists
        val id = playlistId
        PlaylistCoverLoader.get(appContext).importImage(uri, id) { path ->
            if (path == null) {
                Toast.makeText(appContext, R.string.playlist_cover_failed, Toast.LENGTH_LONG).show()
            } else if (!store.setCover(id, Playlist.COVER_IMAGE, path)) {
                File(path).delete() // the playlist was deleted meanwhile
            }
        }
    }

    // ------------------------------------------------------------------ reordering

    private fun createDragHelper(): ItemTouchHelper {
        return ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    dragging = true
                    viewHolder.itemView.alpha = DRAG_ALPHA
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.moveLocal(from, to)
                dragMoved = true
                return true
            }

            /** A mashup keeps the order its transitions were chosen for. */
            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (mix.isEmpty()) super.getDragDirs(recyclerView, viewHolder) else 0
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                dragging = false
                if (!dragMoved) return
                dragMoved = false
                val order = adapter.rows().map { it.uri }
                val id = playlistId
                // Stored after this callback, so the list is not changed while it settles.
                recyclerView.post { repository.playlists.reorder(id, order) }
            }
        })
    }

    companion object {
        private const val ARG_ID = "playlist_id"
        private const val IMAGE_TYPE = "image/*"
        private const val ROUND_PRESS_SCALE = 0.9f
        private const val COVER_PRESS_SCALE = 0.96f
        private const val DISABLED_ALPHA = 0.35f
        private const val DRAG_ALPHA = 0.75f
        private const val ID_RENAME = 1
        private const val ID_COVER = 2
        private const val ID_DELETE = 3
        private const val ID_PLAY_NEXT = 4
        private const val ID_ADD_QUEUE = 5
        private const val ID_USE_COVER = 6
        private const val ID_REMOVE = 7
        private const val ID_DETAILS = 8
        private const val ID_MASHUP = 9
        private const val MAX_CUE_STEPS = 500

        fun create(playlistId: Long): PlaylistFragment {
            return PlaylistFragment().apply {
                arguments = Bundle().apply { putLong(ARG_ID, playlistId) }
            }
        }
    }
}
