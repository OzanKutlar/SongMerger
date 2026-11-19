package me.Integer.Mediaplayer.ui

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track

/** The three-dot menu of a song row. */
object TrackMenu {

    private const val ID_PLAY_NEXT = 1
    private const val ID_ADD_TO_QUEUE = 2
    private const val ID_FAVORITE = 3
    private const val ID_SPLIT_ARTISTS = 4
    private const val ID_DETAILS = 5
    private const val ID_PLAYLIST = 6
    private const val ID_GO_ARTIST = 7
    private const val ID_GO_ALBUM = 8

    fun show(anchor: View, track: Track, host: PlayerHost, repository: LibraryRepository) {
        val context = anchor.context
        val navigator = host as? Navigator
        val isFav = repository.isFavorite(track.uri)
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, ID_PLAY_NEXT, 0, R.string.menu_play_next)
        popup.menu.add(0, ID_ADD_TO_QUEUE, 1, R.string.menu_add_queue)
        popup.menu.add(0, ID_FAVORITE, 2, if (isFav) R.string.menu_remove_favorite else R.string.menu_add_favorite)
        popup.menu.add(0, ID_PLAYLIST, 3, R.string.menu_add_playlist)
        if (navigator != null) {
            popup.menu.add(0, ID_GO_ARTIST, 4, R.string.menu_go_artist)
            if (track.album.isNotBlank()) popup.menu.add(0, ID_GO_ALBUM, 5, R.string.menu_go_album)
        }
        popup.menu.add(0, ID_SPLIT_ARTISTS, 6, R.string.menu_split_artists)
        popup.menu.add(0, ID_DETAILS, 7, R.string.menu_track_details)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_PLAY_NEXT -> playNext(context, track, host, repository)
                ID_ADD_TO_QUEUE -> addToQueue(context, track, host, repository)
                ID_FAVORITE -> repository.toggleFavorite(track.uri)
                ID_PLAYLIST -> PlaylistDialogs.addTrack(context, repository, track)
                ID_GO_ARTIST -> navigator?.let { openArtist(context, repository.artistsOf(track), it) }
                ID_GO_ALBUM -> navigator?.openAlbum(track.album)
                ID_SPLIT_ARTISTS -> SplitArtistsDialog.show(context, repository, track)
                ID_DETAILS -> TrackDetailsDialog.show(context, repository, track)
            }
            true
        }
        popup.show()
    }

    fun playNext(context: Context, track: Track, host: PlayerHost, repository: LibraryRepository) {
        host.connection.playNext(track, repository.displayArtist(track))
        Toast.makeText(
            context,
            context.getString(R.string.toast_play_next, track.title),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Opens the artist of a song; a song with several artists asks which one first. */
    fun openArtist(context: Context, artists: List<String>, navigator: Navigator) {
        if (artists.size <= 1) {
            navigator.openArtist(artists.firstOrNull().orEmpty())
            return
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.choose_artist_title)
            .setItems(artists.toTypedArray()) { _, which ->
                if (which in artists.indices) navigator.openArtist(artists[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addToQueue(context: Context, track: Track, host: PlayerHost, repository: LibraryRepository) {
        host.connection.addToQueue(track, repository.displayArtist(track))
        Toast.makeText(
            context,
            context.getString(R.string.toast_added_to_queue, track.title),
            Toast.LENGTH_SHORT
        ).show()
    }
}
