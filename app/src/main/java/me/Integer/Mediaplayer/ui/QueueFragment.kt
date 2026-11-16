package me.Integer.Mediaplayer.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.OneShotPreDrawListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track
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

    private lateinit var repository: LibraryRepository
    private lateinit var headerBar: View
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var selectionActions: List<View>
    private lateinit var favoriteAction: View
    private lateinit var favoriteIcon: ImageView
    private val selection = QueueSelection()
    private var swipePaint: Paint? = null
    private var swipeIcon: Drawable? = null

    /** While selecting, Back leaves selection mode instead of the screen. */
    private val selectionBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitSelection()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        host = requireActivity() as PlayerHost
        repository = LibraryRepository.get(requireContext())
        list = view.findViewById(R.id.queueList)
        subtitleView = view.findViewById(R.id.queueSubtitle)
        emptyView = view.findViewById(R.id.queueEmpty)
        shuffleButton = view.findViewById(R.id.queueShuffle)
        shuffleIcon = view.findViewById(R.id.queueShuffleIcon)
        shownShuffleOn = null
        view.findViewById<View>(R.id.queueBack).setOnClickListener { (requireActivity() as Navigator).goBack() }
        PressFeedback.attach(shuffleButton, shuffleIcon)
        shuffleButton.setOnClickListener { host.connection.toggleShuffle() }
        wireSelection(view)

        val touchHelper = createTouchHelper()
        adapter = QueueAdapter(
            thumbnails = ThumbnailLoader.get(requireContext()),
            unknownTitle = getString(R.string.player_unknown_title),
            unknownArtist = getString(R.string.library_unknown_artist),
            onStartDrag = { holder -> touchHelper.startDrag(holder) },
            selection = selection,
            onTap = { index -> onRowTapped(index) },
            onLongPress = { index -> enterSelection(index) },
            onRemove = { index -> host.connection.removeQueueItem(index) },
            onReorder = { previous, next, activeIndex -> prepareShuffleMotion(previous, next, activeIndex) }
        )
        shuffleMotion = ShuffleItemAnimator(list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.itemAnimator = shuffleMotion
        list.adapter = adapter
        touchHelper.attachToRecyclerView(list)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBack)
        renderSelectionMode()
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
        if (selection.isActive) {
            selection.retainOnly(adapter.currentUids())
            renderSelection()
        }
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
        val firstView = manager.findViewByPosition(firstVisible)
        val rowHeight = firstView?.height?.takeIf { it > 0 }
            ?: (DEFAULT_ROW_DP * resources.displayMetrics.density).toInt()
        val viewportHeight = maxOf(rowHeight, list.height - list.paddingTop - list.paddingBottom)
        val activeUid = if (activeIndex in next.indices) next[activeIndex] else RecyclerView.NO_ID
        val activeRow = if (activeUid != RecyclerView.NO_ID) list.findViewHolderForItemId(activeUid) else null
        val oldActiveIdx = if (activeUid != RecyclerView.NO_ID) previous.indexOf(activeUid) else -1
        val activeOldTop = when {
            activeRow != null -> activeRow.itemView.top
            oldActiveIdx in 0 until firstVisible -> list.paddingTop - rowHeight
            oldActiveIdx >= 0 -> list.height - list.paddingBottom
            else -> Int.MIN_VALUE
        }
        val oldOffset = firstView?.let { it.top - list.paddingTop } ?: 0
        val oldScrollPx = firstVisible * rowHeight - oldOffset
        val targetOffset = maxOf(0, (viewportHeight - rowHeight) / 2)
        val approxNewScrollPx = if (activeIndex in next.indices) {
            maxOf(0, activeIndex * rowHeight - targetOffset)
        } else {
            oldScrollPx
        }
        val cameraDeltaPx = approxNewScrollPx - oldScrollPx

        shuffleMotion.arm(
            previousFirstVisible = firstVisible,
            previousIndexOf = { uid -> previous.indexOf(uid) },
            nextIndexOf = { uid -> next.indexOf(uid) },
            activeUid = activeUid,
            activeOldTop = activeOldTop,
            cameraDeltaPx = cameraDeltaPx
        )
        OneShotPreDrawListener.add(list) { shuffleMotion.disarm() }
        if (activeIndex in next.indices) {
            manager.scrollToPositionWithOffset(activeIndex, targetOffset)
        }
    }

    // ------------------------------------------------------------------ menu & saving

    private fun wireSelection(view: View) {
        headerBar = view.findViewById(R.id.queueHeader)
        selectionBar = view.findViewById(R.id.queueSelectionBar)
        selectionCount = view.findViewById(R.id.queueSelectionCount)
        favoriteAction = view.findViewById(R.id.queueSelectionFavorite)
        favoriteIcon = view.findViewById(R.id.queueSelectionFavoriteIcon)
        val addToPlaylist = view.findViewById<View>(R.id.queueSelectionPlaylist)
        val remove = view.findViewById<View>(R.id.queueSelectionRemove)
        selectionActions = listOf(addToPlaylist, favoriteAction, remove)
        view.findViewById<View>(R.id.queueMore).setOnClickListener { showMenu(it) }
        view.findViewById<View>(R.id.queueSelectionClose).setOnClickListener { exitSelection() }
        view.findViewById<View>(R.id.queueSelectAll).setOnClickListener { selectAll() }
        addToPlaylist.setOnClickListener { addSelectedToPlaylist() }
        favoriteAction.setOnClickListener { toggleSelectedFavorites() }
        remove.setOnClickListener { removeSelected() }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, ID_SAVE, 0, R.string.queue_save_playlist)
        popup.menu.add(0, ID_SELECT, 1, R.string.queue_select)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_SAVE -> saveQueue()
                ID_SELECT -> enterSelection(RecyclerView.NO_POSITION)
            }
            true
        }
        popup.show()
    }

    /** Every queued song that is in the library, once each, in queue order. */
    private fun saveQueue() {
        val tracks = tracksOf(host.nowPlaying.queue.map { it.trackUri })
        if (tracks.isEmpty()) {
            toast(getString(R.string.queue_nothing_to_save))
            return
        }
        PlaylistDialogs.addTracks(requireContext(), repository, tracks, R.string.queue_save_playlist)
    }

    private fun tracksOf(uris: List<String>): List<Track> {
        return uris.asSequence().distinct().mapNotNull { repository.findTrack(it) }.toList()
    }

    // ------------------------------------------------------------------ selection

    private fun onRowTapped(position: Int) {
        if (!selection.isActive) {
            host.connection.skipToQueueItem(position)
            return
        }
        val uid = adapter.uidAt(position) ?: return
        selection.toggle(uid)
        adapter.refreshRow(position)
        renderSelection()
    }

    /** @param position a row to select right away, or NO_POSITION for none */
    private fun enterSelection(position: Int) {
        val wasActive = selection.isActive
        selection.start()
        adapter.uidAt(position)?.let { uid -> if (!selection.contains(uid)) selection.toggle(uid) }
        renderSelectionMode()
        if (wasActive) adapter.refreshRow(position) else adapter.refreshSelection()
    }

    private fun exitSelection() {
        if (!selection.isActive) return
        selection.stop()
        renderSelectionMode()
        adapter.refreshSelection()
    }

    /** Selects every row, or clears the selection when every row is already selected. */
    private fun selectAll() {
        val uids = adapter.currentUids()
        if (uids.isNotEmpty() && selection.size >= uids.size) selection.clear() else selection.selectAll(uids)
        adapter.refreshSelection()
        renderSelection()
    }

    /** Shows the normal header or the selection bar to match the mode. */
    private fun renderSelectionMode() {
        val active = selection.isActive
        selectionBack.isEnabled = active
        headerBar.visibility = if (active) View.GONE else View.VISIBLE
        selectionBar.visibility = if (active) View.VISIBLE else View.GONE
        if (active) renderSelection()
    }

    private fun renderSelection() {
        val count = selection.size
        selectionCount.text = resources.getQuantityString(R.plurals.queue_selected, count, count)
        val usable = count > 0
        for (action in selectionActions) {
            action.isEnabled = usable
            action.alpha = if (usable) 1f else DISABLED_ALPHA
        }
        val allFavorite = usable && allSelectedFavorite()
        favoriteIcon.setImageResource(if (allFavorite) R.drawable.player_ic_heart else R.drawable.player_ic_heart_outline)
        favoriteIcon.setColorFilter(if (allFavorite) COLOR_ON else COLOR_IDLE)
        favoriteAction.contentDescription =
            getString(if (allFavorite) R.string.menu_remove_favorite else R.string.menu_add_favorite)
    }

    /** The songs of the selected rows, each once, in queue order. */
    private fun selectedUris(): List<String> {
        return selection.positionsIn(adapter.currentUids())
            .mapNotNull { adapter.trackUriAt(it) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun allSelectedFavorite(): Boolean {
        val uris = selectedUris()
        if (uris.isEmpty()) return false
        val favorites = repository.favorites.all()
        return uris.all { it in favorites }
    }

    private fun removeSelected() {
        val positions = selection.positionsIn(adapter.currentUids())
        if (positions.isEmpty()) return
        host.connection.removeQueueRanges(QueueSelection.removalRuns(positions))
        toast(resources.getQuantityString(R.plurals.queue_removed, positions.size, positions.size))
        exitSelection()
    }

    private fun addSelectedToPlaylist() {
        val tracks = tracksOf(selectedUris())
        if (tracks.isEmpty()) {
            toast(getString(R.string.queue_nothing_to_save))
            return
        }
        PlaylistDialogs.addTracks(requireContext(), repository, tracks)
        exitSelection()
    }

    /** Unfavorites the selection when every song is a favorite, otherwise favorites them all. */
    private fun toggleSelectedFavorites() {
        val uris = selectedUris()
        if (uris.isEmpty()) return
        val message = if (allSelectedFavorite()) {
            val removed = repository.removeFavorites(uris)
            resources.getQuantityString(R.plurals.queue_unfavorited, removed, removed)
        } else {
            val added = repository.addFavorites(uris)
            resources.getQuantityString(R.plurals.queue_favorited, added, added)
        }
        toast(message)
        renderSelection()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ swipe to remove

    /** The red strip and trash icon revealed behind a row swiped to the left. */
    private fun drawSwipeBackground(canvas: Canvas, row: View, dX: Float) {
        val paint = swipePaint ?: Paint().apply { color = SWIPE_COLOR }.also { swipePaint = it }
        canvas.drawRect(row.right + dX, row.top.toFloat(), row.right.toFloat(), row.bottom.toFloat(), paint)
        var icon = swipeIcon
        if (icon == null) {
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.player_ic_delete)?.mutate() ?: return
            icon.setTint(Color.WHITE)
            swipeIcon = icon
        }
        val density = resources.displayMetrics.density
        val size = (SWIPE_ICON_DP * density).toInt()
        val margin = (SWIPE_MARGIN_DP * density).toInt()
        if (-dX < size + margin) return
        val right = row.right - margin
        val top = row.top + (row.height - size) / 2
        icon.setBounds(right - size, top, right, top + size)
        icon.draw(canvas)
    }

    private fun createTouchHelper(): ItemTouchHelper {
        return ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT
        ) {
            // Long-press selects rows; dragging starts from the handle.
            override fun isLongPressDragEnabled(): Boolean = false

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

            /** No swiping while selecting, so a stray gesture cannot remove songs. */
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (selection.isActive) 0 else super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                adapter.removeLocal(position)
                host.connection.removeQueueItem(position)
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0f) {
                    drawSwipeBackground(c, viewHolder.itemView, dX)
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

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
                } else if (from >= 0) {
                    render()
                }
            }
        })
    }

    private companion object {
        const val COLOR_ON = 0xFF00E676.toInt()
        const val COLOR_IDLE = 0xFFECEFF1.toInt()
        const val DRAG_ALPHA = 0.75f
        const val DEFAULT_ROW_DP = 76f
        const val DISABLED_ALPHA = 0.35f
        const val SWIPE_COLOR = 0xFFD32F2F.toInt()
        const val SWIPE_ICON_DP = 24f
        const val SWIPE_MARGIN_DP = 24f
        const val ID_SAVE = 1
        const val ID_SELECT = 2
    }
}
