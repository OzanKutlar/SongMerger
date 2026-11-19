package me.Integer.Mediaplayer.ui

import android.graphics.Bitmap
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.library.MixSegment
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.playback.NowPlaying
import me.Integer.Mediaplayer.playback.PlayerConnection
import kotlin.random.Random

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

    /**
     * Plays all [tracks] in order or shuffled, starting at [startIndex]. A negative index
     * starts at the first song, or at a random one when shuffling.
     */
    fun playAll(tracks: List<Track>, shuffle: Boolean, startIndex: Int = -1)

    /**
     * Plays a mashup with DJ transitions, starting at step [startIndex]. Steps whose song is
     * no longer in the library are left out.
     */
    fun playMix(segments: List<MixSegment>, startIndex: Int)

    /**
     * Runs [play] with the launch animation: [list] glides to [row] when it is not fully on
     * screen, the row's cover hops out and drops onto the mini player, and the mini player
     * grows into the full player. [play] runs as the cover hops, so music never waits.
     */
    fun launchFromList(list: RecyclerView, row: Int, play: () -> Unit)
}

/** Screen changes, handled by the activity. */
interface Navigator {
    fun openNowPlaying()

    fun openArtist(name: String)

    fun openAlbum(albumName: String)

    fun openPlaylist(playlistId: Long)

    fun openQueue()

    fun openSettings()

    fun openAiMashupMenu(sourcePlaylistId: Long = AiMashupMenuFragment.NO_PLAYLIST)

    fun goBack()
}

/** A tapped song row: plays the list from it, with the launch animation. */
fun PlayerHost.launchRow(list: RecyclerView, tracks: List<Track>, index: Int) {
    if (index !in tracks.indices) return
    launchFromList(list, index) { playTracks(tracks, index) }
}

/**
 * Play all or Shuffle: the first song is chosen up front (the top one, or a random one), so
 * the list can glide to it before it hops into the mini player.
 */
fun PlayerHost.launchAll(list: RecyclerView, tracks: List<Track>, shuffle: Boolean) {
    if (tracks.isEmpty()) return
    val start = if (shuffle) Random.nextInt(tracks.size) else 0
    launchFromList(list, start) { playAll(tracks, shuffle, start) }
}
