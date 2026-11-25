package me.Integer.Mediaplayer.ui

import android.animation.TimeAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.ui.widgets.IconPart
import me.Integer.Mediaplayer.ui.widgets.ListGlide
import me.Integer.Mediaplayer.ui.widgets.MorphCardView
import me.Integer.Mediaplayer.ui.widgets.MorphScene
import me.Integer.Mediaplayer.ui.widgets.ShadowSprite
import me.Integer.Mediaplayer.ui.widgets.Motion
import me.Integer.Mediaplayer.ui.widgets.Spring
import me.Integer.Mediaplayer.ui.widgets.TextPart
import me.Integer.Mediaplayer.ui.widgets.ViewGeometry
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The song launch: the tapped row becomes the mini player.
 *
 * The list glides to the row when it is off screen. The whole row lifts out as a card, hops,
 * and falls under gravity towards the mini player while morphing into it (size, corners,
 * colour, the cover becoming the round record, the text moving into place, the buttons
 * swapping). It lands on a bouncy spring, the real bar takes over underneath, and then the
 * bar grows into the full player.
 *
 * Each step waits for what it needs, with a timeout: the row to be laid out, its cover to be
 * loaded, and the bar to show the new song. Music starts as the row lifts. Main thread only.
 */
internal class SongLaunch(
    private val layer: FrameLayout,
    private val miniBar: MiniPlayerBar,
    private val host: PlayerHost,
    private val openPlayer: () -> Unit
) {

    private enum class Phase { IDLE, WAITING, FLYING, LANDED }

    private val context = layer.context
    private val density = context.resources.displayMetrics.density
    private val spots = MiniPlayerBar.Spots()
    private val squash = Spring(SQUASH_STIFFNESS, SQUASH_DAMPING)
    private val ticker = TimeAnimator()
    private val expandTask = Runnable { openPlayer() }

    private var phase = Phase.IDLE
    private var clock = 0L
    private var phaseStart = 0L
    private var glide: ListGlide? = null
    private var list: RecyclerView? = null
    private var row = RecyclerView.NO_POSITION
    private var trackUri = ""
    private var pendingPlay: (() -> Unit)? = null
    private var card: MorphCardView? = null
    private var fadingCard: View? = null
    private var hiddenRow: View? = null
    private var pendingTriggered = false

    // The flight path, in layer coordinates: card centres at the start, the top of the hop and the end.
    private var fromX = 0f
    private var fromY = 0f
    private var toX = 0f
    private var toY = 0f
    private var apexY = 0f
    private var tiltSign = 1f
    private var fallMs = FALL_MS
    private var flightMs = 0f
    private var shadow: ShadowSprite? = null
    private var shadowBuilt = false

    // The list's item animator while its change animations are paused for the flight.
    private var quietAnimator: SimpleItemAnimator? = null
    private var animatorHadChanges = false

    init {
        ticker.setTimeListener { _, totalTime, deltaTime -> onTick(totalTime, deltaTime) }
    }

    /** Builds the shared shadow and loads the launch icons ahead of time, so the tap frame does no setup work. */
    fun warmUp() {
        shadowSprite()
        // Loading once fills the resource cache; later loads only copy the cached state.
        for (res in WARM_ICONS) ContextCompat.getDrawable(context, res)
    }

    private fun shadowSprite(): ShadowSprite? {
        if (!shadowBuilt) {
            shadowBuilt = true
            shadow = ShadowSprite.create(SHADOW_BLUR_DP * density, SHADOW_HALO_DP * density, LIFT_CORNER_DP * density)
        }
        return shadow
    }

    /** Plays with the animation from [row] of [list]; a launch still running is replaced. */
    fun launch(list: RecyclerView, row: Int, play: () -> Unit) {
        cancel(runPending = false)
        pendingPlay = play
        pendingTriggered = false
        this.list = list
        this.row = row
        trackUri = (list.adapter as? TrackAdapter)?.rows()?.getOrNull(row)?.uri.orEmpty()
        if (Motion.durationScale(context) <= 0f || !list.isAttachedToWindow || layer.width == 0) {
            playAndOpen()
            return
        }
        if (ListGlide.isRowVisible(list, row)) {
            startWaiting()
            return
        }
        val started = ListGlide.start(list, row) {
            glide = null
            startWaiting()
        }
        if (started == null) startWaiting() else glide = started
    }

    /**
     * Ends everything at once and puts every view back.
     *
     * @param runPending true starts a song that had not started yet
     */
    fun cancel(runPending: Boolean) {
        glide?.cancel()
        glide = null
        ticker.end()
        phase = Phase.IDLE
        layer.removeCallbacks(expandTask)
        removeCards()
        hiddenRow?.let {
            it.animate().cancel()
            it.alpha = 1f
        }
        hiddenRow = null
        layer.isClickable = false
        miniBar.releaseHold()
        restoreList()
        list = null
        if (runPending) runPending() else pendingPlay = null
    }

    // ------------------------------------------------------------------ frame clock

    private fun restartClock() {
        ticker.end()
        clock = 0L
        phaseStart = 0L
        ticker.start()
    }

    private fun onTick(totalMs: Long, deltaMs: Long) {
        clock = totalMs
        val dt = (deltaMs / 1000f).coerceIn(0f, MAX_FRAME_SECONDS)
        when (phase) {
            Phase.WAITING -> waitForRow()
            Phase.FLYING -> fly(deltaMs)
            Phase.LANDED -> settle(dt)
            Phase.IDLE -> ticker.end()
        }
    }

    // ------------------------------------------------------------------ waiting

    private fun startWaiting() {
        phase = Phase.WAITING
        layer.isClickable = true
        restartClock()
    }

    /** Waits for the row to be laid out and, briefly, for its cover to finish loading. */
    private fun waitForRow() {
        val waited = clock - phaseStart
        val item = list?.findViewHolderForAdapterPosition(row)?.itemView
        if (item == null || !item.isLaidOut) {
            if (waited > ROW_WAIT_MS) giveUp()
            return
        }
        val art = item.findViewById<ImageView>(R.id.trackArt)?.drawable
        val coverReady = art == null || art is BitmapDrawable
        if (!coverReady && waited < COVER_WAIT_MS) return
        if (!beginFlight(item, (art as? BitmapDrawable)?.bitmap)) giveUp()
    }

    private fun giveUp() {
        ticker.end()
        phase = Phase.IDLE
        layer.isClickable = false
        playAndOpen()
    }

    // ------------------------------------------------------------------ flight

    private fun beginFlight(item: View, cover: Bitmap?): Boolean {
        val title = item.findViewById<TextView>(R.id.trackTitle) ?: return false
        val artist = item.findViewById<TextView>(R.id.trackArtist) ?: return false
        val thumb = item.findViewById<View>(R.id.trackThumb) ?: return false
        val rowRect = RectF()
        val thumbRect = RectF()
        val titleRect = RectF()
        val artistRect = RectF()
        val placed = ViewGeometry.rectIn(item, layer, rowRect) &&
            ViewGeometry.rectIn(thumb, layer, thumbRect) &&
            ViewGeometry.rectIn(title, layer, titleRect) &&
            ViewGeometry.rectIn(artist, layer, artistRect) &&
            miniBar.spotsIn(layer, spots, playing = true)
        if (!placed) return false
        val scene = MorphScene(
            fromCard = rowRect,
            toCard = spots.card,
            fromCorner = 0f,
            liftCorner = LIFT_CORNER_DP * density,
            toCorner = spots.corner,
            fromColor = ContextCompat.getColor(context, R.color.card),
            toColor = CoverTint.of(cover),
            fromBackgroundAlpha = 0f,
            fromCover = thumbRect,
            toCover = spots.disc,
            fromCoverCorner = THUMB_CORNER_DP * density,
            toCoverCorner = spots.disc.width() / 2f,
            cover = cover,
            coverPlaceholder = ContextCompat.getColor(context, R.color.round_button),
            title = textPart(title, titleRect, spots.title, spots.titleSize, spots.titleFace, spots.titleColor),
            artist = textPart(artist, artistRect, spots.artist, spots.artistSize, spots.artistFace, spots.artistColor),
            icons = icons(item),
            shadow = shadowSprite()
        )
        val view = MorphCardView(context, scene)
        layer.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        card = view
        miniBar.holdForLanding()
        item.animate().cancel()
        item.alpha = 0f
        hiddenRow = item
        fromX = rowRect.centerX()
        fromY = rowRect.centerY()
        toX = spots.card.centerX()
        toY = spots.card.centerY()
        apexY = fromY - HOP_DP * density
        tiltSign = if (toX < fromX) -1f else 1f
        // Longer drops get more time, so the speed on screen stays even.
        val dropDp = hypot(toX - fromX, toY - apexY) / density
        val scaled = FALL_MS * sqrt(dropDp / FALL_REF_DP)
        fallMs = if (scaled.isFinite()) scaled.coerceIn(FALL_MIN_MS, FALL_MAX_MS) else FALL_MS
        quietList()
        phase = Phase.FLYING
        phaseStart = clock
        flightMs = 0f
        fly(0L)
        return true
    }

    /** The row's line keeps its words; only its place, size and style change into the bar's. */
    private fun textPart(
        view: TextView,
        from: RectF,
        to: RectF,
        toSize: Float,
        toFace: Typeface,
        toColor: Int
    ): TextPart {
        val text = view.text?.toString().orEmpty()
        return TextPart(
            text, from, view.textSize, view.typeface ?: Typeface.DEFAULT, view.currentTextColor, false,
            text, to, toSize, toFace, toColor, false
        )
    }

    /** The row's buttons fade out as the bar's previous / pause / next fade in. */
    private fun icons(item: View): List<IconPart> {
        val parts = ArrayList<IconPart>(ICON_COUNT)
        val frame = RectF()
        val rowIconPx = ROW_ICON_DP * density
        item.findViewById<View>(R.id.trackPlayNext)?.let { button ->
            if (ViewGeometry.rectIn(button, layer, frame)) {
                IconPart.of(context, R.drawable.player_ic_play_next, frame, rowIconPx, ROW_ICON_COLOR, false)?.let(parts::add)
            }
        }
        item.findViewById<View>(R.id.trackMenu)?.let { button ->
            if (ViewGeometry.rectIn(button, layer, frame)) {
                IconPart.of(context, R.drawable.player_ic_more, frame, rowIconPx, ROW_ICON_COLOR, false)?.let(parts::add)
            }
        }
        IconPart.of(context, R.drawable.player_ic_previous, spots.previous, 0f, Color.WHITE, true)?.let(parts::add)
        IconPart.of(context, R.drawable.player_ic_pause, spots.play, 0f, Color.WHITE, true)?.let(parts::add)
        IconPart.of(context, R.drawable.player_ic_next, spots.next, 0f, Color.WHITE, true)?.let(parts::add)
        return parts
    }

    /**
     * Lift (scale, shadow, rounder corners), then a hop that eases out with a slight tilt,
     * then a fall that hangs and accelerates like gravity while the card morphs into the bar.
     */
    private fun fly(deltaMs: Long) {
        val view = card ?: return
        // Flight time advances by at most a frame or two per tick: a slow frame (the card being
        // built, a busy main thread) pauses the card for a moment instead of making it jump ahead.
        flightMs += deltaMs.coerceIn(0L, MAX_FLIGHT_STEP_MS).toFloat()
        val t = flightMs
        val lift = easeOut((t / LIFT_MS).coerceIn(0f, 1f))
        val hop = hopEase(((t - HOP_DELAY_MS) / HOP_MS).coerceIn(0f, 1f))
        val fall = ((t - HOP_DELAY_MS - HOP_MS) / fallMs).coerceIn(0f, 1f)
        val morph = smooth(((fall - MORPH_DELAY) / (1f - MORPH_DELAY)).coerceIn(0f, 1f))
        val x = lerp(fromX, toX, easeInOutSine(fall))
        val y = if (fall <= 0f) lerp(fromY, apexY, hop) else lerp(apexY, toY, gravity(fall))
        val scale = 1f + (LIFT_SCALE - 1f) * lift * (1f - fall)
        val tilt = TILT_DEGREES * tiltSign * hop * (1f - fall)
        view.update(
            morph,
            lift * (1f - LANDED_SHADOW_DROP * fall),
            x - lerp(fromX, toX, morph),
            y - lerp(fromY, toY, morph),
            scale,
            scale,
            tilt
        )
        if (!pendingTriggered && t >= PLAY_TRIGGER_DELAY_MS) {
            pendingTriggered = true
            runPending()
        }
        if (fall >= 1f) land()
    }

    // ------------------------------------------------------------------ landing

    private fun land() {
        if (!pendingTriggered) {
            pendingTriggered = true
            runPending()
        }
        phase = Phase.LANDED
        phaseStart = clock
        squash.reset(0f, IMPACT_VELOCITY, 0f)
    }

    /** Squash and settle; the landed card holds still until the real bar shows the new song. */
    private fun settle(dt: Float) {
        val view = card ?: return giveUp()
        squash.step(dt)
        val waited = clock - phaseStart
        if (waited > SQUASH_MAX_MS) squash.settle()
        val s = squash.value
        view.update(1f, LANDED_LIFT, 0f, 0f, 1f + SQUASH_X * s, 1f - SQUASH_Y * s, 0f)
        val settled = squash.isSettled
        val ready = isBarReady() || waited > BAR_WAIT_MS
        if (settled && ready) handOver()
    }

    private fun isBarReady(): Boolean {
        val state = host.nowPlaying
        if (!state.active) return false
        val uriMatches = trackUri.isEmpty() || state.trackUri == trackUri
        return uriMatches && host.isArtResolved
    }

    /** The real bar appears under the card, the card fades off it, and the row returns. */
    private fun handOver() {
        ticker.end()
        restoreList()
        phase = Phase.IDLE
        layer.isClickable = false
        miniBar.revealAfterLanding()
        hiddenRow?.animate()?.alpha(1f)?.setDuration(ROW_RETURN_MS)?.withLayer()?.start()
        hiddenRow = null
        list = null
        val view = card
        card = null
        if (view != null) {
            fadingCard = view
            // The landed card is static, so a hardware layer draws it once and only blends it while fading.
            view.animate().alpha(0f).setDuration(HANDOVER_FADE_MS).withLayer().withEndAction {
                layer.removeView(view)
                if (fadingCard === view) fadingCard = null
            }.start()
        }
        layer.postDelayed(expandTask, HANDOVER_TO_EXPAND_MS)
    }

    // ------------------------------------------------------------------ helpers

    private fun playAndOpen() {
        runPending()
        openPlayer()
    }

    private fun runPending() {
        val play = pendingPlay
        pendingPlay = null
        play?.invoke()
    }

    private fun removeCards() {
        card?.let { layer.removeView(it) }
        card = null
        fadingCard?.let {
            it.animate().cancel()
            layer.removeView(it)
        }
        fadingCard = null
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x)

    /**
     * Leaves at a brisk but not abrupt speed (half an ease-out-cubic's) and comes to rest at
     * the apex, so the fall, which also starts from rest, carries on without a corner.
     */
    private fun hopEase(x: Float): Float = x * (3f - x * x) / 2f

    private fun easeInOutSine(x: Float): Float = (-(cos(PI * x) - 1.0) / 2.0).toFloat()

    private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

    /** Hangs at the top and accelerates like gravity, but lands at about 70% of free-fall speed. */
    private fun gravity(x: Float): Float = x * x * (1f + GRAVITY_BLEND - GRAVITY_BLEND * x)

    /** While the row is in the air the list rebinds in place, so no second row is built and faded in under the card. */
    private fun quietList() {
        val animator = list?.itemAnimator as? SimpleItemAnimator ?: return
        if (quietAnimator != null) return
        animatorHadChanges = animator.supportsChangeAnimations
        animator.supportsChangeAnimations = false
        quietAnimator = animator
    }

    private fun restoreList() {
        quietAnimator?.supportsChangeAnimations = animatorHadChanges
        quietAnimator = null
    }

    private companion object {
        const val ROW_WAIT_MS = 300L
        const val COVER_WAIT_MS = 120L
        const val BAR_WAIT_MS = 220L
        const val SQUASH_MAX_MS = 340L
        const val HANDOVER_FADE_MS = 110L
        const val HANDOVER_TO_EXPAND_MS = 40L
        const val ROW_RETURN_MS = 200L
        const val MAX_FRAME_SECONDS = 0.05f
        const val PLAY_TRIGGER_DELAY_MS = 32f

        const val LIFT_MS = 140f
        const val HOP_DELAY_MS = 30f
        const val HOP_MS = 200f
        const val MAX_FLIGHT_STEP_MS = 40L

        // The lifted card's shared shadow.
        const val SHADOW_BLUR_DP = 8f
        const val SHADOW_HALO_DP = 16f
        const val FALL_MS = 430f
        const val FALL_REF_DP = 520f
        const val FALL_MIN_MS = 360f
        const val FALL_MAX_MS = 560f
        const val GRAVITY_BLEND = 0.6f
        const val MORPH_DELAY = 0.06f
        const val HOP_DP = 40f
        const val LIFT_SCALE = 1.04f
        const val TILT_DEGREES = 2.5f
        const val LANDED_SHADOW_DROP = 0.5f
        const val LANDED_LIFT = 0.5f

        // A bouncy landing: the card flattens, overshoots slightly tall, and settles.
        const val SQUASH_STIFFNESS = 500f
        const val SQUASH_DAMPING = 0.35f
        const val IMPACT_VELOCITY = 34f
        const val SQUASH_X = 0.05f
        const val SQUASH_Y = 0.11f

        const val LIFT_CORNER_DP = 18f
        const val THUMB_CORNER_DP = 10f
        const val ROW_ICON_DP = 22f
        const val ROW_ICON_COLOR = 0xFFA1A1A6.toInt()
        const val ICON_COUNT = 5
        val WARM_ICONS = intArrayOf(
            R.drawable.player_ic_play_next,
            R.drawable.player_ic_more,
            R.drawable.player_ic_previous,
            R.drawable.player_ic_pause,
            R.drawable.player_ic_next
        )
    }
}
