package me.Integer.Mediaplayer.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.SessionCommand

/**
 * The private commands between [PlayerConnection] and [PlaybackService] that keep the
 * shuffle state and the pre-shuffle order inside the service.
 */
internal object QueueCommands {

    /** Shuffles or restores the live queue. Argument: [ARG_ENABLED]. */
    const val ACTION_SET_SHUFFLE = "me.Integer.Mediaplayer.SET_SHUFFLE"

    /** Records the mode and the pre-shuffle order of a queue that is about to be set. */
    const val ACTION_ADOPT_ORDER = "me.Integer.Mediaplayer.ADOPT_ORDER"

    /** Asks for the current mode. Every answer carries [ARG_ENABLED]. */
    const val ACTION_GET_STATE = "me.Integer.Mediaplayer.GET_STATE"

    const val ARG_ENABLED = "enabled"
    const val ARG_UIDS = "uids"

    /** Key of the per-slot id in a media item's metadata extras. */
    const val EXTRA_UID = "queue_uid"

    val SET_SHUFFLE = SessionCommand(ACTION_SET_SHUFFLE, Bundle.EMPTY)
    val ADOPT_ORDER = SessionCommand(ACTION_ADOPT_ORDER, Bundle.EMPTY)
    val GET_STATE = SessionCommand(ACTION_GET_STATE, Bundle.EMPTY)

    /** The slot id of [item], or 0 when it has none. */
    fun uidOf(item: MediaItem): Long {
        return item.mediaMetadata.extras?.getLong(EXTRA_UID, 0L) ?: 0L
    }
}
