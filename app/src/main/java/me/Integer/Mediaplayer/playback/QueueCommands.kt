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

    /** Key of a mashup step's crossfade length, in milliseconds, in the metadata extras. */
    const val EXTRA_CROSSFADE_MS = "mix_crossfade_ms"

    /** Key of a mashup step's initial tempo ratio during a beat-matched blend. */
    const val EXTRA_TEMPO_RATIO = "mix_tempo_ratio"

    /** The longest crossfade the player performs; the same limit as MixSegment.MAX_CROSSFADE_MS. */
    const val MAX_CROSSFADE_MS = 15000L

    val SET_SHUFFLE = SessionCommand(ACTION_SET_SHUFFLE, Bundle.EMPTY)
    val ADOPT_ORDER = SessionCommand(ACTION_ADOPT_ORDER, Bundle.EMPTY)
    val GET_STATE = SessionCommand(ACTION_GET_STATE, Bundle.EMPTY)

    /** The slot id of [item], or 0 when it has none. */
    fun uidOf(item: MediaItem): Long {
        return item.mediaMetadata.extras?.getLong(EXTRA_UID, 0L) ?: 0L
    }

    /** How long [item] blends into the next one; 0 for a normal song. */
    fun crossfadeOf(item: MediaItem): Long {
        val value = item.mediaMetadata.extras?.getLong(EXTRA_CROSSFADE_MS, 0L) ?: 0L
        return value.coerceIn(0L, MAX_CROSSFADE_MS)
    }

    /** Initial playback speed ratio for [item] at the start of its transition; 1f by default. */
    fun tempoRatioOf(item: MediaItem): Float {
        val value = item.mediaMetadata.extras?.getFloat(EXTRA_TEMPO_RATIO, 1f) ?: 1f
        return if (value.isFinite()) value.coerceIn(0.85f, 1.15f) else 1f
    }
}
