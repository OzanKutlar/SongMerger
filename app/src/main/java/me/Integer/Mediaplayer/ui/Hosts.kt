package me.Integer.Mediaplayer.ui

import android.graphics.Bitmap
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.playback.NowPlaying
import me.Integer.Mediaplayer.playback.PlayerConnection

/** What the activity shares with its screens about playback. Main thread only. */
interface PlayerHost {

    interface Listener {
        /** The player state or the current cover changed; read them from the host. */
        fun onPlayerChanged()
    }

    val connection: PlayerConnection
    val nowPlaying: NowPlaying

    /** Cover of the current song; null while it loads or when the song has none. */
    val currentArt: Bitmap?

    /** True once the cover lookup for the current song has finished. */
    val isArtResolved: Boolean

    fun addPlayerListener(listener: Listener)

    fun removePlayerListener(listener: Listener)

    /** Replaces the queue with [tracks] and starts at [startIndex], keeping the shuffle mode. */
    fun playTracks(tracks: List<Track>, startIndex: Int)

    /** Plays all [tracks]: in order from the first, or shuffled from a random one. */
    fun playAll(tracks: List<Track>, shuffle: Boolean)
}

/** Screen changes, handled by the activity. */
interface Navigator {
    fun openNowPlaying()

    fun openArtist(name: String)

    fun openAlbum(albumName: String)

    fun openQueue()

    fun openSettings()

    fun goBack()
}
