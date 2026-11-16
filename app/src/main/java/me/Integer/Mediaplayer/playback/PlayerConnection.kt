package me.Integer.Mediaplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import me.Integer.Mediaplayer.library.Track
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/**
 * The screens' remote control for [PlaybackService]: transport commands in, [NowPlaying]
 * snapshots out. Connecting starts the service if needed; releasing does not stop the music.
 *
 * Shuffle physically reorders the player's media items rather than using ExoPlayer's hidden
 * shuffle index, so the queue view always shows the real upcoming order and dragging items
 * or tapping "Play next" works predictably. The reordering and the pre-shuffle order live
 * in the service; this class only asks for them. Main thread only.
 */
class PlayerConnection(context: Context, private val listener: NowPlayingListener) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var pending: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var shuffleEnabled = false
    private var nextUid = 1L
    private var queue = TimelineQueue.EMPTY
    private var publishQueued = false

    private val publishTask = Runnable {
        publishQueued = false
        publishNow()
    }

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
        main.removeCallbacks(publishTask)
        publishQueued = false
        controller?.removeListener(playerListener)
        controller = null
        pending?.let { MediaController.releaseFuture(it) }
        pending = null
    }

    private fun onConnected(future: ListenableFuture<MediaController>) {
        if (pending !== future) return
        val connected = resultOf(future, "connecting to the playback service")
        if (connected == null) {
            pending = null
            return
        }
        connected.shuffleModeEnabled = false
        connected.addListener(playerListener)
        controller = connected
        // Songs already queued keep their ids, so new ones must start above them.
        nextUid = maxOf(nextUid, highestUid(connected) + 1L)
        requestShuffleState(connected)
        publish()
    }

    /** The value of a finished [future], or null (logged) when it failed. */
    private fun <T : Any> resultOf(future: ListenableFuture<T>, what: String): T? {
        return try {
            future.get()
        } catch (e: ExecutionException) {
            Log.w(TAG, "$what failed: ${e.message}")
            null
        } catch (e: CancellationException) {
            Log.w(TAG, "$what was cancelled")
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "$what was interrupted")
            null
        }
    }

    private fun highestUid(player: Player): Long {
        val timeline = player.currentTimeline
        val window = Timeline.Window()
        val count = minOf(timeline.windowCount, MAX_QUEUE)
        var highest = 0L
        for (i in 0 until count) {
            highest = maxOf(highest, QueueCommands.uidOf(timeline.getWindow(i, window).mediaItem))
        }
        return highest
    }

    // ------------------------------------------------------------------ service commands

    /** The service is the source of truth for shuffle; this reads it after connecting. */
    private fun requestShuffleState(player: MediaController) {
        val future = player.sendCustomCommand(QueueCommands.GET_STATE, Bundle.EMPTY)
        future.addListener({
            val result = resultOf(future, "reading the shuffle state")
            if (result != null && controller === player) applyShuffleState(result)
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun applyShuffleState(result: SessionResult) {
        if (result.resultCode != SessionResult.RESULT_SUCCESS) {
            Log.w(TAG, "the shuffle state was refused with code ${result.resultCode}")
            return
        }
        val enabled = result.extras.getBoolean(QueueCommands.ARG_ENABLED, shuffleEnabled)
        if (enabled == shuffleEnabled) return
        shuffleEnabled = enabled
        publish()
    }

    /** Sends a queue command; a refusal is logged and the real state is read back. */
    private fun send(player: MediaController, command: SessionCommand, args: Bundle) {
        val future = player.sendCustomCommand(command, args)
        future.addListener({
            val result = resultOf(future, command.customAction)
            val refused = result == null || result.resultCode != SessionResult.RESULT_SUCCESS
            if (refused && controller === player) {
                Log.w(TAG, "${command.customAction} did not succeed")
                requestShuffleState(player)
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    // ------------------------------------------------------------------ queue

    /**
     * Replaces the queue with [tracks] and starts playing at [startIndex].
     *
     * @param shuffle the shuffle mode for the new queue; by default the current one is kept
     */
    fun playTracks(
        tracks: List<Track>,
        startIndex: Int,
        shuffle: Boolean = shuffleEnabled,
        artistOf: (Track) -> String
    ) {
        val player = controller ?: return
        if (tracks.isEmpty()) return
        val items = tracks.take(MAX_QUEUE).map { toItem(it, artistOf(it)) }
        val index = startIndex.coerceIn(0, items.size - 1)
        val scramble = shuffle && items.size > 1
        shuffleEnabled = shuffle
        val original = if (scramble) LongArray(items.size) { QueueCommands.uidOf(items[it]) } else LongArray(0)
        val args = Bundle().apply {
            putBoolean(QueueCommands.ARG_ENABLED, shuffle)
            putLongArray(QueueCommands.ARG_UIDS, original)
        }
        send(player, QueueCommands.ADOPT_ORDER, args)
        player.shuffleModeEnabled = false
        if (scramble) {
            val first = items[index]
            val rest = items.filterIndexed { i, _ -> i != index }.shuffled()
            val scrambled = ArrayList<MediaItem>(items.size).apply {
                add(first)
                addAll(rest)
            }
            player.setMediaItems(scrambled, 0, 0L)
        } else {
            player.setMediaItems(items, index, 0L)
        }
        player.prepare()
        player.play()
    }

    /** Inserts a song right after the current one; starts playback when the queue was empty. */
    fun playNext(track: Track, artist: String) {
        val player = controller ?: return
        val item = toItem(track, artist)
        val wasEmpty = player.mediaItemCount == 0
        val insertAt = if (wasEmpty) 0 else (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
        player.addMediaItem(insertAt, item)
        if (wasEmpty) startFresh(player) else publish()
    }

    fun addToQueue(track: Track, artist: String) {
        val player = controller ?: return
        val item = toItem(track, artist)
        val wasEmpty = player.mediaItemCount == 0
        player.addMediaItem(item)
        if (wasEmpty) startFresh(player) else publish()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val player = controller ?: return
        val count = player.mediaItemCount
        if (fromIndex !in 0 until count || toIndex !in 0 until count || fromIndex == toIndex) return
        player.moveMediaItem(fromIndex, toIndex)
        publish()
    }

    fun removeQueueItem(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.removeMediaItem(index)
        publish()
    }

    fun skipToQueueItem(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.seekTo(index, 0L)
        player.play()
    }

    private fun startFresh(player: MediaController) {
        player.prepare()
        player.play()
    }

    private fun toItem(track: Track, artist: String): MediaItem {
        val uid = nextUid++
        val extras = Bundle().apply { putLong(QueueCommands.EXTRA_UID, uid) }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(artist.ifEmpty { null })
            .setAlbumTitle(track.album.ifEmpty { null })
            .setArtworkUri(Uri.parse(track.uri))
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.uri)
            .setUri(track.uri)
            .setMediaMetadata(metadata)
            .build()
    }

    // ------------------------------------------------------------------ transport & shuffle

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
        setShuffle(!shuffleEnabled)
    }

    /**
     * Asks the service to scramble or restore the queue around the active track. The button
     * state changes at once; the new order arrives with the next snapshot.
     */
    fun setShuffle(enabled: Boolean) {
        if (shuffleEnabled == enabled) return
        shuffleEnabled = enabled
        val player = controller
        if (player != null) {
            val args = Bundle().apply { putBoolean(QueueCommands.ARG_ENABLED, enabled) }
            send(player, QueueCommands.SET_SHUFFLE, args)
        }
        publish()
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

    /** Several changes in a row become one snapshot, delivered right after them. */
    private fun publish() {
        if (publishQueued) return
        publishQueued = true
        main.post(publishTask)
    }

    private fun publishNow() {
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
            album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
            durationMs = duration,
            positionMs = maxOf(0L, player.currentPosition),
            positionUpdatedAtMs = SystemClock.elapsedRealtime(),
            speed = player.playbackParameters.speed,
            playing = player.playWhenReady && state != Player.STATE_IDLE && state != Player.STATE_ENDED,
            clockRunning = player.isPlaying,
            hasPreviousTrack = player.hasPreviousMediaItem(),
            canNext = player.hasNextMediaItem(),
            canSeek = player.isCurrentMediaItemSeekable && duration > 0L,
            shuffleOn = shuffleEnabled,
            repeatMode = repeatModeOf(player.repeatMode),
            currentIndex = player.currentMediaItemIndex,
            queue = queueOf(player)
        )
    }

    /** The same list object for as long as the player's timeline does not change. */
    private fun queueOf(player: Player): TimelineQueue {
        val timeline = player.currentTimeline
        val cached = queue
        if (cached.timeline === timeline) return cached
        val fresh = TimelineQueue.of(timeline, MAX_QUEUE)
        queue = fresh
        return fresh
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
