package me.Integer.Mediaplayer.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.playback.SkipOutcome
import me.Integer.Mediaplayer.ui.widgets.MarqueeTextView
import me.Integer.Mediaplayer.ui.widgets.PressFeedback
import me.Integer.Mediaplayer.ui.widgets.VinylDiscView

/**
 * The floating mini player: a rounded bar tinted by the cover, with a small spinning disc,
 * the song and previous / play / next. Tapping the bar opens the full player. Main thread only.
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
        setShown(state.active && !suppressed)
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
        renderPlayIcon(state.playing)
    }

    /** Stops everything that animates; [render] picks it up again. */
    fun stop() {
        vinyl.setPlaying(false)
        colorAnimator?.end()
        colorAnimator = null
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

    private fun renderPlayIcon(playing: Boolean) {
        if (iconPlaying == playing) return
        iconPlaying = playing
        playIcon.setImageResource(if (playing) R.drawable.player_ic_pause else R.drawable.player_ic_play)
        playCell.contentDescription = if (playing) pauseDescription else playDescription
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

    private fun setShown(show: Boolean) {
        if (show == shown) return
        shown = show
        bar.animate().cancel()
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
                .withEndAction { if (!shown) bar.visibility = View.GONE }
                .start()
        }
    }

    private companion object {
        const val CORNER_DP = 34f
        const val HIDE_OFFSET_DP = 24f
        const val SHOW_MS = 220L
        const val HIDE_MS = 160L
        const val TINT_MS = 450L
    }
}
