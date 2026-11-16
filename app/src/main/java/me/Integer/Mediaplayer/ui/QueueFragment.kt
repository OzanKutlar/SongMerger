package me.Integer.Mediaplayer.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.OneShotPreDrawListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.ui.widgets.PressFeedback
import me.Integer.Mediaplayer.ui.widgets.ShuffleItemAnimator

/**
 * Shows the current playback queue in exact upcoming order. Allows dragging rows to
 * reorder them, tapping a row to jump to it, removing rows, and toggling shuffle.
 * When the order is shuffled or restored, the rows slide to their new seats.
 */
class QueueFragment : Fragment(R.layout.fragment_queue), PlayerHost.Listener {

    private lateinit var host: PlayerHost
    private lateinit var adapter: QueueAdapter
    private lateinit var list: RecyclerView
    private lateinit var subtitleView: TextView
    private lateinit var emptyView: TextView
    private lateinit var shuffleButton: View
    private lateinit var shuffleIcon: ImageView
    private lateinit var shuffleMotion: ShuffleItemAnimator

    private var dragging = false
    private var dragFrom = -1
    private var dragTo = -1
    private var scrolledInitially = false
    private var shownShuffleOn: Boolean? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        host = requireActivity() as PlayerHost
        list = view.findViewById(R.id.queueList)
        subtitleView = view.findViewById(R.id.queueSubtitle)
        emptyView = view.findViewById(R.id.queueEmpty)
        shuffleButton = view.findViewById(R.id.queueShuffle)
        shuffleIcon = view.findViewById(R.id.queueShuffleIcon)
        shownShuffleOn = null
        view.findViewById<View>(R.id.queueBack).setOnClickListener { (requireActivity() as Navigator).goBack() }
        PressFeedback.attach(shuffleButton, shuffleIcon)
        shuffleButton.setOnClickListener { host.connection.toggleShuffle() }

        val touchHelper = createTouchHelper()
        adapter = QueueAdapter(
            thumbnails = ThumbnailLoader.get(requireContext()),
            unknownTitle = getString(R.string.player_unknown_title),
            unknownArtist = getString(R.string.library_unknown_artist),
            onStartDrag = { holder -> touchHelper.startDrag(holder) },
            onTap = { index -> host.connection.skipToQueueItem(index) },
            onRemove = { index -> host.connection.removeQueueItem(index) },
            onReorder = { previous, next, activeIndex -> prepareShuffleMotion(previous, next, activeIndex) }
        )
        shuffleMotion = ShuffleItemAnimator(list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.itemAnimator = shuffleMotion
        list.adapter = adapter
        touchHelper.attachToRecyclerView(list)
    }

    override fun onStart() {
        super.onStart()
        host.addPlayerListener(this)
        render()
    }

    override fun onStop() {
        host.removePlayerListener(this)
        shuffleMotion.disarm()
        super.onStop()
    }

    override fun onPlayerChanged() {
        if (!dragging) render()
    }

    private fun render() {
        val state = host.nowPlaying
        val queue = state.queue
        adapter.submit(queue, state.currentIndex)
        val emptyVisibility = if (queue.isEmpty()) View.VISIBLE else View.GONE
        if (emptyView.visibility != emptyVisibility) emptyView.visibility = emptyVisibility
        val subtitle = if (queue.isEmpty()) {
            getString(R.string.player_idle)
        } else {
            getString(R.string.queue_position, state.currentIndex + 1, queue.size)
        }
        if (subtitleView.text.toString() != subtitle) subtitleView.text = subtitle
        if (shownShuffleOn != state.shuffleOn) {
            shownShuffleOn = state.shuffleOn
            shuffleIcon.setColorFilter(if (state.shuffleOn) COLOR_ON else COLOR_IDLE)
        }
        if (!scrolledInitially && state.currentIndex in queue.indices) {
            scrolledInitially = true
            list.scrollToPosition(state.currentIndex)
        }
    }

    /**
     * Called by the adapter right before it shows a reordered queue. Arms the fly animation
     * for the coming layout pass and keeps the playing song where it is on screen, as far
     * as its new seat allows.
     */
    private fun prepareShuffleMotion(previous: LongArray, next: LongArray, activeIndex: Int) {
        val manager = list.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = maxOf(0, manager.findFirstVisibleItemPosition())
        shuffleMotion.arm(firstVisible, { uid -> previous.indexOf(uid) }, { uid -> next.indexOf(uid) })
        OneShotPreDrawListener.add(list) { shuffleMotion.disarm() }
        if (activeIndex !in next.indices) return
        val activeRow = list.findViewHolderForItemId(next[activeIndex])
        val offset = if (activeRow == null) 0 else maxOf(0, activeRow.itemView.top - list.paddingTop)
        manager.scrollToPositionWithOffset(activeIndex, offset)
    }

    private fun createTouchHelper(): ItemTouchHelper {
        return ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    dragging = true
                    dragFrom = viewHolder.bindingAdapterPosition
                    dragTo = dragFrom
                    viewHolder.itemView.alpha = DRAG_ALPHA
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.moveLocal(from, to)
                dragTo = to
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                val from = dragFrom
                val to = dragTo
                dragging = false
                dragFrom = -1
                dragTo = -1
                if (from >= 0 && to >= 0 && from != to) {
                    host.connection.moveQueueItem(from, to)
                } else {
                    render()
                }
            }
        })
    }

    private companion object {
        const val COLOR_ON = 0xFF00E676.toInt()
        const val COLOR_IDLE = 0xFFECEFF1.toInt()
        const val DRAG_ALPHA = 0.75f
    }
}
