package me.Integer.Mediaplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import me.Integer.Mediaplayer.MainActivity
import kotlin.random.Random

/**
 * Owns the player. Running it as a media session service gives background playback, the
 * notification, lock-screen controls and headset buttons without extra code.
 * Audio focus and "pause when headphones are unplugged" are handled by the player.
 *
 * It also owns shuffle: the queue is physically reordered here, directly on the player,
 * and the pre-shuffle order is remembered here so it survives the screens being recreated.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private var artLoader: EmbeddedArtBitmapLoader? = null

    private var shuffleOn = false

    /** Slot ids in the order to return to when shuffle is turned off. */
    private var originalOrder = LongArray(0)

    private val sessionCallback = object : MediaSession.Callback {

        /** Allows the app's queue commands on top of the defaults. */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(QueueCommands.SET_SHUFFLE)
                .add(QueueCommands.ADOPT_ORDER)
                .add(QueueCommands.GET_STATE)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        /**
         * Media items lose their URI on the way from the app to the service, so it is
         * restored from the media id, which is the song's content URI. Anything else is dropped.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val playable = mediaItems
                .filter { it.mediaId.startsWith(CONTENT_PREFIX) }
                .map { it.buildUpon().setUri(it.mediaId).build() }
                .toMutableList()
            return Futures.immediateFuture(playable)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val handled = handleQueueCommand(customCommand.customAction, args)
            val code = if (handled) SessionResult.RESULT_SUCCESS else SessionError.ERROR_NOT_SUPPORTED
            return Futures.immediateFuture(SessionResult(code, stateExtras()))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val loader = EmbeddedArtBitmapLoader(this)
        artLoader = loader
        val exo = buildPlayer()
        player = exo
        session = MediaSession.Builder(this, exo)
            .setCallback(sessionCallback)
            .setSessionActivity(openAppIntent())
            .setBitmapLoader(loader)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return session
    }

    /** Swiping the app away stops the service unless music is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = session?.player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.let { current ->
            current.player.release()
            current.release()
        }
        session = null
        player = null
        artLoader?.shutdown()
        artLoader = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ shuffle

    /** @return false for a command this service does not know */
    private fun handleQueueCommand(action: String, args: Bundle): Boolean {
        return when (action) {
            QueueCommands.ACTION_SET_SHUFFLE -> {
                setShuffle(args.getBoolean(QueueCommands.ARG_ENABLED, false))
                true
            }
            QueueCommands.ACTION_ADOPT_ORDER -> {
                shuffleOn = args.getBoolean(QueueCommands.ARG_ENABLED, false)
                originalOrder = args.getLongArray(QueueCommands.ARG_UIDS) ?: LongArray(0)
                true
            }
            QueueCommands.ACTION_GET_STATE -> true
            else -> false
        }
    }

    private fun stateExtras(): Bundle {
        return Bundle().apply { putBoolean(QueueCommands.ARG_ENABLED, shuffleOn) }
    }

    /** Scrambles the queue around the playing song, or puts it back, without a gap in playback. */
    private fun setShuffle(enabled: Boolean) {
        if (shuffleOn == enabled) return
        shuffleOn = enabled
        val exo = player ?: return
        val current = currentUids(exo)
        if (enabled) {
            originalOrder = current
            applyOrder(exo, QueueOrder.shuffled(current.size, exo.currentMediaItemIndex, Random.Default))
        } else {
            val order = QueueOrder.restored(originalOrder, current)
            originalOrder = LongArray(0)
            applyOrder(exo, order)
        }
    }

    private fun currentUids(exo: ExoPlayer): LongArray {
        return LongArray(exo.mediaItemCount) { index ->
            QueueCommands.uidOf(exo.getMediaItemAt(index)).takeIf { it != 0L } ?: -(index.toLong() + 1L)
        }
    }

    /**
     * Rearranges the queue so that seat i holds the song now at order[i]. The playing song
     * itself is never replaced, only moved, so playback carries on.
     */
    private fun applyOrder(exo: ExoPlayer, order: IntArray) {
        val count = exo.mediaItemCount
        if (count <= 1) return
        if (!QueueOrder.isPermutation(order, count) || QueueOrder.isIdentity(order)) return
        val activeIndex = exo.currentMediaItemIndex.coerceIn(0, count - 1)
        val seat = order.indexOf(activeIndex)
        if (seat < 0) return
        val items = List(count) { exo.getMediaItemAt(it) }
        val before = List(seat) { items[order[it]] }
        val after = List(count - seat - 1) { items[order[seat + 1 + it]] }
        try {
            if (activeIndex > 0) exo.moveMediaItem(activeIndex, 0)
            if (after.isEmpty()) exo.removeMediaItems(1, count) else exo.replaceMediaItems(1, count, after)
            if (before.isNotEmpty()) exo.addMediaItems(0, before)
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot reorder the queue", e)
        }
    }

    // ------------------------------------------------------------------ setup

    private fun buildPlayer(): ExoPlayer {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(this)
            .setAudioAttributes(attributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
    }

    /** Tapping the notification opens the app. */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private companion object {
        const val TAG = "PlaybackService"
        const val CONTENT_PREFIX = "content://"
    }
}
