package me.Integer.Mediaplayer.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.playback.NowPlaying
import me.Integer.Mediaplayer.playback.SkipOutcome
import me.Integer.Mediaplayer.ui.widgets.MarqueeTextView
import me.Integer.Mediaplayer.ui.widgets.MiniBarGestures
import me.Integer.Mediaplayer.ui.widgets.PressFeedback
import me.Integer.Mediaplayer.ui.widgets.ProgressLineDrawable
import me.Integer.Mediaplayer.ui.widgets.VinylDiscView
import me.Integer.Mediaplayer.ui.widgets.ViewGeometry

/**
 * The floating mini player: a rounded bar tinted by the cover, with a small spinning disc,
 * the song and previous / play / next. Tapping the bar opens the full player.
 *
 * It also takes part in the song launch: it reports where its pieces sit so a song row can
 * morph into it, stays hidden while that row is landing, and steps aside while it grows into
 * the full player. Main thread only.
 */
class MiniPlayerBar(
    private val bar: View,
    private val host: PlayerHost,
    private val navigator: Navigator
) {

    /** Where everything in the bar sits at rest, for animations that morph into or out of it. */
    class Spots {
        val card = RectF()
        val disc = RectF()
        val title = RectF()
        val artist = RectF()
        val previous = RectF()
        val play = RectF()
        val next = RectF()
        var corner = 0f
        var color = 0
        var titleText = ""
        var artistText = ""
        var titleSize = 0f
        var artistSize = 0f
        var titleFace: Typeface = Typeface.DEFAULT
        var artistFace: Typeface = Typeface.DEFAULT
        var titleColor = 0
        var artistColor = 0
        var playing = false
    }

    private val density = bar.resources.displayMetrics.density
    private val vinyl: VinylDiscView = bar.findViewById(R.id.miniVinyl)
    private val title: MarqueeTextView = bar.findViewById(R.id.miniTitle)
    private val artist: TextView = bar.findViewById(R.id.miniArtist)
    private val playCell: View = bar.findViewById(R.id.miniPlay)
    private val playIcon: ImageView = bar.findViewById(R.id.miniPlayIcon)
    private val previousIcon: View = bar.findViewById(R.id.miniPreviousIcon)
    private val nextIcon: View = bar.findViewById(R.id.miniNextIcon)
    private val unknownTitle = bar.context.getString(R.string.player_unknown_title)
    private val unknownArtist = bar.context.getString(R.string.library_unknown_artist)
    private val playDescription = bar.context.getString(R.string.player_play)
    private val pauseDescription = bar.context.getString(R.string.player_pause)
    private val shape = GradientDrawable().apply {
        cornerRadius = CORNER_DP * density
        setColor(CoverTint.FALLBACK)
    }

    private val progressLine = ProgressLineDrawable(density, CORNER_DP * density * PROGRESS_INSET_SHARE)
    private val progressTask = Runnable { onProgressTick() }

    private var shownColor = CoverTint.FALLBACK
    private var tintedArt: Bitmap? = null
    private var tintApplied = false
    private var colorAnimator: ValueAnimator? = null
    private var suppressed = false
    private var shown = false
    private var iconPlaying: Boolean? = null

    /** A song row is landing where the bar sits: the bar stays invisible and defers its work until the hand-over. */
    private var held = false

    /** True while the bar catches up after a hold: changes snap instead of animating. */
    private var snapping = false

    /** The bar's corner radius. */
    val cornerPx: Float
        get() = CORNER_DP * density

    /** True while the bar can be seen. */
    val isOnScreen: Boolean
        get() = shown && bar.isShown && bar.alpha > 0f

    init {
        bar.background = shape
        bar.foreground = progressLine
        bar.setOnClickListener { navigator.openNowPlaying() }
        val previousCell = bar.findViewById<View>(R.id.miniPrevious)
        val nextCell = bar.findViewById<View>(R.id.miniNext)
        PressFeedback.attach(previousCell, previousIcon)
        PressFeedback.attach(playCell, playIcon)
        PressFeedback.attach(nextCell, nextIcon)
        previousCell.setOnClickListener { skipBack() }
        playCell.setOnClickListener { togglePlayback() }
        nextCell.setOnClickListener { host.connection.next() }
        MiniBarGestures.attach(bar, object : MiniBarGestures.Callback {
            override fun onSwipeUp() = navigator.openNowPlaying()

            override fun onSwipeLeft() {
                if (host.nowPlaying.canNext) host.connection.next()
            }

            override fun onSwipeRight() = swipeBack()
        })
    }

    /** Hidden while true, e.g. on the full player. */
    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        render()
    }

    fun render() {
        val state = host.nowPlaying
        setShown(state.active && !suppressed)
        // A landing row covers the bar: text, art, tint and the disc wait for the hand-over,
        // so none of that work (or the layout pass it causes) lands in the middle of the fall.
        if (held) return
        if (!state.active) {
            vinyl.setPlaying(false)
            stopProgress()
            return
        }
        title.setText(state.title.ifEmpty { unknownTitle })
        val line = state.artist.ifEmpty { unknownArtist }
        if (artist.text.toString() != line) artist.text = line
        if (host.isArtResolved) {
            vinyl.setArt(host.currentArt)
            applyTint(host.currentArt)
        }
        vinyl.setPlaying(state.clockRunning && shown)
        vinyl.setExpanded(state.playing)
        renderPlayIcon(state.playing)
        renderProgress(state)
        scheduleProgress(shown && state.clockRunning)
    }

    /** Stops everything that animates; [render] picks it up again. */
    fun stop() {
        vinyl.setPlaying(false)
        bar.removeCallbacks(progressTask)
        settlePlayIcon()
        colorAnimator?.end()
        colorAnimator = null
    }

    // ------------------------------------------------------------------ morphs

    /**
     * Fills [out] with where the bar's pieces sit when the bar rests, in [layer] coordinates.
     *
     * @param playing the disc is drawn full size while playing and smaller while paused
     * @return false while the bar has no size yet
     */
    fun spotsIn(layer: View, out: Spots, playing: Boolean): Boolean {
        val placed = ViewGeometry.rectIn(bar, layer, out.card) &&
            ViewGeometry.rectIn(vinyl, layer, out.disc) &&
            ViewGeometry.rectIn(title, layer, out.title) &&
            ViewGeometry.rectIn(artist, layer, out.artist) &&
            ViewGeometry.rectIn(previousIcon, layer, out.previous) &&
            ViewGeometry.rectIn(playIcon, layer, out.play) &&
            ViewGeometry.rectIn(nextIcon, layer, out.next)
        if (!placed) return false
        // A hidden bar waits a little lower; report where it rests.
        val lift = -bar.translationY
        for (rect in arrayOf(out.card, out.disc, out.title, out.artist, out.previous, out.play, out.next)) {
            rect.offset(0f, lift)
        }
        val share = DISC_SHARE * (if (playing) 1f else DISC_RESTING)
        val inset = out.disc.width() * (1f - share) / 2f
        out.disc.inset(inset, inset)
        out.corner = cornerPx
        out.color = shownColor
        out.titleText = title.getText().toString()
        out.artistText = artist.text?.toString().orEmpty()
        out.titleSize = title.textSizePx
        out.titleFace = title.textTypeface
        out.titleColor = title.textColor
        out.artistSize = artist.textSize
        out.artistFace = artist.typeface ?: Typeface.DEFAULT
        out.artistColor = artist.currentTextColor
        out.playing = playing
        return true
    }

    /** A song row is about to land here: the bar disappears at once and waits. */
    fun holdForLanding() {
        held = true
        vinyl.setPlaying(false)
        bar.removeCallbacks(progressTask)
        bar.animate().cancel()
        bar.alpha = 0f
        bar.translationX = 0f
        bar.translationY = 0f
        bar.scaleX = 1f
        bar.scaleY = 1f
    }

    /** The launch was called off: a bar that should show fades back in. */
    fun releaseHold() {
        if (!held) return
        held = false
        catchUp()
        if (shown) bar.animate().alpha(1f).setDuration(SHOW_MS).setInterpolator(DecelerateInterpolator()).start()
    }

    /** The landed row hands over: the bar, already showing the new song, appears at once. */
    fun revealAfterLanding() {
        held = false
        catchUp()
        if (!shown) return
        bar.animate().cancel()
        bar.alpha = 1f
        bar.translationY = 0f
    }

    /** The card growing into the player starts exactly on the bar, so the bar steps aside. */
    fun hideForExpand() {
        bar.alpha = 0f
    }

    /** The growing card was called off before the player opened. */
    fun restoreAfterExpand() {
        if (shown && !held) bar.alpha = 1f
    }

    /** Renders everything deferred during a hold in one pass, snapping instead of animating. */
    private fun catchUp() {
        snapping = true
        try {
            render()
        } finally {
            snapping = false
        }
    }

    // ------------------------------------------------------------------ progress

    private fun renderProgress(state: NowPlaying) {
        val duration = state.durationMs
        val position = state.positionAt(SystemClock.elapsedRealtime())
        progressLine.setFraction(if (duration > 0L) position.toFloat() / duration else 0f)
    }

    /** While a song advances on screen the line moves twice a second; otherwise nothing runs. */
    private fun scheduleProgress(running: Boolean) {
        bar.removeCallbacks(progressTask)
        if (running) bar.postDelayed(progressTask, PROGRESS_TICK_MS)
    }

    private fun stopProgress() {
        bar.removeCallbacks(progressTask)
        progressLine.setFraction(0f)
    }

    private fun onProgressTick() {
        val state = host.nowPlaying
        if (!state.active) return
        renderProgress(state)
        scheduleProgress(shown && state.clockRunning)
    }

    // ------------------------------------------------------------------ actions

    /** A swipe always goes back one track when there is one, like swiping the big record. */
    private fun swipeBack() {
        val state = host.nowPlaying
        if (!state.active) return
        if (state.hasPreviousTrack) host.connection.previousTrack() else host.connection.restart()
    }

    /** Like most players: restart when a few seconds in, otherwise the previous song. */
    private fun skipBack() {
        val state = host.nowPlaying
        if (!state.active) return
        val position = state.positionAt(SystemClock.elapsedRealtime())
        if (!state.hasPreviousTrack || SkipOutcome.expectsRestart(position)) {
            host.connection.restart()
        } else {
            host.connection.previousTrack()
        }
    }

    private fun togglePlayback() {
        if (host.nowPlaying.playing) host.connection.pause() else host.connection.play()
    }

    // ------------------------------------------------------------------ rendering

    /** The icon shrinks away, swaps and grows back, like the big play button. */
    private fun renderPlayIcon(playing: Boolean) {
        if (iconPlaying == playing) return
        val animate = iconPlaying != null && shown && !held && !snapping
        iconPlaying = playing
        playCell.contentDescription = if (playing) pauseDescription else playDescription
        if (!animate) {
            settlePlayIcon()
            return
        }
        playIcon.animate().cancel()
        playIcon.animate().scaleX(MORPH_SCALE).scaleY(MORPH_SCALE).alpha(0f)
            .setDuration(MORPH_HALF_MS).setInterpolator(AccelerateInterpolator())
            .withEndAction { growPlayIcon() }.start()
    }

    private fun growPlayIcon() {
        val playing = iconPlaying ?: return
        playIcon.setImageResource(iconFor(playing))
        playIcon.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(MORPH_HALF_MS).setInterpolator(DecelerateInterpolator()).start()
    }

    /** Ends a running morph at once with the right icon at full size. */
    private fun settlePlayIcon() {
        playIcon.animate().cancel()
        val playing = iconPlaying ?: return
        playIcon.setImageResource(iconFor(playing))
        playIcon.scaleX = 1f
        playIcon.scaleY = 1f
        playIcon.alpha = 1f
    }

    private fun iconFor(playing: Boolean): Int {
        return if (playing) R.drawable.player_ic_pause else R.drawable.player_ic_play
    }

    private fun applyTint(art: Bitmap?) {
        if (tintApplied && art === tintedArt) return
        val animate = tintApplied && !held && !snapping
        tintApplied = true
        tintedArt = art
        val target = CoverTint.of(art)
        colorAnimator?.cancel()
        colorAnimator = null
        if (!animate) {
            setColor(target)
            return
        }
        val fade = ValueAnimator.ofArgb(shownColor, target)
        fade.duration = TINT_MS
        fade.addUpdateListener { setColor(it.animatedValue as Int) }
        colorAnimator = fade
        fade.start()
    }

    private fun setColor(color: Int) {
        shownColor = color
        shape.setColor(color)
    }

    /**
     * Hidden means invisible rather than gone, so the bar keeps its place as a landing target.
     * While a row is landing the bar changes state without showing itself.
     */
    private fun setShown(show: Boolean) {
        if (show == shown) return
        shown = show
        bar.animate().cancel()
        bar.translationX = 0f
        bar.scaleX = 1f
        bar.scaleY = 1f
        if (held) {
            bar.visibility = if (show) View.VISIBLE else View.INVISIBLE
            bar.alpha = 0f
            bar.translationY = 0f
            return
        }
        val offset = HIDE_OFFSET_DP * density
        if (show) {
            bar.visibility = View.VISIBLE
            bar.alpha = 0f
            bar.translationY = offset
            bar.animate().alpha(1f).translationY(0f)
                .setDuration(SHOW_MS).setInterpolator(DecelerateInterpolator()).start()
        } else {
            bar.animate().alpha(0f).translationY(offset)
                .setDuration(HIDE_MS).setInterpolator(AccelerateInterpolator())
                .withEndAction { if (!shown) bar.visibility = View.INVISIBLE }
                .start()
        }
    }

    private companion object {
        const val CORNER_DP = 34f
        const val HIDE_OFFSET_DP = 24f
        const val SHOW_MS = 220L
        const val HIDE_MS = 160L
        const val TINT_MS = 450L
        const val MORPH_SCALE = 0.4f
        const val MORPH_HALF_MS = 90L
        const val PROGRESS_TICK_MS = 500L

        /** The progress line keeps this share of the corner radius away from each end. */
        const val PROGRESS_INSET_SHARE = 0.75f

        /** VinylDiscView's disc diameter as a share of its view, and its size while paused. */
        const val DISC_SHARE = 0.87f
        const val DISC_RESTING = 0.84f
    }
}
