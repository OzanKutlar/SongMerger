package me.Integer.Mediaplayer.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Ping-pong dual-deck DJ mixer. Unlike a single-player handoff, a playing song never
 * switches decks mid-stream:
 *
 * 1. ARMED: 3.5 s before the outgoing deck's fade point, the idle deck loads the queue at
 *    the next track index, seeks to its clipped start with volume 0, and buffers to READY.
 * 2. FADING: at the fade point, the incoming deck starts playing and becomes the active
 *    session player. If the trigger ran late, the incoming deck starts as far into its song
 *    as the outgoing one has moved on, so their beats stay together. Its volume bleeds in
 *    rapidly while the outgoing deck finishes playing its tail and fades down to 0.
 * 3. FINISH: the outgoing deck stops and resets, while the incoming deck smoothly glides
 *    its playback speed back to 1.0x if it was tempo-matched.
 *
 * Every deck volume includes the item's loudness correction ([QueueCommands.gainOf]), so a
 * mashup keeps one level. Normal songs with a crossfade from the settings blend the same way.
 */
internal class DjCrossfader(
    deckA: ExoPlayer,
    deckB: ExoPlayer,
    private val onActiveDeckChanged: (ExoPlayer) -> Unit
) {

    private enum class Stage { IDLE, ARMED, FADING }

    private val main = Handler(Looper.getMainLooper())

    var activeDeck: ExoPlayer = deckA
        private set
    private var idleDeck: ExoPlayer = deckB

    private var stage = Stage.IDLE
    private var released = false
    private var ticking = false
    private var duckMultiplier = 1f

    private var outgoingUid = 0L
    private var incomingUid = 0L
    private var incomingIndex = -1
    private var initialTempo = 1f
    private var outgoingGain = 1f
    private var incomingGain = 1f

    private var fadeAtMs = 0L
    private var fadeLengthMs = 0L
    private var fadeStartedAt = 0L
    private var skippedUid = 0L

    private val tickTask = Runnable { onTick() }
    private val triggerTask = Runnable { trigger() }

    private val activeListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) abort()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && stage == Stage.IDLE) return
            abort()
            skippedUid = 0L
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                if (currentUid(activeDeck) != skippedUid) skippedUid = 0L
                // A song that starts without a blend still gets its own loudness correction.
                if (stage == Stage.IDLE) activeDeck.volume = restVolume(activeDeck)
            }
            schedule()
        }
    }

    private val idleListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "secondary deck error (${stage.name}): ${error.errorCodeName}")
            val uid = outgoingUid
            abort()
            skippedUid = uid
        }
    }

    init {
        activeDeck.addListener(activeListener)
        idleDeck.addListener(idleListener)
    }

    fun setDucking(ducking: Boolean) {
        duckMultiplier = if (ducking) DUCK_GAIN else 1f
        if (stage == Stage.IDLE) {
            activeDeck.volume = restVolume(activeDeck)
        }
    }

    /** Cancels any pending or running blend and restores the active deck to its volume. */
    fun abort() {
        main.removeCallbacks(triggerTask)
        if (stage == Stage.IDLE) return
        stage = Stage.IDLE
        stopDeck(idleDeck)
        activeDeck.volume = restVolume(activeDeck)
        resetSpeed(activeDeck)
        schedule()
    }

    fun release() {
        if (released) return
        abort()
        released = true
        main.removeCallbacks(tickTask)
        main.removeCallbacks(triggerTask)
        ticking = false
        activeDeck.removeListener(activeListener)
        idleDeck.removeListener(idleListener)
        activeDeck.release()
        idleDeck.release()
    }

    // ------------------------------------------------------------------ ticking

    private fun schedule() {
        if (released) return
        val needed = stage != Stage.IDLE || (activeDeck.isPlaying && plannedFadeMs() > 0L)
        if (!needed) {
            main.removeCallbacks(tickTask)
            ticking = false
            return
        }
        if (ticking) return
        ticking = true
        val delay = if (stage == Stage.FADING) FADE_TICK_MS else WATCH_TICK_MS
        main.postDelayed(tickTask, delay)
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
            Stage.FADING -> stepFade()
        }
        schedule()
    }

    // ------------------------------------------------------------------ arming

    private fun nextIndexOf(player: ExoPlayer): Int {
        val count = player.mediaItemCount
        if (count <= 1) return C.INDEX_UNSET
        val current = player.currentMediaItemIndex
        if (current + 1 < count) return current + 1
        return if (player.repeatMode == Player.REPEAT_MODE_ALL) 0 else C.INDEX_UNSET
    }

    private fun plannedFadeMs(): Long {
        val item = activeDeck.currentMediaItem ?: return 0L
        val uid = QueueCommands.uidOf(item)
        val crossfade = QueueCommands.crossfadeOf(item)
        if (uid == 0L || uid == skippedUid || crossfade < MIN_FADE_MS) return 0L
        if (activeDeck.repeatMode == Player.REPEAT_MODE_ONE) return 0L
        if (nextIndexOf(activeDeck) == C.INDEX_UNSET) return 0L
        val duration = activeDeck.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return 0L
        val fade = minOf(crossfade, duration / 2)
        return if (fade >= MIN_FADE_MS) fade else 0L
    }

    private fun considerArming() {
        if (!activeDeck.isPlaying) return
        val fade = plannedFadeMs()
        val item = activeDeck.currentMediaItem
        if (fade <= 0L || item == null) return
        val fadeAt = activeDeck.duration - fade
        val untilFade = fadeAt - activeDeck.currentPosition
        if (untilFade <= 0L) {
            skippedUid = QueueCommands.uidOf(item)
            return
        }
        if (untilFade <= WARMUP_MS) arm(item, fadeAt, fade)
    }

    private fun arm(outgoingItem: MediaItem, fadeAt: Long, fade: Long) {
        val uid = QueueCommands.uidOf(outgoingItem)
        val nextIdx = nextIndexOf(activeDeck)
        if (nextIdx == C.INDEX_UNSET) return
        val count = activeDeck.mediaItemCount
        val queueCopy = List(count) { activeDeck.getMediaItemAt(it) }
        val nextItem = queueCopy.getOrNull(nextIdx) ?: return
        val tempo = QueueCommands.tempoRatioOf(nextItem)
        try {
            idleDeck.volume = 0f
            idleDeck.playWhenReady = false
            idleDeck.repeatMode = activeDeck.repeatMode
            idleDeck.playbackParameters = PlaybackParameters(tempo, 1f)
            idleDeck.setMediaItems(queueCopy, nextIdx, 0L)
            idleDeck.prepare()
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot prepare incoming deck: ${e.message}")
            stopDeck(idleDeck)
            skippedUid = uid
            return
        }
        outgoingUid = uid
        incomingUid = QueueCommands.uidOf(nextItem)
        incomingIndex = nextIdx
        initialTempo = tempo
        outgoingGain = QueueCommands.gainOf(outgoingItem)
        incomingGain = QueueCommands.gainOf(nextItem)
        fadeAtMs = fadeAt
        fadeLengthMs = fade
        stage = Stage.ARMED
    }

    private fun watchArmed() {
        if (!activeDeck.playWhenReady || currentUid(activeDeck) != outgoingUid) {
            abort()
            return
        }
        val nextIdx = nextIndexOf(activeDeck)
        if (nextIdx != incomingIndex || nextIdx == C.INDEX_UNSET) {
            abort()
            return
        }
        val nextUid = QueueCommands.uidOf(activeDeck.getMediaItemAt(nextIdx))
        if (nextUid != incomingUid) {
            abort()
            return
        }
        val untilFade = fadeAtMs - activeDeck.currentPosition
        if (untilFade > WARMUP_MS + REWIND_SLACK_MS) {
            abort()
            return
        }
        if (untilFade > WATCH_TICK_MS) return
        main.removeCallbacks(triggerTask)
        main.postDelayed(triggerTask, untilFade.coerceAtLeast(0L))
    }

    // ------------------------------------------------------------------ trigger & crossfade

    private fun trigger() {
        if (stage != Stage.ARMED) return
        if (!activeDeck.playWhenReady || currentUid(activeDeck) != outgoingUid) {
            abort()
            return
        }
        val late = activeDeck.currentPosition - fadeAtMs
        if (late < -EARLY_SLACK_MS) return
        if (idleDeck.playbackState == Player.STATE_BUFFERING && late <= BUFFER_GRACE_MS) {
            main.postDelayed(triggerTask, FADE_TICK_MS)
            return
        }
        if (late > LATE_SLACK_MS || idleDeck.playbackState != Player.STATE_READY) {
            val uid = outgoingUid
            abort()
            skippedUid = uid
            return
        }
        catchUp(late)

        // Swap decks: incoming deck starts playing Song B and becomes the session player;
        // outgoing deck stays on Song A and plays out its fade tail without any seek or bump.
        activeDeck.removeListener(activeListener)
        idleDeck.removeListener(idleListener)

        val outgoing = activeDeck
        val incoming = idleDeck
        activeDeck = incoming
        idleDeck = outgoing

        activeDeck.addListener(activeListener)
        idleDeck.addListener(idleListener)

        stage = Stage.FADING
        fadeStartedAt = SystemClock.elapsedRealtime()
        applyGains(0f)
        activeDeck.play()
        onActiveDeckChanged(activeDeck)
        restartTicks()
    }

    /**
     * The outgoing song kept playing while the trigger ran late, so the incoming one starts
     * that much further in (at its own tempo), keeping the two beat grids together.
     */
    private fun catchUp(lateMs: Long) {
        if (lateMs <= PHASE_TOLERANCE_MS) return
        try {
            idleDeck.seekTo((lateMs * initialTempo).roundToLong())
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot advance the incoming deck: ${e.message}")
        }
    }

    private fun stepFade() {
        if (!activeDeck.playWhenReady || currentUid(activeDeck) != incomingUid) {
            abort()
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - fadeStartedAt
        val progress = (elapsed.toFloat() / fadeLengthMs.toFloat()).coerceIn(0f, 1f)
        val outState = idleDeck.playbackState
        val outDone = outState == Player.STATE_ENDED || outState == Player.STATE_IDLE
        if (progress >= 1f || outDone) {
            finish()
            return
        }
        applyGains(progress)
        applyTempoGlide(progress)
    }

    private fun finish() {
        stage = Stage.IDLE
        stopDeck(idleDeck)
        activeDeck.volume = restVolume(activeDeck)
        resetSpeed(activeDeck)
    }

    /**
     * Fast bleed-in DJ curve:
     * - Incoming rises with t^0.55 so its beat is clearly audible within the first second.
     * - Outgoing holds 1.0 for the first 15% of the overlap, then cosine-fades to 0.
     * Each deck is scaled by its song's loudness correction.
     */
    private fun applyGains(progress: Float) {
        val inGain = progress.pow(INCOMING_CURVE_EXP).coerceIn(0f, 1f)
        val outPhase = ((progress - OUTGOING_HOLD) / (1f - OUTGOING_HOLD)).coerceIn(0f, 1f)
        val outGain = cos(outPhase * HALF_PI).coerceIn(0f, 1f)
        activeDeck.volume = inGain * duckMultiplier * incomingGain
        idleDeck.volume = outGain * duckMultiplier * outgoingGain
    }

    /** Glides the incoming track's tempo back to 1.0x during the second half of the blend. */
    private fun applyTempoGlide(progress: Float) {
        if (initialTempo == 1f) return
        val t = ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val speed = initialTempo + (1f - initialTempo) * t
        activeDeck.playbackParameters = PlaybackParameters(speed, 1f)
    }

    /** The volume of a deck that is not blending: ducking times its song's correction. */
    private fun restVolume(player: ExoPlayer): Float {
        return duckMultiplier * QueueCommands.gainOf(player.currentMediaItem)
    }

    private fun resetSpeed(player: ExoPlayer) {
        if (player.playbackParameters.speed != 1f) {
            player.playbackParameters = PlaybackParameters.DEFAULT
        }
    }

    private fun stopDeck(deck: ExoPlayer) {
        try {
            deck.volume = 0f
            deck.stop()
            deck.clearMediaItems()
            resetSpeed(deck)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot stop deck: ${e.message}")
        }
    }

    private fun currentUid(player: ExoPlayer): Long {
        return player.currentMediaItem?.let { QueueCommands.uidOf(it) } ?: 0L
    }

    private companion object {
        const val TAG = "DjCrossfader"
        const val WATCH_TICK_MS = 160L
        const val FADE_TICK_MS = 25L

        const val WARMUP_MS = 3500L
        const val REWIND_SLACK_MS = 1500L
        const val EARLY_SLACK_MS = 35L
        const val BUFFER_GRACE_MS = 350L
        const val LATE_SLACK_MS = 600L
        const val MIN_FADE_MS = 1000L

        /** Lateness below this is inaudible against the beat; above it the incoming deck catches up. */
        const val PHASE_TOLERANCE_MS = 40L

        const val INCOMING_CURVE_EXP = 0.55f
        const val OUTGOING_HOLD = 0.15f
        const val DUCK_GAIN = 0.2f
        val HALF_PI = (PI / 2.0).toFloat()
    }
}
