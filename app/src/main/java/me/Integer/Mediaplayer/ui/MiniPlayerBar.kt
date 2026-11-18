package me.Integer.Mediaplayer.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.playback.SkipOutcome
import me.Integer.Mediaplayer.ui.widgets.MarqueeTextView
import me.Integer.Mediaplayer.ui.widgets.PressFeedback
import me.Integer.Mediaplayer.ui.widgets.VinylDiscView
import me.Integer.Mediaplayer.ui.widgets.ViewGeometry

/**
 * The floating mini player: a rounded bar tinted by the cover, with a small spinning disc,
 * the song and previous / play / next. Tapping the bar opens the full player.
 *
 * It also takes part in the song launch: it can wait hidden while a cover falls towards it,
 * squash when the cover lands, and hand its bounds and colour to the card that grows into
 * the full player. Main thread only.
 */
class MiniPlayerBar(
    private val bar: View,
    private val host: PlayerHost,
    private val navigator: Navigator
) {

    private val density = bar.resources.displayMetrics.density
    private val vinyl: VinylDiscView = bar.findViewById(R.id.miniVinyl)
    private val title: MarqueeTextView = bar.findViewById(R.id.miniTitle)
    private val artist: TextView = bar.findViewById(R.id.miniArtist)
    private val playCell: View = bar.findViewById(R.id.miniPlay)
    private val playIcon: ImageView = bar.findViewById(R.id.miniPlayIcon)
    private val unknownTitle = bar.context.getString(R.string.player_unknown_title)
    private val unknownArtist = bar.context.getString(R.string.library_unknown_artist)
    private val playDescription = bar.context.getString(R.string.player_play)
    private val pauseDescription = bar.context.getString(R.string.player_pause)
    private val shape = GradientDrawable().apply {
        cornerRadius = CORNER_DP * density
        setColor(CoverTint.FALLBACK)
    }

    private var shownColor = CoverTint.FALLBACK
    private var tintedArt: Bitmap? = null
    private var tintApplied = false
    private var colorAnimator: ValueAnimator? = null
    private var suppressed = false
    private var shown = false
    private var iconPlaying: Boolean? = null

    /** A cover is falling towards the bar: a hidden bar waits for it, and the disc is hidden. */
    private var held = false

    /** The bar's corner radius, for the card that grows from it. */
    val cornerPx: Float
        get() = CORNER_DP * density

    /** The cover tint shown right now. */
    val color: Int
        get() = shownColor

    /** True while the bar can be seen. */
    val isOnScreen: Boolean
        get() = shown && bar.isShown && bar.alpha > 0f

    init {
        bar.background = shape
        bar.setOnClickListener { navigator.openNowPlaying() }
        val previousCell = bar.findViewById<View>(R.id.miniPrevious)
        val nextCell = bar.findViewById<View>(R.id.miniNext)
        PressFeedback.attach(previousCell, bar.findViewById(R.id.miniPreviousIcon))
        PressFeedback.attach(playCell, playIcon)
        PressFeedback.attach(nextCell, bar.findViewById(R.id.miniNextIcon))
        previousCell.setOnClickListener { skipBack() }
        playCell.setOnClickListener { togglePlayback() }
        nextCell.setOnClickListener { host.connection.next() }
    }

    /** Hidden while true, e.g. on the full player. */
    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        render()
    }

    fun render() {
        val state = host.nowPlaying
        val wanted = state.active && !suppressed
        setShown(wanted && (shown || !held))
        if (!state.active) {
            vinyl.setPlaying(false)
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
    }

    /** Stops everything that animates; [render] picks it up again. */
    fun stop() {
        vinyl.setPlaying(false)
        settlePlayIcon()
        colorAnimator?.end()
        colorAnimator = null
    }

    // ------------------------------------------------------------------ song launch

    /** The bar in the coordinates of [layer]. */
    fun boundsIn(layer: View, out: RectF): Boolean {
        return ViewGeometry.rectIn(bar, layer, out)
    }

    /** Where the disc is drawn when the bar is at rest, in the coordinates of [layer]. */
    fun discRectIn(layer: View, out: RectF): Boolean {
        if (!ViewGeometry.rectIn(vinyl, layer, out)) return false
        // A hidden bar waits lower down; the cover lands where the bar will rest.
        out.offset(0f, -bar.translationY)
        val inset = out.width() * (1f - DISC_SHARE) / 2f
        out.inset(inset, inset)
        return true
    }

    /** A cover is on its way: hide the disc, and keep a hidden bar hidden until it lands. */
    fun holdForLanding() {
        held = true
        vinyl.animate().cancel()
        vinyl.alpha = 0f
    }

    /** The launch was called off: everything goes back to normal. */
    fun releaseHold() {
        if (!held) return
        held = false
        vinyl.alpha = 1f
        render()
    }

    /** The cover landed: the bar appears at once if needed, squashes, and the disc pops. */
    fun land() {
        held = false
        vinyl.alpha = 1f
        val wanted = host.nowPlaying.active && !suppressed
        if (wanted && !shown) {
            shown = true
            bar.animate().cancel()
            bar.visibility = View.VISIBLE
            bar.alpha = 1f
            bar.translationY = 0f
        }
        render()
        if (shown) squash()
    }

    /** The card growing into the player covers the bar exactly, so the bar steps aside. */
    fun hideForExpand() {
        bar.alpha = 0f
    }

    /** The growing card was called off before the player opened. */
    fun restoreAfterExpand() {
        if (shown) bar.alpha = 1f
    }

    private fun squash() {
        bar.pivotX = bar.width / 2f
        bar.pivotY = bar.height.toFloat()
        bar.scaleX = SQUASH_X
        bar.scaleY = SQUASH_Y
        bar.animate().scaleX(1f).scaleY(1f)
            .setDuration(SQUASH_MS).setInterpolator(OvershootInterpolator(SQUASH_TENSION)).start()
        vinyl.scaleX = DISC_POP
        vinyl.scaleY = DISC_POP
        vinyl.animate().scaleX(1f).scaleY(1f)
            .setDuration(SQUASH_MS).setInterpolator(OvershootInterpolator(SQUASH_TENSION)).start()
    }

    // ------------------------------------------------------------------ actions

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
        val animate = iconPlaying != null && shown
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
        val animate = tintApplied
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

    /** Hidden means invisible rather than gone, so the bar keeps its place as a landing target. */
    private fun setShown(show: Boolean) {
        if (show == shown) return
        shown = show
        bar.animate().cancel()
        bar.scaleX = 1f
        bar.scaleY = 1f
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
        const val SQUASH_X = 1.04f
        const val SQUASH_Y = 0.9f
        const val SQUASH_MS = 360L
        const val SQUASH_TENSION = 2.5f
        const val DISC_POP = 1.25f

        /** The disc's drawn diameter as a share of its view (VinylDiscView's radius fraction × 2). */
        const val DISC_SHARE = 0.87f
    }
}
