package me.Integer.Mediaplayer.ui

import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.playback.NowPlaying
import me.Integer.Mediaplayer.playback.PlaybackClock
import me.Integer.Mediaplayer.playback.SkipOutcome
import me.Integer.Mediaplayer.ui.widgets.CoverAmbienceView
import me.Integer.Mediaplayer.ui.widgets.DiscGestures
import me.Integer.Mediaplayer.ui.widgets.MarqueeTextView
import me.Integer.Mediaplayer.ui.widgets.PressFeedback
import me.Integer.Mediaplayer.ui.widgets.ScrubMath
import me.Integer.Mediaplayer.ui.widgets.TrackTransition
import me.Integer.Mediaplayer.ui.widgets.VinylDiscView
import kotlin.math.abs

/**
 * Drives the full-screen player, ported from the car HUD media panel.
 *
 * Motion: press feedback on every control, optimistic play/pause with a morph and pulse,
 * directional text transitions, a record carousel and a progress glide on track changes,
 * and hold-and-turn scrubbing on the disc. Every animation is settled in [stop], so
 * nothing runs in the background. Main thread only.
 */
class NowPlayingController(
    root: View,
    private val host: PlayerHost,
    private val repository: LibraryRepository
) {

    private val context = root.context
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val unknownTitle = context.getString(R.string.player_unknown_title)
    private val unknownArtist = context.getString(R.string.library_unknown_artist)
    private val idleTitle = context.getString(R.string.player_idle)
    private val playDescription = context.getString(R.string.player_play)
    private val pauseDescription = context.getString(R.string.player_pause)
    private val thumbNormal: Drawable? = ContextCompat.getDrawable(context, R.drawable.player_seek_thumb)
    private val thumbPressed: Drawable? = ContextCompat.getDrawable(context, R.drawable.player_seek_thumb_pressed)

    private val ambience: CoverAmbienceView = root.findViewById(R.id.playerAmbience)
    private val vinyl: VinylDiscView = root.findViewById(R.id.playerVinyl)
    private val textBlock: View = root.findViewById(R.id.playerTextBlock)
    private val titleView: MarqueeTextView = root.findViewById(R.id.playerTitle)
    private val artistView: TextView = root.findViewById(R.id.playerArtist)
    private val elapsedView: TextView = root.findViewById(R.id.playerElapsed)
    private val durationView: TextView = root.findViewById(R.id.playerDuration)
    private val seekBar: SeekBar = root.findViewById(R.id.playerSeek)
    private val favoriteCell: View = root.findViewById(R.id.playerFavorite)
    private val favoriteIcon: ImageView = root.findViewById(R.id.playerFavoriteIcon)
    private val queueCell: View = root.findViewById(R.id.playerQueue)
    private val queueIcon: View = root.findViewById(R.id.playerQueueIcon)
    private val moreCell: View = root.findViewById(R.id.playerMore)
    private val moreIcon: View = root.findViewById(R.id.playerMoreIcon)
    private val shuffleCell: View = root.findViewById(R.id.playerShuffle)
    private val shuffleIcon: ImageView = root.findViewById(R.id.playerShuffleIcon)
    private val previousCell: View = root.findViewById(R.id.playerPrevious)
    private val previousIcon: View = root.findViewById(R.id.playerPreviousIcon)
    private val playCell: View = root.findViewById(R.id.playerPlay)
    private val playButton: View = root.findViewById(R.id.playerPlayButton)
    private val playIcon: ImageView = root.findViewById(R.id.playerPlayIcon)
    private val playPulse: View = root.findViewById(R.id.playerPlayPulse)
    private val nextCell: View = root.findViewById(R.id.playerNext)
    private val nextIcon: View = root.findViewById(R.id.playerNextIcon)
    private val repeatCell: View = root.findViewById(R.id.playerRepeat)
    private val repeatIcon: ImageView = root.findViewById(R.id.playerRepeatIcon)
    private val transition = TrackTransition(textBlock, density)

    private var dragging = false
    private var pendingSeekMs = -1L
    private var pendingSeekAt = 0L
    private var shownElapsedSecond = NO_VALUE
    private var shownDurationMs = NO_VALUE
    private var displayedUri: String? = null

    // Tap feedback state.
    private var playIconInitialised = false
    private var shownPlaying = false
    private var optimisticPlaying = false
    private var optimisticUntil = 0L
    private var lastSkipDirection = TrackTransition.DIRECTION_NEXT
    private var lastSkipAt = 0L
    private var shownRepeatMode = NO_MODE
    private var shownFavorite: Boolean? = null

    // Carousel: the track the record on screen belongs to, and a record waiting to enter.
    private var discKey: String? = null
    private var discExitDirection = TrackTransition.DIRECTION_NEXT
    private var discExitAt = 0L
    private var enterDirection = TrackTransition.DIRECTION_NEXT
    private var enterPendingSince = 0L

    private var shuffleColorInitialised = false
    private var shuffleTargetColor = COLOR_IDLE
    private var shuffleShownColor = COLOR_IDLE
    private var shuffleColorAnimator: ValueAnimator? = null
    private var progressGlide: ValueAnimator? = null

    // Disc scrubbing: position at the long press, rotation since then, last seek sent.
    private var scrubbing = false
    private var scrubBaseMs = 0L
    private var scrubDegrees = 0f
    private var scrubTargetMs = 0L
    private var scrubSentMs = 0L
    private var scrubSentAt = 0L
    private var scrubDetent = 0L
    private var scrubTrackUri = ""

    private val state: NowPlaying
        get() = host.nowPlaying

    private val tickTask = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private val applyLatestTrack = Runnable { applyTrackText(state) }

    private val morphInTask = Runnable {
        playIcon.setImageResource(playIconResource())
        playIcon.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(MORPH_HALF_MS).setInterpolator(DECELERATE).start()
    }

    private val seekListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser && dragging) previewSeek(progress)
        }

        override fun onStartTrackingTouch(bar: SeekBar?) {
            dragging = true
            cancelGlide()
            setSeekHighlight(true)
        }

        override fun onStopTrackingTouch(bar: SeekBar?) {
            dragging = false
            setSeekHighlight(false)
            commitSeekMs(PlaybackClock.fromPermille(seekBar.progress, state.durationMs))
        }
    }

    init {
        elapsedView.fontFeatureSettings = "tnum"
        durationView.fontFeatureSettings = "tnum"
        wireControls()
    }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        handler.removeCallbacks(tickTask)
        handler.postDelayed(tickTask, TICK_INTERVAL_MS)
        render()
    }

    fun stop() {
        handler.removeCallbacks(tickTask)
        dragging = false
        abortScrub()
        settleAnimations()
    }

    fun onPlayerChanged() {
        val current = state
        settlePendingSeek(current, SystemClock.elapsedRealtime())
        if (scrubbing && !canKeepScrubbing(current)) abortScrub()
        render()
    }

    /** Seek position, optimistic expiry, and records waiting for a cover or a track change. */
    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        val current = state
        returnDiscIfIgnored(now)
        if (!current.active) return
        if (scrubbing) sendScrubSeek(scrubTargetMs)
        renderProgress(current, now)
        renderDisc(current, now)
        if (optimisticUntil != 0L && now >= optimisticUntil) renderPlayState(current, now)
    }

    // ------------------------------------------------------------------ setup

    private fun wireControls() {
        PressFeedback.attach(favoriteCell, favoriteIcon)
        PressFeedback.attach(queueCell, queueIcon)
        PressFeedback.attach(moreCell, moreIcon)
        PressFeedback.attach(shuffleCell, shuffleIcon)
        PressFeedback.attach(previousCell, previousIcon)
        PressFeedback.attach(playCell, playButton)
        PressFeedback.attach(nextCell, nextIcon)
        PressFeedback.attach(repeatCell, repeatIcon)
        DiscGestures.attach(vinyl, object : DiscGestures.Callback {
            override fun onDrag(offsetPx: Float) = vinyl.setDragOffset(offsetPx)
            override fun onRelease(offsetPx: Float, commit: Boolean) = onDiscReleased(offsetPx, commit)
            override fun onScrubStart(): Boolean = startScrub()
            override fun onScrub(deltaDegrees: Float) = scrubBy(deltaDegrees)
            override fun onScrubEnd(commit: Boolean) = endScrub(commit)
        })
        favoriteCell.setOnClickListener { onFavoriteTapped() }
        playCell.setOnClickListener { onPlayPauseTapped() }
        vinyl.setOnClickListener { onPlayPauseTapped() }
        previousCell.setOnClickListener { onSkip(TrackTransition.DIRECTION_PREVIOUS, false) }
        nextCell.setOnClickListener { onSkip(TrackTransition.DIRECTION_NEXT, false) }
        shuffleCell.setOnClickListener { host.connection.toggleShuffle() }
        repeatCell.setOnClickListener { host.connection.cycleRepeat() }
        seekBar.max = PlaybackClock.PERMILLE_MAX
        seekBar.setOnSeekBarChangeListener(seekListener)
    }

    // ------------------------------------------------------------------ taps

    private fun onFavoriteTapped() {
        val current = state
        if (!current.active || current.trackUri.isEmpty()) return
        repository.toggleFavorite(current.trackUri)
        hop(favoriteIcon)
    }

    /** Optimistic: the icon, pulse and disc react at once; the player confirms later. */
    private fun onPlayPauseTapped() {
        val current = state
        if (!current.active) return
        val target = !shownPlaying
        val now = SystemClock.elapsedRealtime()
        optimisticPlaying = target
        optimisticUntil = now + OPTIMISTIC_WINDOW_MS
        pulsePlay()
        renderPlayState(current, now)
        if (target) host.connection.play() else host.connection.pause()
    }

    /**
     * Buttons and disc swipes. The record leaves at once (left for next, right for
     * previous) and the new one slides in when the track changes.
     *
     * The previous button restarts the song when it is more than a few seconds in: the
     * record spins back in place and the bar returns to 0:00. A swipe to the right always
     * goes back one track.
     *
     * @return false when there is nothing to skip to in that direction
     */
    private fun onSkip(direction: Int, fromSwipe: Boolean): Boolean {
        val current = state
        if (!current.active) return false
        val now = SystemClock.elapsedRealtime()
        val position = displayedPosition(current, now)
        return if (direction == TrackTransition.DIRECTION_NEXT) {
            skipNext(current, now)
        } else {
            skipPrevious(current, position, fromSwipe, now)
        }
    }

    private fun skipNext(current: NowPlaying, now: Long): Boolean {
        if (!current.canNext) return false
        rememberSkip(TrackTransition.DIRECTION_NEXT, now)
        exitDisc(TrackTransition.DIRECTION_NEXT, now)
        nudge(nextCell, TrackTransition.DIRECTION_NEXT)
        host.connection.next()
        return true
    }

    private fun skipPrevious(current: NowPlaying, position: Long, fromSwipe: Boolean, now: Long): Boolean {
        if (fromSwipe && !current.hasPreviousTrack) return false
        val restart = !current.hasPreviousTrack || (!fromSwipe && SkipOutcome.expectsRestart(position))
        rememberSkip(TrackTransition.DIRECTION_PREVIOUS, now)
        nudge(previousCell, TrackTransition.DIRECTION_PREVIOUS)
        if (restart) {
            showRestart(position, now)
            host.connection.restart()
        } else {
            exitDisc(TrackTransition.DIRECTION_PREVIOUS, now)
            host.connection.previousTrack()
        }
        return true
    }

    private fun rememberSkip(direction: Int, now: Long) {
        lastSkipDirection = direction
        lastSkipAt = now
    }

    private fun exitDisc(direction: Int, now: Long) {
        discExitDirection = direction
        discExitAt = now
        vinyl.exit(direction)
    }

    /** The record spins back in place and the bar returns to 0:00. */
    private fun showRestart(positionMs: Long, now: Long) {
        vinyl.rewind(SkipOutcome.rewindDegrees(positionMs))
        pendingSeekMs = 0L
        pendingSeekAt = now
        glideProgress(0)
        renderProgress(state, now)
    }

    /** Drag the record left for the next song, right for the previous one. */
    private fun onDiscReleased(offsetPx: Float, commit: Boolean) {
        if (commit && offsetPx != 0f) {
            val direction = if (offsetPx < 0f) TrackTransition.DIRECTION_NEXT else TrackTransition.DIRECTION_PREVIOUS
            if (onSkip(direction, true)) return
        }
        vinyl.releaseDrag()
    }

    /** Direction of a skip made in the last few seconds; automatic advances count as next. */
    private fun recentSkipDirection(now: Long): Int {
        val recent = lastSkipAt != 0L && now - lastSkipAt <= SKIP_DIRECTION_WINDOW_MS
        return if (recent) lastSkipDirection else TrackTransition.DIRECTION_NEXT
    }

    /** A skip that did not change the track (e.g. a one-song queue) brings the record back. */
    private fun returnDiscIfIgnored(now: Long) {
        if (discExitAt == 0L || now - discExitAt < DISC_RETURN_MS) return
        discExitAt = 0L
        if (vinyl.isAwaitingTrack && enterPendingSince == 0L) vinyl.enter(-discExitDirection)
    }

    // ------------------------------------------------------------------ disc scrubbing

    /** Long press on the disc: freezes the readout at the live position and follows the finger. */
    private fun startScrub(): Boolean {
        val current = state
        if (!current.active || !current.canSeek) return false
        if (dragging || vinyl.isAwaitingTrack) return false
        val now = SystemClock.elapsedRealtime()
        cancelGlide()
        scrubBaseMs = PlaybackClock.clamp(displayedPosition(current, now), current.durationMs)
        scrubDegrees = 0f
        scrubTargetMs = scrubBaseMs
        scrubSentMs = scrubBaseMs
        scrubSentAt = now
        scrubDetent = ScrubMath.detent(scrubBaseMs, SCRUB_DETENT_MS)
        scrubTrackUri = current.trackUri
        scrubbing = true
        setSeekHighlight(true)
        vinyl.setScrubbing(true)
        return true
    }

    /** Clockwise fast-forwards, counter-clockwise rewinds; one turn is 30 s. */
    private fun scrubBy(deltaDegrees: Float) {
        if (!scrubbing) return
        val duration = state.durationMs
        val previous = scrubDegrees
        scrubDegrees = ScrubMath.clampDegrees(scrubBaseMs, previous + deltaDegrees, duration)
        val applied = scrubDegrees - previous
        if (applied != 0f) vinyl.addScrubRotation(applied)
        val target = ScrubMath.target(scrubBaseMs, scrubDegrees, duration)
        showScrubPosition(target, duration)
        tickDetent(target)
        sendScrubSeek(target)
    }

    /** Finger lifted: one exact seek to where the disc was left. Cancelled: keep what was heard. */
    private fun endScrub(commit: Boolean) {
        if (!scrubbing) return
        val finalMs = if (commit) scrubTargetMs else scrubSentMs
        val moved = finalMs != scrubBaseMs || scrubSentMs != scrubBaseMs
        abortScrub()
        if (!moved || !commitSeekMs(finalMs)) {
            renderProgress(state, SystemClock.elapsedRealtime())
        }
    }

    /** Stops scrubbing without seeking any further (pause, track change, seeking lost). */
    private fun abortScrub() {
        if (!scrubbing) return
        scrubbing = false
        vinyl.setScrubbing(false)
        setSeekHighlight(false)
    }

    private fun canKeepScrubbing(next: NowPlaying): Boolean {
        return next.active && next.canSeek && next.trackUri == scrubTrackUri
    }

    private fun showScrubPosition(positionMs: Long, durationMs: Long) {
        scrubTargetMs = positionMs
        elapsedView.text = PlaybackClock.format(positionMs)
        shownElapsedSecond = NO_VALUE
        val progress = PlaybackClock.toPermille(positionMs, durationMs)
        if (seekBar.progress != progress) seekBar.progress = progress
    }

    private fun tickDetent(positionMs: Long) {
        val detent = ScrubMath.detent(positionMs, SCRUB_DETENT_MS)
        if (detent == scrubDetent) return
        scrubDetent = detent
        vinyl.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Audible scrubbing: at most one seek per interval, and only for a real move. */
    private fun sendScrubSeek(targetMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - scrubSentAt < SCRUB_SEEK_INTERVAL_MS) return
        if (abs(targetMs - scrubSentMs) < SCRUB_MIN_SEEK_STEP_MS) return
        scrubSentMs = targetMs
        scrubSentAt = now
        host.connection.seekTo(targetMs)
    }

    // ------------------------------------------------------------------ seeking

    private fun previewSeek(progress: Int) {
        elapsedView.text = PlaybackClock.format(PlaybackClock.fromPermille(progress, state.durationMs))
        shownElapsedSecond = NO_VALUE
    }

    /**
     * Seeks and holds the bar at the target until the player confirms.
     *
     * @return false when the player cannot seek right now
     */
    private fun commitSeekMs(targetMs: Long): Boolean {
        val current = state
        if (!current.active || !current.canSeek) return false
        val target = PlaybackClock.clamp(targetMs, current.durationMs)
        pendingSeekMs = target
        pendingSeekAt = SystemClock.elapsedRealtime()
        host.connection.seekTo(target)
        renderProgress(current, pendingSeekAt)
        return true
    }

    /** Holds the bar at the requested spot until the player confirms the seek. */
    private fun settlePendingSeek(next: NowPlaying, now: Long) {
        if (pendingSeekMs < 0L) return
        val confirmed = next.positionUpdatedAtMs >= pendingSeekAt &&
            abs(next.positionAt(now) - pendingSeekMs) <= SEEK_MATCH_TOLERANCE_MS
        if (confirmed || !next.active) pendingSeekMs = -1L
    }

    private fun displayedPosition(current: NowPlaying, now: Long): Long {
        if (pendingSeekMs >= 0L) {
            if (now - pendingSeekAt <= SEEK_SETTLE_WINDOW_MS) {
                return PlaybackClock.estimate(
                    pendingSeekMs, pendingSeekAt, current.speed, current.clockRunning, current.durationMs, now
                )
            }
            pendingSeekMs = -1L
        }
        return current.positionAt(now)
    }

    private fun setSeekHighlight(active: Boolean) {
        val thumb = if (active) thumbPressed else thumbNormal
        if (thumb != null) seekBar.thumb = thumb
        elapsedView.setTextColor(if (active) COLOR_ON else COLOR_TIME)
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        val now = SystemClock.elapsedRealtime()
        val current = state
        if (!current.active) {
            renderIdle()
            return
        }
        renderTrack(current, now)
        renderButtons(current)
        renderPlayState(current, now)
        renderProgress(current, now)
        renderDisc(current, now)
        if (host.isArtResolved) ambience.setArt(host.currentArt)
    }

    /** Nothing loaded: an empty record and disabled controls. */
    private fun renderIdle() {
        transition.finish()
        displayedUri = null
        discKey = null
        discExitAt = 0L
        enterPendingSince = 0L
        titleView.setText(idleTitle)
        artistView.text = ""
        vinyl.showInPlace()
        vinyl.setPlaying(false)
        vinyl.setArt(null)
        ambience.setArt(null)
        setUsable(favoriteCell, false)
        setUsable(queueCell, false)
        setUsable(moreCell, false)
        setUsable(previousCell, false)
        setUsable(nextCell, false)
        setUsable(playCell, false)
        setUsable(seekBar, false)
        seekBar.progress = 0
        elapsedView.text = PlaybackClock.format(0L)
        durationView.text = NO_TIME
        shownElapsedSecond = NO_VALUE
        shownDurationMs = NO_VALUE
    }

    /** New track: one coalesced text transition and a progress glide. */
    private fun renderTrack(current: NowPlaying, now: Long) {
        if (displayedUri == null) {
            applyTrackText(current)
            return
        }
        if (current.trackUri == displayedUri) {
            if (!transition.isFadingOut) applyTrackText(current)
            return
        }
        if (transition.isFadingOut) return
        transition.run(recentSkipDirection(now), applyLatestTrack)
        glideProgress(PlaybackClock.toPermille(current.positionAt(now), current.durationMs))
    }

    private fun applyTrackText(current: NowPlaying) {
        displayedUri = current.trackUri
        titleView.setText(current.title.ifEmpty { unknownTitle })
        val artist = repository.findTrack(current.trackUri)?.let { repository.displayArtist(it) }
            ?.ifEmpty { null }
            ?: current.artist.ifEmpty { unknownArtist }
        if (artistView.text.toString() != artist) artistView.text = artist
    }

    /**
     * Carousel: on a new track the old record slides out (left for next, right for previous)
     * and the new one slides in from the other side. After a tap or swipe the old record has
     * already left. The new record waits briefly for its cover so it does not enter blank.
     */
    private fun renderDisc(current: NowPlaying, now: Long) {
        val key = current.trackUri
        if (discKey == null) {
            discKey = key
            if (host.isArtResolved) vinyl.setArt(host.currentArt)
            return
        }
        if (key != discKey) {
            discKey = key
            discExitAt = 0L
            enterDirection = recentSkipDirection(now)
            enterPendingSince = now
            if (!vinyl.isAwaitingTrack) vinyl.exit(enterDirection)
        }
        if (enterPendingSince != 0L) {
            if (!host.isArtResolved && now - enterPendingSince < ART_WAIT_MS) return
            enterPendingSince = 0L
            vinyl.setArt(host.currentArt)
            vinyl.enter(enterDirection)
            return
        }
        if (host.isArtResolved) vinyl.setArt(host.currentArt)
    }

    private fun renderButtons(current: NowPlaying) {
        setUsable(favoriteCell, true)
        setUsable(queueCell, true)
        setUsable(moreCell, true)
        setUsable(previousCell, true)
        setUsable(nextCell, current.canNext)
        setUsable(playCell, true)
        setUsable(seekBar, current.canSeek)
        renderFavorite(current)
        renderShuffle(current)
        renderRepeat(current)
    }

    private fun renderFavorite(current: NowPlaying) {
        val fav = repository.isFavorite(current.trackUri)
        if (shownFavorite == fav) return
        shownFavorite = fav
        favoriteIcon.setImageResource(if (fav) R.drawable.player_ic_heart else R.drawable.player_ic_heart_outline)
        favoriteIcon.setColorFilter(if (fav) COLOR_ON else COLOR_IDLE)
    }

    /** Optimistic state wins until the player confirms it or the window runs out. */
    private fun renderPlayState(current: NowPlaying, now: Long) {
        val optimistic = optimisticUntil != 0L && now < optimisticUntil && current.playing != optimisticPlaying
        if (!optimistic) optimisticUntil = 0L
        renderPlayIcon(if (optimistic) optimisticPlaying else current.playing)
        vinyl.setPlaying(if (optimistic) optimisticPlaying else current.clockRunning)
    }

    private fun renderPlayIcon(playing: Boolean) {
        if (playIconInitialised && playing == shownPlaying) return
        val animate = playIconInitialised
        playIconInitialised = true
        shownPlaying = playing
        playCell.contentDescription = if (playing) pauseDescription else playDescription
        if (!animate) {
            resetPlayIcon()
            return
        }
        playIcon.animate().cancel()
        playIcon.animate().scaleX(MORPH_SCALE).scaleY(MORPH_SCALE).alpha(0f)
            .setDuration(MORPH_HALF_MS).setInterpolator(ACCELERATE)
            .withEndAction(morphInTask).start()
    }

    private fun playIconResource(): Int {
        return if (shownPlaying) R.drawable.player_ic_pause else R.drawable.player_ic_play
    }

    private fun resetPlayIcon() {
        playIcon.animate().cancel()
        playIcon.setImageResource(playIconResource())
        playIcon.scaleX = 1f
        playIcon.scaleY = 1f
        playIcon.alpha = 1f
    }

    private fun renderShuffle(current: NowPlaying) {
        val target = if (current.shuffleOn) COLOR_ON else COLOR_IDLE
        if (shuffleColorInitialised && target == shuffleTargetColor) return
        val animate = shuffleColorInitialised
        shuffleColorInitialised = true
        shuffleTargetColor = target
        shuffleColorAnimator?.cancel()
        shuffleColorAnimator = null
        if (!animate) {
            applyShuffleColor(target)
            return
        }
        val fade = ValueAnimator.ofArgb(shuffleShownColor, target)
        fade.duration = SHUFFLE_COLOR_MS
        fade.addUpdateListener { applyShuffleColor(it.animatedValue as Int) }
        shuffleColorAnimator = fade
        fade.start()
        hop(shuffleIcon)
    }

    private fun applyShuffleColor(color: Int) {
        shuffleShownColor = color
        shuffleIcon.setColorFilter(color)
    }

    private fun renderRepeat(current: NowPlaying) {
        val mode = current.repeatMode
        if (mode == shownRepeatMode) return
        val animate = shownRepeatMode != NO_MODE
        shownRepeatMode = mode
        val one = mode == NowPlaying.REPEAT_ONE
        repeatIcon.setImageResource(if (one) R.drawable.player_ic_repeat_one else R.drawable.player_ic_repeat)
        repeatIcon.setColorFilter(if (mode == NowPlaying.REPEAT_OFF) COLOR_IDLE else COLOR_ON)
        if (animate) hop(repeatIcon)
    }

    private fun renderProgress(current: NowPlaying, now: Long) {
        val duration = current.durationMs
        if (duration != shownDurationMs) {
            shownDurationMs = duration
            durationView.text = if (duration > 0L) PlaybackClock.format(duration) else NO_TIME
        }
        if (dragging || scrubbing) return
        val position = displayedPosition(current, now)
        val progress = PlaybackClock.toPermille(position, duration)
        val gliding = progressGlide?.isRunning == true
        if (!gliding && seekBar.progress != progress) seekBar.progress = progress
        val second = position / 1000L
        if (second == shownElapsedSecond) return
        shownElapsedSecond = second
        elapsedView.text = PlaybackClock.format(position)
    }

    // ------------------------------------------------------------------ small animations

    private fun pulsePlay() {
        playPulse.animate().cancel()
        playPulse.scaleX = 1f
        playPulse.scaleY = 1f
        playPulse.alpha = PULSE_START_ALPHA
        playPulse.animate().scaleX(PULSE_END_SCALE).scaleY(PULSE_END_SCALE).alpha(0f)
            .setDuration(PULSE_MS).setInterpolator(DECELERATE).start()
    }

    private fun nudge(view: View, direction: Int) {
        view.animate().translationX(direction * NUDGE_DP * density)
            .setDuration(NUDGE_HALF_MS).setInterpolator(DECELERATE)
            .withEndAction {
                view.animate().translationX(0f)
                    .setDuration(NUDGE_HALF_MS).setInterpolator(ACCELERATE).start()
            }.start()
    }

    private fun hop(view: View) {
        view.animate().translationY(-HOP_DP * density)
            .setDuration(HOP_UP_MS).setInterpolator(DECELERATE)
            .withEndAction {
                view.animate().translationY(0f)
                    .setDuration(HOP_DOWN_MS).setInterpolator(ACCELERATE).start()
            }.start()
    }

    private fun glideProgress(target: Int) {
        cancelGlide()
        val from = seekBar.progress
        if (dragging || from == target) return
        val glide = ValueAnimator.ofInt(from, target)
        glide.duration = PROGRESS_GLIDE_MS
        glide.interpolator = DECELERATE
        glide.addUpdateListener { seekBar.progress = it.animatedValue as Int }
        progressGlide = glide
        glide.start()
    }

    private fun cancelGlide() {
        progressGlide?.cancel()
        progressGlide = null
    }

    /** Jumps every running animation to its end state, so nothing animates in the background. */
    private fun settleAnimations() {
        transition.finish()
        cancelGlide()
        shuffleColorAnimator?.cancel()
        shuffleColorAnimator = null
        if (shuffleColorInitialised) applyShuffleColor(shuffleTargetColor)
        if (playIconInitialised) resetPlayIcon()
        playPulse.animate().cancel()
        playPulse.alpha = 0f
        for (control in listOf(favoriteCell, queueCell, moreCell, shuffleCell, previousCell, playCell, nextCell, repeatCell, seekBar)) {
            settleTransform(control)
            control.alpha = if (control.isEnabled) 1f else DISABLED_ALPHA
        }
        for (icon in listOf(favoriteIcon, queueIcon, moreIcon, shuffleIcon, previousIcon, nextIcon, repeatIcon, playButton, vinyl)) {
            settleTransform(icon)
        }
        if (enterPendingSince != 0L && host.isArtResolved) vinyl.setArt(host.currentArt)
        enterPendingSince = 0L
        vinyl.showInPlace()
        vinyl.setPlaying(false)
        discExitAt = 0L
        optimisticUntil = 0L
        setSeekHighlight(false)
    }

    private fun settleTransform(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    /** Enabling is immediate; the alpha change fades. */
    private fun setUsable(view: View, usable: Boolean) {
        if (view.isEnabled == usable) return
        view.isEnabled = usable
        view.animate().alpha(if (usable) 1f else DISABLED_ALPHA)
            .setDuration(ALPHA_FADE_MS).setInterpolator(DECELERATE).start()
    }

    private companion object {
        const val TICK_INTERVAL_MS = 250L
        const val SEEK_SETTLE_WINDOW_MS = 2000L
        const val SEEK_MATCH_TOLERANCE_MS = 3000L
        const val OPTIMISTIC_WINDOW_MS = 1500L
        const val SKIP_DIRECTION_WINDOW_MS = 3000L
        const val DISC_RETURN_MS = 2000L

        /** How long a new record waits for its cover before entering without it. */
        const val ART_WAIT_MS = 600L
        const val ALPHA_FADE_MS = 200L
        const val MORPH_HALF_MS = 90L
        const val PULSE_MS = 350L
        const val NUDGE_HALF_MS = 100L
        const val SHUFFLE_COLOR_MS = 250L
        const val HOP_UP_MS = 110L
        const val HOP_DOWN_MS = 140L
        const val PROGRESS_GLIDE_MS = 300L
        const val SCRUB_SEEK_INTERVAL_MS = 250L
        const val SCRUB_MIN_SEEK_STEP_MS = 250L

        /** A light haptic tick every this much playback time while scrubbing. */
        const val SCRUB_DETENT_MS = 5000L

        const val NUDGE_DP = 6f
        const val HOP_DP = 4f
        const val MORPH_SCALE = 0.4f
        const val PULSE_START_ALPHA = 0.8f
        const val PULSE_END_SCALE = 1.35f
        const val DISABLED_ALPHA = 0.35f

        const val COLOR_ON = 0xFF00E676.toInt()
        const val COLOR_IDLE = 0xFFECEFF1.toInt()
        const val COLOR_TIME = 0xFFCFD8DC.toInt()
        const val NO_VALUE = Long.MIN_VALUE
        const val NO_MODE = -1
        const val NO_TIME = "--:--"
        val ACCELERATE: Interpolator = AccelerateInterpolator()
        val DECELERATE: Interpolator = DecelerateInterpolator()
    }
}
