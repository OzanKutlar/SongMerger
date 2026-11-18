package me.Integer.Mediaplayer.ui

import android.animation.TimeAnimator
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.ui.widgets.IconPart
import me.Integer.Mediaplayer.ui.widgets.MarqueeTextView
import me.Integer.Mediaplayer.ui.widgets.MorphCardView
import me.Integer.Mediaplayer.ui.widgets.MorphScene
import me.Integer.Mediaplayer.ui.widgets.Motion
import me.Integer.Mediaplayer.ui.widgets.Spring
import me.Integer.Mediaplayer.ui.widgets.TextPart
import me.Integer.Mediaplayer.ui.widgets.ViewGeometry
import kotlin.math.min

/**
 * Grows the mini player into the full player as one piece: the bar stretches into the screen
 * (top edge leading, so it rises), the record flies to the big record's exact spot, the title
 * and artist move up and centre into the player's lines, the buttons fade, and the tint
 * deepens into the player's background. The player is measured off screen first, so every
 * piece lands where the real player draws it; then the player opens underneath and the card
 * fades off it. Waits briefly for the big cover. Main thread only.
 */
internal class PlayerExpand(
    private val layer: FrameLayout,
    private val container: ViewGroup,
    private val miniBar: MiniPlayerBar,
    private val host: PlayerHost
) {

    private enum class Phase { IDLE, WAITING, GROWING, REVEALING }

    private val context = layer.context
    private val backgroundColor = ContextCompat.getColor(context, R.color.background)
    private val spots = MiniPlayerBar.Spots()
    private val grow = Spring(GROW_STIFFNESS, GROW_DAMPING)
    private val ticker = TimeAnimator()

    // The player's layout, relative to the screen container; measured once per size.
    private val playerDisc = RectF()
    private val playerTitle = RectF()
    private val playerArtist = RectF()
    private var measuredWidth = 0
    private var measuredHeight = 0
    private var titleSize = 0f
    private var titleFace: Typeface = Typeface.DEFAULT
    private var titleColor = Color.WHITE
    private var artistSize = 0f
    private var artistFace: Typeface = Typeface.DEFAULT
    private var artistColor = Color.WHITE

    private var phase = Phase.IDLE
    private var clock = 0L
    private var phaseStart = 0L
    private var card: MorphCardView? = null
    private var commit: (() -> Boolean)? = null

    init {
        ticker.setTimeListener { _, totalTime, deltaTime -> onTick(totalTime, deltaTime) }
    }

    /** Pre-inflates and measures the target player layout once the screen is sized. */
    fun warmUp() {
        measurePlayer()
    }

    /**
     * @param commit opens the player once the card has become it; false if it could not
     * @return false when the bar is not on screen or the player cannot be measured; the
     *     caller then opens the player the plain way
     */
    fun expand(commit: () -> Boolean): Boolean {
        if (Motion.durationScale(context) <= 0f || !miniBar.isOnScreen) return false
        cancel()
        if (!measurePlayer()) return false
        if (!miniBar.spotsIn(layer, spots, host.nowPlaying.playing)) return false
        this.commit = commit
        layer.isClickable = true
        phase = Phase.WAITING
        ticker.end()
        clock = 0L
        phaseStart = 0L
        ticker.start()
        return true
    }

    /** Ends at once; a bar that had stepped aside comes back. */
    fun cancel() {
        val barHidden = card != null && phase == Phase.GROWING
        ticker.end()
        phase = Phase.IDLE
        card?.let {
            it.animate().cancel()
            layer.removeView(it)
        }
        card = null
        commit = null
        layer.isClickable = false
        if (barHidden) miniBar.restoreAfterExpand()
    }

    private fun onTick(totalMs: Long, deltaMs: Long) {
        clock = totalMs
        val dt = (deltaMs / 1000f).coerceIn(0f, MAX_FRAME_SECONDS)
        when (phase) {
            Phase.WAITING -> if (host.isArtResolved || clock - phaseStart >= ART_WAIT_MS) startGrowing()
            Phase.GROWING -> growStep(dt)
            Phase.REVEALING -> revealStep()
            Phase.IDLE -> ticker.end()
        }
    }

    private fun startGrowing() {
        val scene = buildScene()
        if (scene == null) {
            val open = commit
            cancel()
            open?.invoke()
            return
        }
        val view = MorphCardView(context, scene)
        layer.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        view.update(0f, 0f, 0f, 0f, 1f, 1f, 0f)
        card = view
        miniBar.hideForExpand()
        grow.reset(0f, 0f, 1f)
        phase = Phase.GROWING
        phaseStart = clock
    }

    private fun growStep(dt: Float) {
        val view = card ?: return cancel()
        grow.step(dt)
        view.update(grow.value, 0f, 0f, 0f, 1f, 1f, 0f)
        if (!grow.isSettled && clock - phaseStart < GROW_MAX_MS) return
        grow.settle()
        view.update(1f, 0f, 0f, 0f, 1f, 1f, 0f)
        val open = commit
        commit = null
        if (open == null || !open()) {
            cancel()
            return
        }
        phase = Phase.REVEALING
        phaseStart = clock
    }

    /** The player now sits exactly under the card; give it a moment to draw, then fade the card. */
    private fun revealStep() {
        val view = card ?: return finish()
        val fading = clock - phaseStart - REVEAL_DELAY_MS
        if (fading <= 0L) return
        val alpha = 1f - fading / REVEAL_FADE_MS
        if (alpha <= 0f) {
            finish()
            return
        }
        view.alpha = alpha
    }

    private fun finish() {
        ticker.end()
        phase = Phase.IDLE
        card?.let { layer.removeView(it) }
        card = null
        layer.isClickable = false
    }

    // ------------------------------------------------------------------ layouts

    private fun buildScene(): MorphScene? {
        val origin = RectF()
        if (!ViewGeometry.rectIn(container, layer, origin)) return null
        val disc = RectF(playerDisc).apply { offset(origin.left, origin.top) }
        val title = RectF(playerTitle).apply { offset(origin.left, origin.top) }
        val artist = RectF(playerArtist).apply { offset(origin.left, origin.top) }
        val icons = ArrayList<IconPart>(BAR_ICONS)
        val playIcon = if (spots.playing) R.drawable.player_ic_pause else R.drawable.player_ic_play
        IconPart.of(context, R.drawable.player_ic_previous, spots.previous, 0f, Color.WHITE, false)?.let(icons::add)
        IconPart.of(context, playIcon, spots.play, 0f, Color.WHITE, false)?.let(icons::add)
        IconPart.of(context, R.drawable.player_ic_next, spots.next, 0f, Color.WHITE, false)?.let(icons::add)
        return MorphScene(
            fromCard = spots.card,
            toCard = origin,
            fromCorner = spots.corner,
            liftCorner = spots.corner,
            toCorner = 0f,
            fromColor = spots.color,
            toColor = backgroundColor,
            fromBackgroundAlpha = 1f,
            fromCover = spots.disc,
            toCover = disc,
            fromCoverCorner = spots.disc.width() / 2f,
            toCoverCorner = disc.width() / 2f,
            cover = host.currentArt,
            coverPlaceholder = ContextCompat.getColor(context, R.color.round_button),
            title = TextPart(
                spots.titleText, spots.title, spots.titleSize, spots.titleFace, spots.titleColor, false,
                spots.titleText, title, titleSize, titleFace, titleColor, true
            ),
            artist = TextPart(
                spots.artistText, spots.artist, spots.artistSize, spots.artistFace, spots.artistColor, false,
                spots.artistText, artist, artistSize, artistFace, artistColor, true
            ),
            icons = icons,
            topLead = TOP_LEAD
        )
    }

    /**
     * Inflates the player once per screen size and lays it out off screen, to learn where its
     * record, title and artist will be drawn.
     */
    private fun measurePlayer(): Boolean {
        val width = container.width
        val height = container.height
        if (width <= 0 || height <= 0) return false
        if (width == measuredWidth && height == measuredHeight) return true
        val root = (try {
            LayoutInflater.from(context).inflate(R.layout.fragment_now_playing, container, false) as? ViewGroup
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot measure the player: ${e.message}")
            null
        }) ?: return false
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, width, height)
        val vinyl = root.findViewById<View>(R.id.playerVinyl)
        val title = root.findViewById<MarqueeTextView>(R.id.playerTitle)
        val artist = root.findViewById<TextView>(R.id.playerArtist)
        if (vinyl == null || title == null || artist == null) return false
        relativeRect(root, vinyl, playerDisc)
        // The record is drawn as a centred circle, resting smaller until it grows while playing.
        val diameter = min(playerDisc.width(), playerDisc.height()) * DISC_SHARE * DISC_RESTING
        val cx = playerDisc.centerX()
        val cy = playerDisc.centerY()
        playerDisc.set(cx - diameter / 2f, cy - diameter / 2f, cx + diameter / 2f, cy + diameter / 2f)
        relativeRect(root, title, playerTitle)
        relativeRect(root, artist, playerArtist)
        titleSize = title.textSizePx
        titleFace = title.textTypeface
        titleColor = title.textColor
        artistSize = artist.textSize
        artistFace = artist.typeface ?: Typeface.DEFAULT
        artistColor = artist.currentTextColor
        measuredWidth = width
        measuredHeight = height
        return true
    }

    private fun relativeRect(root: ViewGroup, child: View, out: RectF) {
        val bounds = Rect(0, 0, child.width, child.height)
        root.offsetDescendantRectToMyCoords(child, bounds)
        out.set(bounds)
    }

    private companion object {
        const val TAG = "PlayerExpand"
        const val GROW_STIFFNESS = 280f
        const val GROW_DAMPING = 1f
        const val GROW_MAX_MS = 750L
        const val ART_WAIT_MS = 120L
        const val REVEAL_DELAY_MS = 32L
        const val REVEAL_FADE_MS = 160f
        const val MAX_FRAME_SECONDS = 0.05f
        const val TOP_LEAD = 0.7f
        const val BAR_ICONS = 3

        /** VinylDiscView's disc diameter as a share of its view, and its size while resting. */
        const val DISC_SHARE = 0.87f
        const val DISC_RESTING = 0.84f
    }
}
