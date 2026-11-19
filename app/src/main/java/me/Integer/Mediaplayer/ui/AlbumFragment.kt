package me.Integer.Mediaplayer.ui

import android.os.Bundle
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.Track

/**
 * Shows one album and every song in the library belonging to that album.
 */
class AlbumFragment : TrackCollectionFragment() {

    private val albumName: String
        get() = arguments?.getString(ARG_ALBUM).orEmpty()

    override fun resolveTitle(): String {
        return albumName.ifEmpty { getString(R.string.album_unknown) }
    }

    override fun loadTracks(): List<Track> {
        return repository.tracksInAlbum(albumName)
    }

    companion object {
        private const val ARG_ALBUM = "album_name"

        fun create(albumName: String): AlbumFragment {
            return AlbumFragment().apply {
                arguments = Bundle().apply { putString(ARG_ALBUM, albumName) }
            }
        }
    }
}
