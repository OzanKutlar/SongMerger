package me.Integer.Mediaplayer.ui

import android.view.View
import android.widget.Toast
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

    fun show(anchor: View, track: Track, host: PlayerHost, repository: LibraryRepository) {
        val context = anchor.context
        val isFav = repository.isFavorite(track.uri)
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, ID_PLAY_NEXT, 0, R.string.menu_play_next)
        popup.menu.add(0, ID_ADD_TO_QUEUE, 1, R.string.menu_add_queue)
        popup.menu.add(
            0,
            ID_FAVORITE,
            2,
            if (isFav) R.string.menu_remove_favorite else R.string.menu_add_favorite
        )
        popup.menu.add(0, ID_PLAYLIST, 2, R.string.menu_add_playlist)
        popup.menu.add(0, ID_SPLIT_ARTISTS, 3, R.string.menu_split_artists)
        popup.menu.add(0, ID_DETAILS, 4, R.string.menu_track_details)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_PLAY_NEXT -> playNext(context, track, host, repository)
                ID_ADD_TO_QUEUE -> {
                    host.connection.addToQueue(track, repository.displayArtist(track))
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_added_to_queue, track.title),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                ID_FAVORITE -> repository.toggleFavorite(track.uri)
                ID_PLAYLIST -> PlaylistDialogs.addTrack(context, repository, track)
                ID_SPLIT_ARTISTS -> SplitArtistsDialog.show(context, repository, track)
                ID_DETAILS -> TrackDetailsDialog.show(context, repository, track)
            }
            true
        }
        popup.show()
    }

    fun playNext(context: android.content.Context, track: Track, host: PlayerHost, repository: LibraryRepository) {
        host.connection.playNext(track, repository.displayArtist(track))
        Toast.makeText(
            context,
            context.getString(R.string.toast_play_next, track.title),
            Toast.LENGTH_SHORT
        ).show()
    }
}
