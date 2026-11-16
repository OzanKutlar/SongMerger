package me.Integer.Mediaplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import me.Integer.Mediaplayer.MainActivity

/**
 * Owns the player. Running it as a media session service gives background playback, the
 * notification, lock-screen controls and headset buttons without extra code.
 * Audio focus and "pause when headphones are unplugged" are handled by the player.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var artLoader: EmbeddedArtBitmapLoader? = null

    /**
     * Media items lose their URI on the way from the app to the service, so it is restored
     * from the media id, which is the song's content URI. Anything else is dropped.
     */
    private val sessionCallback = object : MediaSession.Callback {
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
    }

    override fun onCreate() {
        super.onCreate()
        val loader = EmbeddedArtBitmapLoader(this)
        artLoader = loader
        session = MediaSession.Builder(this, buildPlayer())
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
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.let { current ->
            current.player.release()
            current.release()
        }
        session = null
        artLoader?.shutdown()
        artLoader = null
        super.onDestroy()
    }

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
        const val CONTENT_PREFIX = "content://"
    }
}
