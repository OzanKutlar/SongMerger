package me.Integer.Mediaplayer.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.OneShotPreDrawListener
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.ui.widgets.ShuffleItemAnimator

/**
 * Drives the inline playback queue inside the Now Playing screen.
 *
 * Fades the vinyl disc out and reveals the queue in its exact space while keeping the
 * bottom seek bar and transport controls fixed. Supports scrolling, tapping to play,
 * drag-handle reordering, swipe-to-remove, shuffle animations, and multi-selection.
 */
class NowPlayingQueueController(
    root: View,
    private val host: PlayerHost,
    private val repository: LibraryRepository,
    private val onStateChanged: () -> Unit
) {

    private val context = root.context
    private val resources = context.resources

    private val discStage: View = root.findViewById(R.id.playerDiscStage)
    private val queueStage: View = root.findViewById(R.id.playerQueueStage)
    private val queueIcon: ImageView = root.findViewById(R.id.playerQueueIcon)

    private val list: RecyclerView = root.findViewById(R.id.queueList)
    private val subtitleView: TextView = root.findViewById(R.id.queueSubtitle)
    private val emptyView: TextView = root.findViewById(R.id.queueEmpty)
    private val headerBar: View = root.findViewById(R.id.queueHeader)
    private val selectionBar: View = root.findViewById(R.id.queueSelectionBar)
    private val selectionCount: TextView = root.findViewById(R.id.queueSelectionCount)
    private val favoriteAction: View = root.findViewById(R.id.queueSelectionFavorite)
    private val favoriteIcon: ImageView = root.findViewById(R.id.queueSelectionFavoriteIcon)
    private val selectionActions: List<View>

    private val selection = QueueSelection()
    private val adapter: QueueAdapter
    private val shuffleMotion: ShuffleItemAnimator

    private var dragging = false
    private var dragFrom = -1
    private var dragTo = -1
    private var swipePaint: Paint? = null
    private var swipeIcon: Drawable? = null

    var isQueueVisible: Boolean = false
        private set

    val isSelectionActive: Boolean
        get() = selection.isActive

    init {
        val addToPlaylist = root.findViewById<View>(R.id.queueSelectionPlaylist)
        val remove = root.findViewById<View>(R.id.queueSelectionRemove)
        selectionActions = listOf(addToPlaylist, favoriteAction, remove)

        root.findViewById<View>(R.id.queueSavePlaylist).setOnClickListener { saveQueue() }
        root.findViewById<View>(R.id.queueMore).setOnClickListener {
            if (selection.isActive) exitSelection() else enterSelection(RecyclerView.NO_POSITION)
        }
        root.findViewById<View>(R.id.queueSelectionClose).setOnClickListener { exitSelection() }
        root.findViewById<View>(R.id.queueSelectAll).setOnClickListener { selectAll() }
        addToPlaylist.setOnClickListener { addSelectedToPlaylist() }
        favoriteAction.setOnClickListener { toggleSelectedFavorites() }
        remove.setOnClickListener { removeSelected() }

        val touchHelper = createTouchHelper()
        adapter = QueueAdapter(
            thumbnails = ThumbnailLoader.get(context),
            unknownTitle = context.getString(R.string.player_unknown_title),
            unknownArtist = context.getString(R.string.library_unknown_artist),
            onStartDrag = { holder -> touchHelper.startDrag(holder) },
            selection = selection,
            onTap = { index -> onRowTapped(index) },
            onLongPress = { index -> enterSelection(index) },
            onRemove = { index -> host.connection.removeQueueItem(index) },
            onReorder = { previous, next, activeIndex -> prepareShuffleMotion(previous, next, activeIndex) }
        )
        shuffleMotion = ShuffleItemAnimator(list)
        list.layoutManager = LinearLayoutManager(context)
        list.itemAnimator = shuffleMotion
        list.adapter = adapter
        touchHelper.attachToRecyclerView(list)
        renderSelectionMode()
        updateQueueIconTint()
    }

    fun toggleQueue() {
        setQueueVisible(!isQueueVisible, animate = true)
    }

    fun setQueueVisible(visible: Boolean, animate: Boolean) {
        if (isQueueVisible == visible) return
        isQueueVisible = visible
        if (!visible && selection.isActive) {
            selection.stop()
            renderSelectionMode()
            adapter.refreshSelection()
        }
        updateQueueIconTint()
        onStateChanged()

        discStage.animate().cancel()
        queueStage.animate().cancel()

        if (visible) {
            render()
            scrollToCurrentTrack()
            queueStage.visibility = View.VISIBLE
            if (!animate) {
                discStage.alpha = 0f
                discStage.visibility = View.INVISIBLE
                queueStage.alpha = 1f
                return
            }
            discStage.animate()
                .alpha(0f)
                .setDuration(FADE_DURATION_MS)
                .setInterpolator(ACCELERATE)
                .withEndAction {
                    if (isQueueVisible) discStage.visibility = View.INVISIBLE
                }
                .start()
            queueStage.animate()
                .alpha(1f)
                .setDuration(FADE_DURATION_MS)
                .setInterpolator(DECELERATE)
                .start()
        } else {
            shuffleMotion.disarm()
            discStage.visibility = View.VISIBLE
            if (!animate) {
                queueStage.alpha = 0f
                queueStage.visibility = View.GONE
                discStage.alpha = 1f
                return
            }
            queueStage.animate()
                .alpha(0f)
                .setDuration(FADE_DURATION_MS)
                .setInterpolator(ACCELERATE)
                .withEndAction {
                    if (!isQueueVisible) queueStage.visibility = View.GONE
                }
                .start()
            discStage.animate()
                .alpha(1f)
                .setDuration(FADE_DURATION_MS)
                .setInterpolator(DECELERATE)
                .start()
        }
    }

    /**
     * Handles system Back when the inline queue or its selection mode is open.
     *
     * @return true if the back press was consumed
     */
    fun handleBackPressed(): Boolean {
        if (selection.isActive) {
            exitSelection()
            return true
        }
        if (isQueueVisible) {
            setQueueVisible(false, animate = true)
            return true
        }
        return false
    }

    fun onPlayerChanged() {
        if (!dragging) render()
    }

    fun stop() {
        shuffleMotion.disarm()
        discStage.animate().cancel()
        queueStage.animate().cancel()
        if (isQueueVisible) {
            discStage.alpha = 0f
            discStage.visibility = View.INVISIBLE
            queueStage.alpha = 1f
            queueStage.visibility = View.VISIBLE
        } else {
            queueStage.alpha = 0f
            queueStage.visibility = View.GONE
            discStage.alpha = 1f
            discStage.visibility = View.VISIBLE
        }
    }

    private fun updateQueueIconTint() {
        queueIcon.setColorFilter(if (isQueueVisible) COLOR_ON else COLOR_IDLE)
    }

    private fun scrollToCurrentTrack() {
        val state = host.nowPlaying
        val index = state.currentIndex
        if (index !in state.queue.indices) return
        val manager = list.layoutManager as? LinearLayoutManager ?: return
        list.post {
            val rowHeight = (DEFAULT_ROW_DP * resources.displayMetrics.density).toInt()
            val viewportHeight = maxOf(rowHeight, list.height - list.paddingTop - list.paddingBottom)
            val offset = maxOf(0, (viewportHeight - rowHeight) / 3)
            manager.scrollToPositionWithOffset(index, offset)
        }
    }

    fun render() {
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
            context.getString(R.string.player_idle)
        } else {
            context.getString(R.string.queue_position, state.currentIndex + 1, queue.size)
        }
        if (subtitleView.text.toString() != subtitle) subtitleView.text = subtitle
        updateQueueIconTint()
    }

    private fun prepareShuffleMotion(previous: LongArray, next: LongArray, activeIndex: Int) {
        if (!isQueueVisible) return
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

    // ------------------------------------------------------------------ saving & selection

    private fun saveQueue() {
        val tracks = tracksOf(host.nowPlaying.queue.map { it.trackUri })
        if (tracks.isEmpty()) {
            toast(context.getString(R.string.queue_nothing_to_save))
            return
        }
        PlaylistDialogs.addTracks(context, repository, tracks, R.string.queue_save_playlist)
    }

    private fun tracksOf(uris: List<String>): List<Track> {
        return uris.asSequence().distinct().mapNotNull { repository.findTrack(it) }.toList()
    }

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

    private fun enterSelection(position: Int) {
        val wasActive = selection.isActive
        selection.start()
        adapter.uidAt(position)?.let { uid -> if (!selection.contains(uid)) selection.toggle(uid) }
        renderSelectionMode()
        if (wasActive) adapter.refreshRow(position) else adapter.refreshSelection()
        onStateChanged()
    }

    private fun exitSelection() {
        if (!selection.isActive) return
        selection.stop()
        renderSelectionMode()
        adapter.refreshSelection()
        onStateChanged()
    }

    private fun selectAll() {
        val uids = adapter.currentUids()
        if (uids.isNotEmpty() && selection.size >= uids.size) selection.clear() else selection.selectAll(uids)
        adapter.refreshSelection()
        renderSelection()
    }

    private fun renderSelectionMode() {
        val active = selection.isActive
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
            context.getString(if (allFavorite) R.string.menu_remove_favorite else R.string.menu_add_favorite)
    }

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
            toast(context.getString(R.string.queue_nothing_to_save))
            return
        }
        PlaylistDialogs.addTracks(context, repository, tracks)
        exitSelection()
    }

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
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ swipe & drag

    private fun drawSwipeBackground(canvas: Canvas, row: View, dX: Float) {
        val paint = swipePaint ?: Paint().apply { color = SWIPE_COLOR }.also { swipePaint = it }
        canvas.drawRect(row.right + dX, row.top.toFloat(), row.right.toFloat(), row.bottom.toFloat(), paint)
        var icon = swipeIcon
        if (icon == null) {
            icon = ContextCompat.getDrawable(context, R.drawable.player_ic_delete)?.mutate() ?: return
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
        const val FADE_DURATION_MS = 220L
        const val COLOR_ON = 0xFFFF6D00.toInt()
        const val COLOR_IDLE = 0xFFFDF8F5.toInt()
        const val DRAG_ALPHA = 0.75f
        const val DEFAULT_ROW_DP = 76f
        const val DISABLED_ALPHA = 0.35f
        const val SWIPE_COLOR = 0xFFD32F2F.toInt()
        const val SWIPE_ICON_DP = 24f
        const val SWIPE_MARGIN_DP = 24f
        val ACCELERATE = AccelerateInterpolator()
        val DECELERATE = DecelerateInterpolator()
    }
}
