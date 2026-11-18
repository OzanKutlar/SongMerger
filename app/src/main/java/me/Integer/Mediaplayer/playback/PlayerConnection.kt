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
import me.Integer.Mediaplayer.library.MixSegment
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
class PlayerConnection(
    context: Context,
    private val listener: NowPlayingListener,
    /** The crossfade between normal songs from the settings, read as songs are queued. */
    private val songCrossfadeMs: () -> Long = { 0L }
) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var pending: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var shuffleEnabled = false
    private var sleepDeadlineElapsedMs = 0L
    private var sleepEndOfTrack = false
    private var nextUid = 1L
    private var queue = TimelineQueue.EMPTY
    private var publishQueued = false
    private var queueLoadGeneration = 0L

    private val publishTask = Runnable {
        publishQueued = false
        publishNow()
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // A DJ crossfade changes the volume many times a second; nothing on screen shows it.
            if (events.size() == 1 && events.contains(Player.EVENT_VOLUME_CHANGED)) return
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
        queueLoadGeneration++
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
        if (connected.mediaItemCount == 0) requestRestore(connected)
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

    /**
     * An empty service gets the last saved queue back, paused. The answer carries the shuffle
     * state and the highest slot id, so songs added afterwards get fresh ids.
     */
    private fun requestRestore(player: MediaController) {
        val future = player.sendCustomCommand(QueueCommands.RESTORE_LAST, Bundle.EMPTY)
        future.addListener({
            val result = resultOf(future, "restoring the last queue")
            if (result == null || controller !== player) return@addListener
            if (result.resultCode != SessionResult.RESULT_SUCCESS) {
                Log.w(TAG, "restoring the last queue was refused with code ${result.resultCode}")
                return@addListener
            }
            nextUid = maxOf(nextUid, result.extras.getLong(QueueCommands.ARG_MAX_UID, 0L) + 1L)
            applyShuffleState(result)
            publish()
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun applyShuffleState(result: SessionResult) {
        if (result.resultCode != SessionResult.RESULT_SUCCESS) {
            Log.w(TAG, "the shuffle state was refused with code ${result.resultCode}")
            return
        }
        val rem = result.extras.getLong(QueueCommands.ARG_SLEEP_REMAINING_MS, 0L)
        sleepEndOfTrack = rem == -1L
        sleepDeadlineElapsedMs = if (rem > 0L) SystemClock.elapsedRealtime() + rem else 0L
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
        val gen = ++queueLoadGeneration
        val capped = if (tracks.size > MAX_QUEUE) tracks.subList(0, MAX_QUEUE) else tracks
        val total = capped.size
        val index = startIndex.coerceIn(0, total - 1)
        val scramble = shuffle && total > 1
        shuffleEnabled = shuffle
        val baseUid = nextUid
        nextUid += total
        val original = if (scramble) LongArray(total) { baseUid + it } else LongArray(0)
        val args = Bundle().apply {
            putBoolean(QueueCommands.ARG_ENABLED, shuffle)
            putLongArray(QueueCommands.ARG_UIDS, original)
        }
        send(player, QueueCommands.ADOPT_ORDER, args)
        player.shuffleModeEnabled = false
        if (total <= INITIAL_QUEUE_BATCH) {
            val items = ArrayList<MediaItem>(total)
            for (i in 0 until total) {
                val track = capped[i]
                items.add(toItemWithUid(track, artistOf(track), baseUid + i))
            }
            if (scramble) {
                val first = items[index]
                val rest = items.filterIndexed { i, _ -> i != index }.shuffled()
                val scrambled = ArrayList<MediaItem>(total).apply {
                    add(first)
                    addAll(rest)
                }
                player.setMediaItems(scrambled, 0, 0L)
            } else {
                player.setMediaItems(items, index, 0L)
            }
            player.prepare()
            player.play()
            return
        }
        if (scramble) {
            val order = IntArray(total - 1) { if (it < index) it else it + 1 }
            order.shuffle()
            val initialEnd = minOf(INITIAL_QUEUE_BATCH - 1, order.size)
            val initialItems = ArrayList<MediaItem>(initialEnd + 1)
            val firstTrack = capped[index]
            initialItems.add(toItemWithUid(firstTrack, artistOf(firstTrack), baseUid + index))
            for (i in 0 until initialEnd) {
                val pos = order[i]
                val track = capped[pos]
                initialItems.add(toItemWithUid(track, artistOf(track), baseUid + pos))
            }
            player.setMediaItems(initialItems, 0, 0L)
            player.prepare()
            player.play()
            scheduleRemainingScrambled(gen, player, capped, order, initialEnd, baseUid, artistOf)
        } else {
            val winStart = maxOf(0, index - INITIAL_QUEUE_HALF_WINDOW)
            val winEnd = minOf(total, index + INITIAL_QUEUE_HALF_WINDOW)
            val initialItems = ArrayList<MediaItem>(winEnd - winStart)
            for (i in winStart until winEnd) {
                val track = capped[i]
                initialItems.add(toItemWithUid(track, artistOf(track), baseUid + i))
            }
            player.setMediaItems(initialItems, index - winStart, 0L)
            player.prepare()
            player.play()
            scheduleRemainingOrdered(gen, player, capped, winStart, winEnd, baseUid, artistOf)
        }
    }

    private fun scheduleRemainingScrambled(
        gen: Long,
        player: MediaController,
        tracks: List<Track>,
        order: IntArray,
        nextPos: Int,
        baseUid: Long,
        artistOf: (Track) -> String
    ) {
        if (nextPos >= order.size) return
        main.postDelayed({
            if (gen != queueLoadGeneration || controller !== player) return@postDelayed
            val end = minOf(nextPos + QUEUE_CHUNK_SIZE, order.size)
            val batch = ArrayList<MediaItem>(end - nextPos)
            for (i in nextPos until end) {
                val pos = order[i]
                val track = tracks[pos]
                batch.add(toItemWithUid(track, artistOf(track), baseUid + pos))
            }
            player.addMediaItems(batch)
            scheduleRemainingScrambled(gen, player, tracks, order, end, baseUid, artistOf)
        }, QUEUE_CHUNK_DELAY_MS)
    }

    private fun scheduleRemainingOrdered(
        gen: Long,
        player: MediaController,
        tracks: List<Track>,
        prependEnd: Int,
        appendStart: Int,
        baseUid: Long,
        artistOf: (Track) -> String
    ) {
        if (prependEnd <= 0 && appendStart >= tracks.size) return
        main.postDelayed({
            if (gen != queueLoadGeneration || controller !== player) return@postDelayed
            if (appendStart < tracks.size) {
                val end = minOf(appendStart + QUEUE_CHUNK_SIZE, tracks.size)
                val batch = ArrayList<MediaItem>(end - appendStart)
                for (i in appendStart until end) {
                    val track = tracks[i]
                    batch.add(toItemWithUid(track, artistOf(track), baseUid + i))
                }
                player.addMediaItems(batch)
                scheduleRemainingOrdered(gen, player, tracks, prependEnd, end, baseUid, artistOf)
            } else {
                val start = maxOf(0, prependEnd - QUEUE_CHUNK_SIZE)
                val batch = ArrayList<MediaItem>(prependEnd - start)
                for (i in start until prependEnd) {
                    val track = tracks[i]
                    batch.add(toItemWithUid(track, artistOf(track), baseUid + i))
                }
                player.addMediaItems(0, batch)
                scheduleRemainingOrdered(gen, player, tracks, start, appendStart, baseUid, artistOf)
            }
        }, QUEUE_CHUNK_DELAY_MS)
    }

    /**
     * Replaces the queue with a mashup and starts at step [startIndex]. Each step plays only
     * its part of the song and blends into the next one. Shuffle is turned off, because the
     * steps were chosen for this order.
     *
     * @param steps each song with the part of it to play, in playing order
     */
    fun playMix(steps: List<Pair<Track, MixSegment>>, startIndex: Int, artistOf: (Track) -> String) {
        val player = controller ?: return
        if (steps.isEmpty()) return
        queueLoadGeneration++
        val items = steps.take(MAX_QUEUE).map { (track, segment) -> toItem(track, artistOf(track), segment) }
        val index = startIndex.coerceIn(0, items.size - 1)
        shuffleEnabled = false
        val args = Bundle().apply {
            putBoolean(QueueCommands.ARG_ENABLED, false)
            putLongArray(QueueCommands.ARG_UIDS, LongArray(0))
        }
        send(player, QueueCommands.ADOPT_ORDER, args)
        player.shuffleModeEnabled = false
        player.setMediaItems(items, index, 0L)
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

    /**
     * Removes several runs of queue positions, one player call per run. [ranges] must go
     * from the end of the queue backwards, so each removal leaves the next runs valid;
     * a run that breaks that order or lies outside the queue is skipped.
     */
    fun removeQueueRanges(ranges: List<IntRange>) {
        val player = controller ?: return
        var below = Int.MAX_VALUE
        for (range in ranges) {
            if (range.isEmpty() || range.first < 0 || range.last >= below) continue
            if (range.last >= player.mediaItemCount) continue
            player.removeMediaItems(range.first, range.last + 1)
            below = range.first
        }
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

    /** @param segment the part of the song to play in a mashup; null plays the whole song */
    private fun toItem(track: Track, artist: String, segment: MixSegment? = null): MediaItem {
        return toItemWithUid(track, artist, nextUid++, segment)
    }

    private fun toItemWithUid(track: Track, artist: String, uid: Long, segment: MixSegment? = null): MediaItem {
        val extras = Bundle().apply {
            putLong(QueueCommands.EXTRA_UID, uid)
            if (segment != null) {
                putLong(QueueCommands.EXTRA_CROSSFADE_MS, segment.crossfadeMs)
                putFloat(QueueCommands.EXTRA_TEMPO_RATIO, segment.tempoRatio)
                if (segment.gainDb != 0f) putFloat(QueueCommands.EXTRA_GAIN_DB, segment.gainDb)
            } else {
                val fade = songCrossfadeMs().coerceIn(0L, QueueCommands.MAX_CROSSFADE_MS)
                if (fade > 0L) putLong(QueueCommands.EXTRA_CROSSFADE_MS, fade)
            }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(artist.ifEmpty { null })
            .setAlbumTitle(track.album.ifEmpty { null })
            .setArtworkUri(Uri.parse(track.uri))
            .setExtras(extras)
            .build()
        val builder = MediaItem.Builder()
            .setMediaId(track.uri)
            .setUri(track.uri)
            .setMediaMetadata(metadata)
        if (segment != null) builder.setClippingConfiguration(clippingOf(segment))
        return builder.build()
    }

    /** The player plays only this part of the song; positions are relative to its start. */
    private fun clippingOf(segment: MixSegment): MediaItem.ClippingConfiguration {
        val end = if (segment.playsToEnd) C.TIME_END_OF_SOURCE else segment.endMs
        return MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(segment.startMs)
            .setEndPositionMs(end)
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

    /** Sets or cancels the sleep timer (0 minutes & endOfTrack=false cancels). */
    fun setSleepTimer(minutes: Int, endOfTrack: Boolean = false) {
        val player = controller ?: return
        sleepEndOfTrack = endOfTrack
        sleepDeadlineElapsedMs = if (!endOfTrack && minutes > 0) {
            SystemClock.elapsedRealtime() + minutes * 60_000L
        } else {
            0L
        }
        val args = Bundle().apply {
            putInt(QueueCommands.ARG_MINUTES, minutes)
            putBoolean(QueueCommands.ARG_END_OF_TRACK, endOfTrack)
        }
        send(player, QueueCommands.SET_SLEEP_TIMER, args)
    }

    /** Remaining sleep timer milliseconds, -1 if set to end of current track, or 0 if off. */
    fun sleepTimerRemainingMs(): Long {
        if (sleepEndOfTrack) return -1L
        val rem = sleepDeadlineElapsedMs - SystemClock.elapsedRealtime()
        return if (rem > 0L) rem else 0L
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
        const val INITIAL_QUEUE_BATCH = 64
        const val INITIAL_QUEUE_HALF_WINDOW = 32
        const val QUEUE_CHUNK_SIZE = 250
        const val QUEUE_CHUNK_DELAY_MS = 32L
    }
}
