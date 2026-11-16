package me.Integer.Mediaplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
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
 *
 * Shuffle is implemented by physically reordering the player's media items rather than
 * ExoPlayer's hidden shuffle index. That way the queue view always shows the real upcoming
 * order and dragging items or tapping "Play next" works predictably.
 */
class PlayerConnection(context: Context, private val listener: NowPlayingListener) {

    private val appContext = context.applicationContext
    private var pending: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var shuffleEnabled = false
    private val originalOrder = ArrayList<MediaItem>()
    private var nextUid = 1L

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
        if (pending !== future) return
        try {
            val connected = future.get()
            connected.shuffleModeEnabled = false
            connected.addListener(playerListener)
            controller = connected
            if (originalOrder.isEmpty() && connected.mediaItemCount > 0) {
                originalOrder.addAll(currentItems(connected))
            }
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
        val items = tracks.take(MAX_QUEUE).map { toItem(it, artistOf(it)) }
        val index = startIndex.coerceIn(0, items.size - 1)
        originalOrder.clear()
        originalOrder.addAll(items)
        player.shuffleModeEnabled = false
        if (shuffleEnabled && items.size > 1) {
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
        originalOrder.add(item)
        player.addMediaItem(insertAt, item)
        if (wasEmpty) startFresh(player) else publish()
    }

    fun addToQueue(track: Track, artist: String) {
        val player = controller ?: return
        val item = toItem(track, artist)
        val wasEmpty = player.mediaItemCount == 0
        originalOrder.add(item)
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
        val count = player.mediaItemCount
        if (index !in 0 until count) return
        val removedUid = uidOf(player.getMediaItemAt(index))
        originalOrder.removeAll { uidOf(it) == removedUid }
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
        val extras = Bundle().apply { putLong(EXTRA_UID, uid) }
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

    // ------------------------------------------------------------------ transport & physical shuffle

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
     * Physically scrambles or restores the queue around the active track without interrupting
     * playback.
     */
    fun setShuffle(enabled: Boolean) {
        if (shuffleEnabled == enabled) return
        shuffleEnabled = enabled
        val player = controller ?: return
        player.shuffleModeEnabled = false
        val count = player.mediaItemCount
        if (count <= 1) {
            publish()
            return
        }
        val current = currentItems(player)
        if (originalOrder.isEmpty()) originalOrder.addAll(current)
        val activeIndex = player.currentMediaItemIndex.coerceIn(0, count - 1)
        if (enabled) {
            scrambleAroundActive(player, current, activeIndex)
        } else {
            restoreOriginalAroundActive(player, current[activeIndex], activeIndex)
        }
        publish()
    }

    private fun scrambleAroundActive(player: MediaController, items: List<MediaItem>, activeIndex: Int) {
        val others = items.filterIndexed { index, _ -> index != activeIndex }.shuffled()
        if (activeIndex > 0) player.moveMediaItem(activeIndex, 0)
        player.replaceMediaItems(1, player.mediaItemCount, others)
    }

    private fun restoreOriginalAroundActive(player: MediaController, active: MediaItem, activeIndex: Int) {
        val activeUid = uidOf(active)
        val origIdx = originalOrder.indexOfFirst { uidOf(it) == activeUid }
            .takeIf { it >= 0 }
            ?: originalOrder.indexOfFirst { it.mediaId == active.mediaId }
        if (origIdx < 0) return
        val before = originalOrder.subList(0, origIdx).toList()
        val after = originalOrder.subList(origIdx + 1, originalOrder.size).toList()
        if (activeIndex > 0) player.moveMediaItem(activeIndex, 0)
        player.replaceMediaItems(1, player.mediaItemCount, after)
        if (before.isNotEmpty()) player.addMediaItems(0, before)
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
        val queue = buildQueueSnapshot(player)
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
            queue = queue
        )
    }

    private fun buildQueueSnapshot(player: MediaController): List<QueueItem> {
        val count = minOf(player.mediaItemCount, MAX_QUEUE)
        val list = ArrayList<QueueItem>(count)
        for (i in 0 until count) {
            val item = player.getMediaItemAt(i)
            val meta = item.mediaMetadata
            val uid = uidOf(item).takeIf { it != 0L } ?: (i.toLong() + 1L)
            list.add(
                QueueItem(
                    uid = uid,
                    trackUri = item.mediaId,
                    title = meta.title?.toString().orEmpty(),
                    artist = meta.artist?.toString().orEmpty(),
                    album = meta.albumTitle?.toString().orEmpty()
                )
            )
        }
        return list
    }

    private fun currentItems(player: MediaController): List<MediaItem> {
        val count = minOf(player.mediaItemCount, MAX_QUEUE)
        return List(count) { player.getMediaItemAt(it) }
    }

    private fun uidOf(item: MediaItem): Long {
        return item.mediaMetadata.extras?.getLong(EXTRA_UID, 0L) ?: 0L
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
        const val EXTRA_UID = "queue_uid"
    }
}
