package me.Integer.Mediaplayer.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * DJ transitions for mashup queues. A queue item with a crossfade (see
 * [QueueCommands.crossfadeOf]) blends into the next item instead of cutting.
 *
 * The [primary] player stays the only player the media session knows, so the notification,
 * lock screen and seekbar keep working. A second, outgoing deck plays the last seconds of
 * the step that is ending:
 *
 * 1. ARMED: shortly before the fade, the outgoing deck loads the same item, paused, at the
 *    fade position.
 * 2. HANDOFF: at the fade position the outgoing deck starts playing.
 * 3. FADING: the primary player mutes, moves to the next item and the two volumes follow
 *    an equal-power curve until the outgoing deck is silent.
 *
 * Pausing, seeking, skipping or changing the queue cancels the fade and restores the
 * volume. Main thread only, like the players.
 */
internal class DjCrossfader(context: Context, private val primary: ExoPlayer) {

    private enum class Stage { IDLE, ARMED, HANDOFF, FADING }

    private val main = Handler(Looper.getMainLooper())
    private val tail: ExoPlayer = buildTail(context.applicationContext)

    private var stage = Stage.IDLE
    private var released = false
    private var ticking = false

    /** The queue slot that is fading out, and the one that entered at the handoff. */
    private var outgoingUid = 0L
    private var incomingUid = 0L

    /** Position in the outgoing item where the fade begins, and how long it lasts. */
    private var fadeAtMs = 0L
    private var fadeLengthMs = 0L
    private var handoffAt = 0L
    private var fadeStartedAt = 0L

    /** Position jumps caused by this class are ignored until then. */
    private var ignoreJumpsUntil = 0L

    /** A slot whose transition was given up; it ends with a plain cut. */
    private var skippedUid = 0L

    private val tickTask = Runnable { onTick() }
    private val triggerTask = Runnable { trigger() }

    private val primaryListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) abort()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (SystemClock.elapsedRealtime() <= ignoreJumpsUntil) return
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && stage == Stage.IDLE) return
            // A seek, skip or queue change by the user: cancel, and allow a new attempt.
            abort()
            skippedUid = 0L
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) && currentUid() != skippedUid) {
                skippedUid = 0L
            }
            schedule()
        }
    }

    private val tailListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && stage == Stage.HANDOFF) switchPrimary()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "the outgoing deck failed: ${error.errorCodeName}")
            val uid = outgoingUid
            abort()
            skippedUid = uid
        }
    }

    init {
        primary.addListener(primaryListener)
        tail.addListener(tailListener)
    }

    // ------------------------------------------------------------------ public

    /** Cancels a pending or running transition and puts the volume back. */
    fun abort() {
        main.removeCallbacks(triggerTask)
        if (stage == Stage.IDLE) return
        stage = Stage.IDLE
        stopTail()
        primary.volume = 1f
        schedule()
    }

    fun release() {
        if (released) return
        abort()
        released = true
        main.removeCallbacks(tickTask)
        main.removeCallbacks(triggerTask)
        ticking = false
        primary.removeListener(primaryListener)
        tail.removeListener(tailListener)
        tail.release()
    }

    // ------------------------------------------------------------------ ticking

    /** Ticks only while a transition is running or the playing item has one coming. */
    private fun schedule() {
        if (released) return
        val needed = stage != Stage.IDLE || (primary.isPlaying && plannedFadeMs() > 0L)
        if (!needed) {
            main.removeCallbacks(tickTask)
            ticking = false
            return
        }
        if (ticking) return
        ticking = true
        val fast = stage == Stage.HANDOFF || stage == Stage.FADING
        main.postDelayed(tickTask, if (fast) FADE_TICK_MS else WATCH_TICK_MS)
    }

    private fun restartTicks() {
        main.removeCallbacks(tickTask)
        ticking = false
        schedule()
    }

    private fun onTick() {
        ticking = false
        if (released) return
        when (stage) {
            Stage.IDLE -> considerArming()
            Stage.ARMED -> watchArmed()
            Stage.HANDOFF -> watchHandoff()
            Stage.FADING -> stepFade()
        }
        schedule()
    }

    // ------------------------------------------------------------------ arming

    /** The fade length for the playing item, or 0 when it should end with a plain cut. */
    private fun plannedFadeMs(): Long {
        val item = primary.currentMediaItem ?: return 0L
        val uid = QueueCommands.uidOf(item)
        val crossfade = QueueCommands.crossfadeOf(item)
        if (uid == 0L || uid == skippedUid || crossfade < MIN_FADE_MS) return 0L
        if (primary.repeatMode == Player.REPEAT_MODE_ONE || !primary.hasNextMediaItem()) return 0L
        val duration = primary.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return 0L
        val fade = minOf(crossfade, duration / 2)
        return if (fade >= MIN_FADE_MS) fade else 0L
    }

    private fun considerArming() {
        if (!primary.isPlaying) return
        val fade = plannedFadeMs()
        val item = primary.currentMediaItem
        if (fade <= 0L || item == null) return
        val fadeAt = primary.duration - fade
        val untilFade = fadeAt - primary.currentPosition
        if (untilFade <= 0L) {
            // Already inside the fade window, for example after starting near the end.
            skippedUid = QueueCommands.uidOf(item)
            return
        }
        if (untilFade <= WARMUP_MS) arm(item, fadeAt, fade)
    }

    private fun arm(item: MediaItem, fadeAt: Long, fade: Long) {
        val uid = QueueCommands.uidOf(item)
        try {
            tail.volume = 1f
            tail.playWhenReady = false
            tail.setMediaItem(item, fadeAt)
            tail.prepare()
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot prepare the outgoing deck: ${e.message}")
            stopTail()
            skippedUid = uid
            return
        }
        outgoingUid = uid
        fadeAtMs = fadeAt
        fadeLengthMs = fade
        stage = Stage.ARMED
    }

    private fun watchArmed() {
        if (!primary.playWhenReady || currentUid() != outgoingUid) {
            abort()
            return
        }
        val untilFade = fadeAtMs - primary.currentPosition
        if (untilFade > WARMUP_MS + REWIND_SLACK_MS) {
            // Moved back: it arms again closer to the end.
            abort()
            return
        }
        if (untilFade > WATCH_TICK_MS) return
        main.removeCallbacks(triggerTask)
        main.postDelayed(triggerTask, untilFade.coerceAtLeast(0L))
    }

    // ------------------------------------------------------------------ handoff & fade

    private fun trigger() {
        if (stage != Stage.ARMED) return
        if (!primary.playWhenReady || currentUid() != outgoingUid || !primary.hasNextMediaItem()) {
            abort()
            return
        }
        val late = primary.currentPosition - fadeAtMs
        if (late < -EARLY_SLACK_MS) return // the next tick posts the trigger again
        if (late > LATE_SLACK_MS || tail.playbackState != Player.STATE_READY) {
            val uid = outgoingUid
            abort()
            skippedUid = uid
            return
        }
        stage = Stage.HANDOFF
        handoffAt = SystemClock.elapsedRealtime()
        tail.play()
        restartTicks()
    }

    /** Moves on even if the outgoing deck never reports playing, so playback cannot stall. */
    private fun watchHandoff() {
        if (!primary.playWhenReady || currentUid() != outgoingUid) {
            abort()
            return
        }
        if (SystemClock.elapsedRealtime() - handoffAt >= HANDOFF_TIMEOUT_MS) switchPrimary()
    }

    /** The outgoing deck carries the old step; the muted primary player enters the next one. */
    private fun switchPrimary() {
        if (stage != Stage.HANDOFF) return
        if (!primary.hasNextMediaItem()) {
            abort()
            return
        }
        primary.volume = 0f
        ignoreJumpsUntil = SystemClock.elapsedRealtime() + IGNORE_JUMPS_MS
        try {
            primary.seekToNextMediaItem()
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot move to the next step: ${e.message}")
            abort()
            return
        }
        incomingUid = currentUid()
        fadeStartedAt = SystemClock.elapsedRealtime()
        stage = Stage.FADING
        applyGains(0f)
        restartTicks()
    }

    private fun stepFade() {
        if (currentUid() != incomingUid) {
            abort()
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - fadeStartedAt
        val progress = (elapsed.toFloat() / fadeLengthMs.toFloat()).coerceIn(0f, 1f)
        val tailState = tail.playbackState
        val tailDone = tailState == Player.STATE_ENDED || tailState == Player.STATE_IDLE
        if (progress >= 1f || tailDone) {
            finish()
            return
        }
        applyGains(progress)
    }

    private fun finish() {
        stage = Stage.IDLE
        stopTail()
        primary.volume = 1f
    }

    /** Equal power: the combined loudness stays level through the blend. */
    private fun applyGains(progress: Float) {
        val angle = progress * HALF_PI
        primary.volume = sin(angle)
        tail.volume = cos(angle)
    }

    private fun stopTail() {
        try {
            tail.stop()
            tail.clearMediaItems()
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot stop the outgoing deck: ${e.message}")
        }
    }

    private fun currentUid(): Long {
        return primary.currentMediaItem?.let { QueueCommands.uidOf(it) } ?: 0L
    }

    private companion object {
        const val TAG = "DjCrossfader"
        const val WATCH_TICK_MS = 200L
        const val FADE_TICK_MS = 30L

        /** How long before the fade the outgoing deck is loaded. */
        const val WARMUP_MS = 3000L
        const val REWIND_SLACK_MS = 1500L
        const val EARLY_SLACK_MS = 40L
        const val LATE_SLACK_MS = 400L
        const val HANDOFF_TIMEOUT_MS = 300L
        const val IGNORE_JUMPS_MS = 800L
        const val MIN_FADE_MS = 1000L
        val HALF_PI = (PI / 2.0).toFloat()

        /** The primary player owns audio focus; the outgoing deck only plays while it does. */
        fun buildTail(context: Context): ExoPlayer {
            val attributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            return ExoPlayer.Builder(context)
                .setAudioAttributes(attributes, false)
                .setHandleAudioBecomingNoisy(false)
                .build()
        }
    }
}
