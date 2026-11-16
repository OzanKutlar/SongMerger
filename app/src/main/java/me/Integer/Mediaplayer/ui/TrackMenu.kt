package me.Integer.Mediaplayer.ui

import android.view.View
import androidx.appcompat.widget.PopupMenu
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track

/** The three-dot menu of a song row. */
object TrackMenu {

    private const val ID_PLAY_NEXT = 1
    private const val ID_ADD_TO_QUEUE = 2
    private const val ID_SPLIT_ARTISTS = 3

    fun show(anchor: View, track: Track, host: PlayerHost, repository: LibraryRepository) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.menu.add(0, ID_PLAY_NEXT, 0, R.string.menu_play_next)
        popup.menu.add(0, ID_ADD_TO_QUEUE, 1, R.string.menu_add_queue)
        popup.menu.add(0, ID_SPLIT_ARTISTS, 2, R.string.menu_split_artists)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_PLAY_NEXT -> host.connection.playNext(track, repository.displayArtist(track))
                ID_ADD_TO_QUEUE -> host.connection.addToQueue(track, repository.displayArtist(track))
                ID_SPLIT_ARTISTS -> SplitArtistsDialog.show(anchor.context, repository, track)
            }
            true
        }
        popup.show()
    }
}
