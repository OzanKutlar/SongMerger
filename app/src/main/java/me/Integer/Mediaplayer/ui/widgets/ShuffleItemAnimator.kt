package me.Integer.Mediaplayer.ui.widgets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.random.Random

/**
 * Item animator for a list whose rows are reshuffled. It behaves like the default animator
 * until [arm] is called. For the one layout pass after that, rows do not fade: they slide
 * up or down towards their new seats at slightly different speeds and settle with a small
 * overshoot. Rows whose seat is off screen fly out in that direction, and rows coming from
 * off screen fly in from where they were.
 *
 * Only the rows on screen are animated, and only by translation, so the cost does not
 * depend on the length of the list. The system animation scale applies as usual.
 * Main thread only.
 */
class ShuffleItemAnimator(private val list: RecyclerView) : DefaultItemAnimator() {

    private class Flight(
        val holder: RecyclerView.ViewHolder,
        val endOffset: Float,
        val leaving: Boolean,
        val isActive: Boolean,
        val panBonusMs: Long,
        val panDown: Int
    )

    private val pending = ArrayList<Flight>()
    private val running = ArrayList<RecyclerView.ViewHolder>()
    private val random = Random(System.nanoTime())

    private var armed = false
    private var previousFirstVisible = 0
    private var previousIndexOf: (Long) -> Int = NOWHERE
    private var nextIndexOf: (Long) -> Int = NOWHERE
    private var activeUid = RecyclerView.NO_ID
    private var activeOldTop = Int.MIN_VALUE
    private var cameraDeltaPx = 0

    /**
     * Call right before notifying the adapter of a full reorder.
     *
     * @param previousFirstVisible the first row on screen before the reorder
     * @param previousIndexOf position of a stable id before the reorder, or -1
     * @param nextIndexOf position of a stable id after the reorder, or -1
     * @param activeUid stable id of the playing track, or [RecyclerView.NO_ID]
     * @param activeOldTop old screen top of the playing row, or [Int.MIN_VALUE] if unknown
     * @param cameraDeltaPx signed scroll pan in pixels (positive when scrolling down)
     */
    fun arm(
        previousFirstVisible: Int,
        previousIndexOf: (Long) -> Int,
        nextIndexOf: (Long) -> Int,
        activeUid: Long = RecyclerView.NO_ID,
        activeOldTop: Int = Int.MIN_VALUE,
        cameraDeltaPx: Int = 0
    ) {
        this.previousFirstVisible = maxOf(0, previousFirstVisible)
        this.previousIndexOf = previousIndexOf
        this.nextIndexOf = nextIndexOf
        this.activeUid = activeUid
        this.activeOldTop = activeOldTop
        this.cameraDeltaPx = cameraDeltaPx
        armed = true
    }

    /** Back to default behaviour for later changes. Flights already queued still run. */
    fun disarm() {
        armed = false
        previousIndexOf = NOWHERE
        nextIndexOf = NOWHERE
        activeUid = RecyclerView.NO_ID
        activeOldTop = Int.MIN_VALUE
        cameraDeltaPx = 0
    }

    // ------------------------------------------------------------------ hooks

    override fun animateDisappearance(
        viewHolder: RecyclerView.ViewHolder,
        preLayoutInfo: RecyclerView.ItemAnimator.ItemHolderInfo,
        postLayoutInfo: RecyclerView.ItemAnimator.ItemHolderInfo?
    ): Boolean {
        if (!armed) return super.animateDisappearance(viewHolder, preLayoutInfo, postLayoutInfo)
        return flyOut(viewHolder)
    }

    override fun animateAppearance(
        viewHolder: RecyclerView.ViewHolder,
        preLayoutInfo: RecyclerView.ItemAnimator.ItemHolderInfo?,
        postLayoutInfo: RecyclerView.ItemAnimator.ItemHolderInfo
    ): Boolean {
        if (!armed) return super.animateAppearance(viewHolder, preLayoutInfo, postLayoutInfo)
        if (preLayoutInfo != null) return slide(viewHolder, preLayoutInfo.top, postLayoutInfo.top)
        return flyIn(viewHolder, postLayoutInfo.top)
    }

    override fun animatePersistence(
        viewHolder: RecyclerView.ViewHolder,
        preInfo: RecyclerView.ItemAnimator.ItemHolderInfo,
        postInfo: RecyclerView.ItemAnimator.ItemHolderInfo
    ): Boolean {
        if (!armed) return super.animatePersistence(viewHolder, preInfo, postInfo)
        return slide(viewHolder, preInfo.top, postInfo.top)
    }

    /** After a full data change the list reports rows that stayed on screen as changes. */
    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder,
        newHolder: RecyclerView.ViewHolder,
        preInfo: RecyclerView.ItemAnimator.ItemHolderInfo,
        postInfo: RecyclerView.ItemAnimator.ItemHolderInfo
    ): Boolean {
        if (!armed || oldHolder !== newHolder) return super.animateChange(oldHolder, newHolder, preInfo, postInfo)
        return slide(newHolder, preInfo.top, postInfo.top)
    }

    // ------------------------------------------------------------------ planning

    /** A row that is on screen before and after: from its old seat to its new one. */
    private fun slide(holder: RecyclerView.ViewHolder, fromTop: Int, toTop: Int): Boolean {
        val view = holder.itemView
        val carried = view.translationY
        endAnimation(holder)
        val start = (fromTop - toTop) + carried
        if (start == 0f) {
            dispatchAnimationFinished(holder)
            return false
        }
        view.translationY = start
        val isActive = holder.itemId == activeUid && activeUid != RecyclerView.NO_ID
        pending.add(Flight(holder, 0f, false, isActive, panDurationBonus(), panDirection()))
        return true
    }

    /** A row whose old seat was off screen: it enters from that side. */
    private fun flyIn(holder: RecyclerView.ViewHolder, postTop: Int): Boolean {
        val view = holder.itemView
        endAnimation(holder)
        val isActive = holder.itemId == activeUid && activeUid != RecyclerView.NO_ID
        if (isActive && activeOldTop != Int.MIN_VALUE) {
            val start = (activeOldTop - postTop).toFloat()
            if (start == 0f) {
                dispatchAnimationFinished(holder)
                return false
            }
            view.translationY = start
            pending.add(Flight(holder, 0f, false, true, panDurationBonus(), panDirection()))
            return true
        }
        val fromAbove = cameFromAbove(holder.itemId)
        val extra = panTravelExtra()
        view.translationY = if (fromAbove) {
            aboveOffset(holder) - (if (cameraDeltaPx < 0) extra else 0f)
        } else {
            belowOffset(holder) + (if (cameraDeltaPx > 0) extra else 0f)
        }
        pending.add(Flight(holder, 0f, false, isActive, panDurationBonus(), panDirection()))
        return true
    }

    /** A row whose new seat is off screen: it leaves towards that side. */
    private fun flyOut(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        val carried = view.translationY
        endAnimation(holder)
        view.translationY = carried
        val goAbove = headsAbove(holder.itemId)
        val extra = panTravelExtra()
        val end = if (goAbove) {
            aboveOffset(holder) - (if (cameraDeltaPx > 0) extra else 0f)
        } else {
            belowOffset(holder) + (if (cameraDeltaPx < 0) extra else 0f)
        }
        pending.add(Flight(holder, end, true, false, panDurationBonus(), panDirection()))
        return true
    }

    private fun panDirection(): Int = when {
        cameraDeltaPx > 0 -> 1
        cameraDeltaPx < 0 -> -1
        else -> 0
    }

    private fun panTravelExtra(): Float {
        val height = list.height.takeIf { it > 0 } ?: return 0f
        val delta = kotlin.math.abs(cameraDeltaPx)
        if (delta <= height / 4) return 0f
        return minOf(delta * 0.45f, height * 0.75f)
    }

    private fun panDurationBonus(): Long {
        val height = list.height.takeIf { it > 0 } ?: return 0L
        val ratio = (kotlin.math.abs(cameraDeltaPx).toFloat() / height).coerceIn(0f, 1.5f)
        return (ratio * PAN_BONUS_MAX_MS).toLong()
    }

    private fun cameFromAbove(id: Long): Boolean {
        val seat = previousIndexOf(id)
        return seat in 0 until previousFirstVisible
    }

    private fun headsAbove(id: Long): Boolean {
        val seat = nextIndexOf(id)
        if (seat < 0) return false
        val manager = list.layoutManager as? LinearLayoutManager ?: return false
        val first = manager.findFirstVisibleItemPosition()
        return first != RecyclerView.NO_POSITION && seat < first
    }

    /** Translation that puts the row just above the list, a random bit further for variety. */
    private fun aboveOffset(holder: RecyclerView.ViewHolder): Float {
        val view = holder.itemView
        return -(view.bottom + random.nextInt(view.height + 1)).toFloat()
    }

    /** Translation that puts the row just below the list, a random bit further for variety. */
    private fun belowOffset(holder: RecyclerView.ViewHolder): Float {
        val view = holder.itemView
        return (list.height - view.top + random.nextInt(view.height + 1)).toFloat()
    }

    // ------------------------------------------------------------------ running

    override fun runPendingAnimations() {
        super.runPendingAnimations()
        disarm()
        if (pending.isEmpty()) return
        val flights = ArrayList(pending)
        pending.clear()
        for (flight in flights) launch(flight)
    }

    private fun launch(flight: Flight) {
        val holder = flight.holder
        running.add(holder)
        val duration = when {
            flight.leaving -> LEAVE_MIN_MS + flight.panBonusMs / 2L + random.nextLong(LEAVE_SPREAD_MS)
            flight.isActive -> ARRIVE_MIN_MS + flight.panBonusMs + ARRIVE_SPREAD_MS / 2L
            else -> ARRIVE_MIN_MS + flight.panBonusMs + random.nextLong(ARRIVE_SPREAD_MS)
        }
        val curve = when {
            flight.leaving -> AccelerateInterpolator(LEAVE_ACCELERATION)
            flight.isActive -> OvershootInterpolator(OVERSHOOT_ACTIVE)
            else -> OvershootInterpolator(OVERSHOOT_MIN + random.nextFloat() * OVERSHOOT_SPREAD)
        }
        holder.itemView.animate()
            .translationY(flight.endOffset)
            .setDuration(duration)
            .setInterpolator(curve)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    land(holder)
                }
            })
            .start()
    }

    /** Ends one flight: the row is put in its seat and handed back to the list. */
    private fun land(holder: RecyclerView.ViewHolder) {
        if (!running.remove(holder)) return
        val view = holder.itemView
        view.animate().setListener(null)
        view.translationY = 0f
        dispatchAnimationFinished(holder)
        if (!isRunning) dispatchAnimationsFinished()
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        val queued = pending.indexOfFirst { it.holder === item }
        if (queued >= 0) {
            pending.removeAt(queued)
            item.itemView.translationY = 0f
            dispatchAnimationFinished(item)
        }
        if (item in running) {
            item.itemView.animate().cancel()
            land(item) // no-op when cancelling already landed it
        }
        super.endAnimation(item)
    }

    override fun endAnimations() {
        for (flight in ArrayList(pending)) {
            flight.holder.itemView.translationY = 0f
            dispatchAnimationFinished(flight.holder)
        }
        pending.clear()
        for (holder in ArrayList(running)) {
            holder.itemView.animate().cancel()
            land(holder)
        }
        super.endAnimations()
    }

    override fun isRunning(): Boolean {
        return pending.isNotEmpty() || running.isNotEmpty() || super.isRunning()
    }

    private companion object {
        const val ARRIVE_MIN_MS = 420L
        const val ARRIVE_SPREAD_MS = 220L
        const val LEAVE_MIN_MS = 260L
        const val LEAVE_SPREAD_MS = 160L
        const val LEAVE_ACCELERATION = 1.2f

        const val PAN_BONUS_MAX_MS = 240L

        /** Overshoot tension; 0.5 to 0.8 overshoots by roughly one to two percent of the travel. */
        const val OVERSHOOT_ACTIVE = 0.35f
        const val OVERSHOOT_MIN = 0.5f
        const val OVERSHOOT_SPREAD = 0.3f

        val NOWHERE: (Long) -> Int = { -1 }
    }
}
