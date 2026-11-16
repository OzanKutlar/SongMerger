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
import java.util.Collections

/**
 * Rows for the current playback queue, with drag handles to reorder and a remove button.
 */
class QueueAdapter(
    private val thumbnails: ThumbnailLoader,
    private val unknownTitle: String,
    private val unknownArtist: String,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onTap: (Int) -> Unit,
    private val onRemove: (Int) -> Unit
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

    private val items = ArrayList<QueueItem>()
    private var currentIndex = -1

    init {
        setHasStableIds(true)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<QueueItem>, activeIndex: Int) {
        items.clear()
        items.addAll(next)
        currentIndex = activeIndex
        notifyDataSetChanged()
    }

    fun moveLocal(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        if (from < to) {
            for (i in from until to) Collections.swap(items, i, i + 1)
        } else {
            for (i in from downTo to + 1) Collections.swap(items, i, i - 1)
        }
        currentIndex = when (currentIndex) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) currentIndex - 1 else currentIndex + 1
            else -> currentIndex
        }
        notifyItemMoved(from, to)
    }

    override fun getItemId(position: Int): Long {
        return items.getOrNull(position)?.uid ?: RecyclerView.NO_ID
    }

    override fun getItemCount(): Int {
        return items.size
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
        val context = holder.itemView.context
        holder.title.text = item.title.ifEmpty { unknownTitle }
        holder.artist.text = item.artist.ifEmpty { unknownArtist }
        val active = position == currentIndex
        val color = if (active) R.color.accent else R.color.text_primary
        holder.title.setTextColor(ContextCompat.getColor(context, color))
        holder.divider.visibility = if (position == items.size - 1) View.INVISIBLE else View.VISIBLE
        thumbnails.bind(holder.art, item.trackUri)
    }
}
