package me.Integer.Mediaplayer.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track

/**
 * Multi-select for a song list. A long press (or a menu item) starts it, taps pick rows, and
 * a bar that takes the list header's place offers bulk actions. Back leaves selection first.
 * Main thread only.
 */
internal class TrackSelectionController(
    fragment: Fragment,
    root: View,
    private val header: View,
    private val adapter: TrackAdapter,
    private val selection: TrackSelection,
    private val host: PlayerHost,
    private val repository: LibraryRepository
) {

    private val context = root.context
    private val bar: View = root.findViewById(R.id.trackSelectionBar)
    private val countView: TextView = root.findViewById(R.id.trackSelectionCount)
    private val favoriteCell: View = root.findViewById(R.id.trackSelectionFavorite)
    private val favoriteIcon: ImageView = root.findViewById(R.id.trackSelectionFavoriteIcon)
    private val actions: List<View>
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exit()
        }
    }

    val isActive: Boolean
        get() = selection.isActive

    init {
        val playNext = root.findViewById<View>(R.id.trackSelectionPlayNext)
        val playlist = root.findViewById<View>(R.id.trackSelectionPlaylist)
        actions = listOf(playNext, playlist, favoriteCell)
        root.findViewById<View>(R.id.trackSelectionClose).setOnClickListener { exit() }
        root.findViewById<View>(R.id.trackSelectionMore).setOnClickListener { showMore(it) }
        playNext.setOnClickListener { playSelectedNext() }
        playlist.setOnClickListener { addSelectedToPlaylist() }
        favoriteCell.setOnClickListener { toggleSelectedFavorites() }
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner, backCallback)
        renderMode()
    }

    // ------------------------------------------------------------------ mode

    /** @return true when the tap picked or unpicked a row instead of playing it */
    fun handleTap(position: Int): Boolean {
        if (!selection.isActive) return false
        val track = adapter.rows().getOrNull(position) ?: return true
        selection.toggle(track.uri)
        adapter.refreshRow(position)
        renderCount()
        return true
    }

    /** Starts selecting, with the row at [position] picked when there is one. */
    fun enter(position: Int) {
        val wasActive = selection.isActive
        selection.start()
        val track = adapter.rows().getOrNull(position)
        if (track != null && !selection.contains(track.uri)) selection.toggle(track.uri)
        if (wasActive) adapter.refreshRow(position) else adapter.refreshSelection()
        renderMode()
    }

    fun exit() {
        if (!selection.isActive) return
        selection.stop()
        adapter.refreshSelection()
        renderMode()
    }

    /** The list changed (search, sort, library update): picks that left it are forgotten. */
    fun onRowsChanged() {
        if (!selection.isActive) return
        selection.retainOnly(adapter.rows().mapTo(HashSet()) { it.uri })
        renderCount()
    }

    private fun renderMode() {
        val active = selection.isActive
        header.visibility = if (active) View.GONE else View.VISIBLE
        bar.visibility = if (active) View.VISIBLE else View.GONE
        backCallback.isEnabled = active
        if (active) renderCount()
    }

    private fun renderCount() {
        val count = selection.size
        countView.text = context.resources.getQuantityString(R.plurals.queue_selected, count, count)
        val usable = count > 0
        for (action in actions) {
            action.isEnabled = usable
            action.alpha = if (usable) 1f else DISABLED_ALPHA
        }
        val allFavorite = usable && allFavorite(selectedTracks())
        favoriteIcon.setImageResource(if (allFavorite) R.drawable.player_ic_heart else R.drawable.player_ic_heart_outline)
        favoriteIcon.setColorFilter(if (allFavorite) COLOR_ON else COLOR_IDLE)
        favoriteCell.contentDescription =
            context.getString(if (allFavorite) R.string.menu_remove_favorite else R.string.menu_add_favorite)
    }

    // ------------------------------------------------------------------ actions

    private fun selectedTracks(): List<Track> {
        return selection.pickedIn(adapter.rows())
    }

    private fun allFavorite(tracks: List<Track>): Boolean {
        if (tracks.isEmpty()) return false
        val favorites = repository.favorites.all()
        return tracks.all { it.uri in favorites }
    }

    /** Inserted in reverse so the picks play in list order; an idle player just plays them. */
    private fun playSelectedNext() {
        val tracks = selectedTracks()
        if (tracks.isEmpty()) return
        if (!host.nowPlaying.active) {
            host.playTracks(tracks, 0)
        } else {
            for (track in tracks.asReversed()) host.connection.playNext(track, repository.displayArtist(track))
        }
        toast(context.resources.getQuantityString(R.plurals.selection_play_next, tracks.size, tracks.size))
        exit()
    }

    private fun addSelectedToQueue() {
        val tracks = selectedTracks()
        if (tracks.isEmpty()) return
        if (!host.nowPlaying.active) {
            host.playTracks(tracks, 0)
        } else {
            for (track in tracks) host.connection.addToQueue(track, repository.displayArtist(track))
        }
        toast(context.resources.getQuantityString(R.plurals.selection_added_queue, tracks.size, tracks.size))
        exit()
    }

    private fun addSelectedToPlaylist() {
        val tracks = selectedTracks()
        if (tracks.isEmpty()) return
        PlaylistDialogs.addTracks(context, repository, tracks)
        exit()
    }

    private fun toggleSelectedFavorites() {
        val tracks = selectedTracks()
        if (tracks.isEmpty()) return
        val uris = tracks.map { it.uri }
        val message = if (allFavorite(tracks)) {
            val removed = repository.removeFavorites(uris)
            context.resources.getQuantityString(R.plurals.queue_unfavorited, removed, removed)
        } else {
            val added = repository.addFavorites(uris)
            context.resources.getQuantityString(R.plurals.queue_favorited, added, added)
        }
        toast(message)
        renderCount()
    }

    private fun showMore(anchor: View) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, ID_SELECT_ALL, 0, R.string.queue_select_all)
        popup.menu.add(0, ID_ADD_QUEUE, 1, R.string.menu_add_queue).isEnabled = selection.size > 0
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_SELECT_ALL -> toggleAll()
                ID_ADD_QUEUE -> addSelectedToQueue()
            }
            true
        }
        popup.show()
    }

    /** Picks every row, or clears the picks when every row is already picked. */
    private fun toggleAll() {
        val rows = adapter.rows()
        if (rows.isNotEmpty() && selection.size >= rows.size) {
            selection.clear()
        } else {
            selection.pickAll(rows.map { it.uri })
        }
        adapter.refreshSelection()
        renderCount()
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val ID_SELECT_ALL = 1
        const val ID_ADD_QUEUE = 2
        const val DISABLED_ALPHA = 0.35f
        const val COLOR_ON = 0xFFFF6D00.toInt()
        const val COLOR_IDLE = 0xFFFDF8F5.toInt()
    }
}
