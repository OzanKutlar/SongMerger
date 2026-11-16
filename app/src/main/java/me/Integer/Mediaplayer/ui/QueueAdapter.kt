package me.Integer.Mediaplayer.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.playback.QueueItem

/**
 * Rows for the current playback queue, with drag handles to reorder and a remove button.
 *
 * A new queue is compared with the shown one by slot id, and only what changed is redrawn:
 * the two rows of a track change, one row for an insert or a removal. A full reorder is
 * announced through [onReorder] first, so the screen can animate it.
 */
class QueueAdapter(
    private val thumbnails: ThumbnailLoader,
    private val unknownTitle: String,
    private val unknownArtist: String,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onTap: (Int) -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onReorder: (previous: LongArray, next: LongArray, activeIndex: Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val dragHandle: View = view.findViewById(R.id.queueDrag)
        val thumb: View = view.findViewById(R.id.queueThumb)
        val art: ImageView = view.findViewById(R.id.queueArt)
        val title: TextView = view.findViewById(R.id.queueTitle)
        val artist: TextView = view.findViewById(R.id.queueArtist)
        val remove: View = view.findViewById(R.id.queueRemove)
        val divider: View = view.findViewById(R.id.queueDivider)
    }

    /** The player's queue as last submitted; never modified. */
    private var source: List<QueueItem> = emptyList()

    /** A reordered copy that exists only between a drag and the player confirming it. */
    private var local: ArrayList<QueueItem>? = null

    /** Slot ids of the rows as shown, in order. */
    private var uids = LongArray(0)
    private var currentIndex = -1

    private val items: List<QueueItem>
        get() = local ?: source

    init {
        setHasStableIds(true)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<QueueItem>, activeIndex: Int) {
        if (next === source && local == null) {
            moveHighlight(activeIndex)
            return
        }
        val previous = uids
        val nextUids = LongArray(next.size) { next[it].uid }
        val change = QueueDiff.compare(previous, nextUids)
        val previousActiveUid = previous.getOrElse(currentIndex) { NO_UID }
        if (change.kind == QueueDiff.REORDERED) onReorder(previous, nextUids, activeIndex)
        source = next
        local = null
        uids = nextUids
        when (change.kind) {
            QueueDiff.SAME -> moveHighlight(activeIndex)
            QueueDiff.REMOVED_ONE -> {
                currentIndex = activeIndex
                notifyItemRemoved(change.index)
                // The row that is now last loses its divider.
                if (change.index == uids.size && uids.isNotEmpty()) notifyItemChanged(uids.size - 1, PAYLOAD_STATE)
                refreshHighlight(previousActiveUid)
            }
            QueueDiff.INSERTED_ONE -> {
                currentIndex = activeIndex
                notifyItemInserted(change.index)
                // The row that used to be last gets its divider back.
                if (change.index == uids.size - 1 && change.index > 0) notifyItemChanged(change.index - 1, PAYLOAD_STATE)
                refreshHighlight(previousActiveUid)
            }
            else -> {
                currentIndex = activeIndex
                notifyDataSetChanged()
            }
        }
    }

    /** Same rows, another one is playing: only the two affected rows are restyled. */
    private fun moveHighlight(activeIndex: Int) {
        if (activeIndex == currentIndex) return
        val old = currentIndex
        currentIndex = activeIndex
        if (old in uids.indices) notifyItemChanged(old, PAYLOAD_STATE)
        if (activeIndex in uids.indices) notifyItemChanged(activeIndex, PAYLOAD_STATE)
    }

    /** After rows shifted: restyles the old and new playing rows if another song took over. */
    private fun refreshHighlight(previousActiveUid: Long) {
        val activeUid = uids.getOrElse(currentIndex) { NO_UID }
        if (activeUid == previousActiveUid) return
        val stale = uids.indexOf(previousActiveUid)
        if (stale >= 0) notifyItemChanged(stale, PAYLOAD_STATE)
        if (currentIndex in uids.indices) notifyItemChanged(currentIndex, PAYLOAD_STATE)
    }

    fun moveLocal(from: Int, to: Int) {
        if (from !in uids.indices || to !in uids.indices || from == to) return
        var list = local
        if (list == null) {
            list = ArrayList(source)
            local = list
            uids = uids.copyOf()
        }
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        val moved = uids[from]
        if (from < to) {
            System.arraycopy(uids, from + 1, uids, from, to - from)
        } else {
            System.arraycopy(uids, to, uids, to + 1, from - to)
        }
        uids[to] = moved
        currentIndex = when (currentIndex) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) currentIndex - 1 else currentIndex + 1
            else -> currentIndex
        }
        notifyItemMoved(from, to)
    }

    override fun getItemId(position: Int): Long {
        return uids.getOrElse(position) { RecyclerView.NO_ID }
    }

    override fun getItemCount(): Int {
        return uids.size
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
        val holder = Holder(view)
        holder.thumb.clipToOutline = true
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onTap(position)
        }
        holder.remove.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onRemove(position)
        }
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }
            false
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items.getOrNull(position) ?: return
        holder.title.text = item.title.ifEmpty { unknownTitle }
        holder.artist.text = item.artist.ifEmpty { unknownArtist }
        bindState(holder, position)
        thumbnails.bind(holder.art, item.trackUri)
    }

    /** A state-only update keeps the row's text and cover as they are. */
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        bindState(holder, position)
    }

    /** What depends on the row's position rather than its song: highlight and divider. */
    private fun bindState(holder: Holder, position: Int) {
        val context = holder.itemView.context
        val color = if (position == currentIndex) R.color.accent else R.color.text_primary
        holder.title.setTextColor(ContextCompat.getColor(context, color))
        holder.divider.visibility = if (position == uids.size - 1) View.INVISIBLE else View.VISIBLE
    }

    private companion object {
        const val NO_UID = Long.MIN_VALUE
        val PAYLOAD_STATE = Any()
    }
}
