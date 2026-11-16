package me.Integer.Mediaplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import me.Integer.Mediaplayer.library.Track
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/**
 * The screens' remote control for [PlaybackService]: transport commands in, [NowPlaying]
 * snapshots out. Connecting starts the service if needed; releasing does not stop the music.
 * Main thread only. Commands sent before the connection is ready are ignored.
 */
class PlayerConnection(context: Context, private val listener: NowPlayingListener) {

    private val appContext = context.applicationContext
    private var pending: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
        }
    }

    val isConnected: Boolean
        get() = controller != null

    // ------------------------------------------------------------------ lifecycle

    fun connect() {
        if (pending != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        pending = future
        future.addListener({ onConnected(future) }, ContextCompat.getMainExecutor(appContext))
    }

    fun release() {
        controller?.removeListener(playerListener)
        controller = null
        pending?.let { MediaController.releaseFuture(it) }
        pending = null
    }

    private fun onConnected(future: ListenableFuture<MediaController>) {
        if (pending !== future) return // released while connecting
        try {
            val connected = future.get()
            connected.addListener(playerListener)
            controller = connected
        } catch (e: ExecutionException) {
            connectionFailed(e)
            return
        } catch (e: CancellationException) {
            connectionFailed(e)
            return
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            connectionFailed(e)
            return
        }
        publish()
    }

    private fun connectionFailed(e: Exception) {
        Log.w(TAG, "cannot connect to the playback service: ${e.message}")
        pending = null
    }

    // ------------------------------------------------------------------ queue

    /** Replaces the queue with [tracks] and starts playing at [startIndex]. */
    fun playTracks(tracks: List<Track>, startIndex: Int, artistOf: (Track) -> String) {
        val player = controller ?: return
        if (tracks.isEmpty()) return
        val queue = tracks.take(MAX_QUEUE)
        val index = startIndex.coerceIn(0, queue.size - 1)
        player.setMediaItems(queue.map { toItem(it, artistOf(it)) }, index, 0L)
        player.prepare()
        player.play()
    }

    /** Inserts a song right after the current one; starts playback when the queue was empty. */
    fun playNext(track: Track, artist: String) {
        val player = controller ?: return
        val wasEmpty = player.mediaItemCount == 0
        val index = if (wasEmpty) 0 else player.currentMediaItemIndex + 1
        player.addMediaItem(index, toItem(track, artist))
        if (wasEmpty) startFresh(player)
    }

    fun addToQueue(track: Track, artist: String) {
        val player = controller ?: return
        val wasEmpty = player.mediaItemCount == 0
        player.addMediaItem(toItem(track, artist))
        if (wasEmpty) startFresh(player)
    }

    private fun startFresh(player: MediaController) {
        player.prepare()
        player.play()
    }

    private fun toItem(track: Track, artist: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(artist.ifEmpty { null })
            .setAlbumTitle(track.album.ifEmpty { null })
            // The service reads the embedded cover of this file for the notification.
            .setArtworkUri(Uri.parse(track.uri))
            .build()
        return MediaItem.Builder()
            .setMediaId(track.uri)
            .setUri(track.uri)
            .setMediaMetadata(metadata)
            .build()
    }

    // ------------------------------------------------------------------ transport

    fun play() {
        val player = controller ?: return
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        player.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    /** Goes back one track, whatever the position in the current song. */
    fun previousTrack() {
        controller?.seekToPreviousMediaItem()
    }

    /** Restarts the current song. */
    fun restart() {
        controller?.seekTo(0L)
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(maxOf(0L, positionMs))
    }

    fun toggleShuffle() {
        val player = controller ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    /** Off, repeat all, repeat one, and back to off. */
    fun cycleRepeat() {
        val player = controller ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ------------------------------------------------------------------ snapshot

    private fun publish() {
        val player = controller
        listener.onNowPlaying(if (player == null) NowPlaying.IDLE else snapshot(player))
    }

    private fun snapshot(player: MediaController): NowPlaying {
        val item = player.currentMediaItem ?: return NowPlaying.IDLE
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val state = player.playbackState
        return NowPlaying(
            active = true,
            trackUri = item.mediaId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            durationMs = duration,
            positionMs = maxOf(0L, player.currentPosition),
            positionUpdatedAtMs = SystemClock.elapsedRealtime(),
            speed = player.playbackParameters.speed,
            playing = player.playWhenReady && state != Player.STATE_IDLE && state != Player.STATE_ENDED,
            clockRunning = player.isPlaying,
            hasPreviousTrack = player.hasPreviousMediaItem(),
            canNext = player.hasNextMediaItem(),
            canSeek = player.isCurrentMediaItemSeekable && duration > 0L,
            shuffleOn = player.shuffleModeEnabled,
            repeatMode = repeatModeOf(player.repeatMode)
        )
    }

    private fun repeatModeOf(mode: Int): Int {
        return when (mode) {
            Player.REPEAT_MODE_ALL -> NowPlaying.REPEAT_ALL
            Player.REPEAT_MODE_ONE -> NowPlaying.REPEAT_ONE
            else -> NowPlaying.REPEAT_OFF
        }
    }

    private companion object {
        const val TAG = "PlayerConnection"
        const val MAX_QUEUE = 50000
    }
}
