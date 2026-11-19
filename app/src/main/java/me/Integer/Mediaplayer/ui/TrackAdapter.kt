package me.Integer.Mediaplayer.ui

import android.annotation.SuppressLint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.ui.widgets.PressFeedback
import me.Integer.Mediaplayer.ui.widgets.SkeletonDrawable

/**
 * Song rows: cover, title, artists, a quick "Play next" button, and a three-dot menu.
 * The playing song is highlighted. With a [selection], rows show a check mark while songs
 * are being picked, and [onLongPress] (when given) starts picking.
 */
class TrackAdapter(
    private val thumbnails: ThumbnailLoader,
    private val artistLine: (Track) -> String,
    private val onClick: (Int) -> Unit,
    private val onPlayNext: (Track) -> Unit,
    private val onMenu: (Track, View) -> Unit,
    private val selection: TrackSelection? = null,
    private val onLongPress: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: View = view.findViewById(R.id.trackThumb)
        val art: ImageView = view.findViewById(R.id.trackArt)
        val title: TextView = view.findViewById(R.id.trackTitle)
        val artist: TextView = view.findViewById(R.id.trackArtist)
        val playNext: View = view.findViewById(R.id.trackPlayNext)
        val menu: View = view.findViewById(R.id.trackMenu)
        val divider: View = view.findViewById(R.id.trackDivider)
        val check: ImageView = view.findViewById(R.id.trackCheck)
        val normalBackground: Drawable? = view.background
        val pickedBackground: Drawable = ColorDrawable(PICKED_COLOR)
        var isSkeleton = false
    }

    private var tracks: List<Track> = emptyList()
    private var playingUri = ""
    var isLoading = false
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun setLoading(loading: Boolean) {
        if (isLoading == loading) return
        isLoading = loading
        if (tracks.isEmpty()) notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<Track>, diff: DiffUtil.DiffResult? = null) {
        val wasLoading = isLoading
        val hadTracks = tracks.isNotEmpty()
        isLoading = false
        tracks = next
        if (diff != null && !wasLoading && hadTracks && next.isNotEmpty()) {
            diff.dispatchUpdatesTo(this)
        } else {
            notifyDataSetChanged()
        }
    }

    /** Only the two affected rows are rebound, so the highlight crossfades instead of the list flashing. */
    fun setPlaying(trackUri: String) {
        if (trackUri == playingUri) return
        val previous = playingUri
        playingUri = trackUri
        notifyRow(previous)
        notifyRow(trackUri)
    }

    /** Restyles every row after selection starts, stops, or changes as a whole. */
    fun refreshSelection() {
        if (tracks.isNotEmpty()) notifyItemRangeChanged(0, tracks.size, PAYLOAD_SELECTION)
    }

    fun refreshRow(position: Int) {
        if (position in tracks.indices) notifyItemChanged(position, PAYLOAD_SELECTION)
    }

    private fun notifyRow(trackUri: String) {
        if (trackUri.isEmpty()) return
        val index = tracks.indexOfFirst { it.uri == trackUri }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun getItemCount(): Int {
        return if (isLoading && tracks.isEmpty()) SKELETON_COUNT else tracks.size
    }

    /** The rows as shown right now, including moves made by dragging. */
    fun rows(): List<Track> {
        return tracks
    }

    /** Moves one row while it is being dragged; the caller stores the new order afterwards. */
    fun moveLocal(from: Int, to: Int) {
        if (from !in tracks.indices || to !in tracks.indices || from == to) return
        val reordered = ArrayList(tracks)
        reordered.add(to, reordered.removeAt(from))
        tracks = reordered
        notifyItemMoved(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        val holder = Holder(view)
        holder.thumb.clipToOutline = true
        PressFeedback.attach(holder.playNext)
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onClick(position)
        }
        val longPress = onLongPress
        if (longPress != null) {
            view.setOnLongClickListener {
                val position = holder.bindingAdapterPosition
                val usable = position in tracks.indices
                if (usable) longPress(position)
                usable
            }
        }
        holder.playNext.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position in tracks.indices) onPlayNext(tracks[position])
        }
        holder.menu.setOnClickListener { anchor ->
            val position = holder.bindingAdapterPosition
            if (position in tracks.indices) onMenu(tracks[position], anchor)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (isLoading && tracks.isEmpty()) {
            bindSkeleton(holder, position)
            return
        }
        val track = tracks.getOrNull(position) ?: return
        val context = holder.itemView.context
        if (holder.isSkeleton) {
            holder.isSkeleton = false
            holder.title.background = null
            holder.artist.background = null
            setWidth(holder.title, ViewGroup.LayoutParams.MATCH_PARENT)
            setWidth(holder.artist, ViewGroup.LayoutParams.MATCH_PARENT)
            holder.playNext.visibility = View.VISIBLE
            holder.menu.visibility = View.VISIBLE
        }
        holder.title.text = track.title
        holder.artist.text = artistLine(track)
        val color = if (track.uri == playingUri) R.color.accent else R.color.text_primary
        holder.title.setTextColor(ContextCompat.getColor(context, color))
        holder.divider.visibility = if (position == tracks.size - 1) View.INVISIBLE else View.VISIBLE
        thumbnails.bind(holder.art, track.uri)
        bindSelection(holder, track)
    }

    /** A selection-only update keeps the row's text and cover as they are. */
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        val track = tracks.getOrNull(position)
        if (payloads.isEmpty() || holder.isSkeleton || track == null) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        bindSelection(holder, track)
    }

    /** While picking, the row buttons give way to a check mark and picked rows are tinted. */
    private fun bindSelection(holder: Holder, track: Track) {
        val selecting = selection?.isActive == true
        val picked = selecting && selection?.contains(track.uri) == true
        val buttons = if (selecting) View.GONE else View.VISIBLE
        holder.playNext.visibility = buttons
        holder.menu.visibility = buttons
        holder.check.visibility = if (selecting) View.VISIBLE else View.GONE
        val context = holder.itemView.context
        val checkColor = if (picked) R.color.accent else R.color.text_muted
        holder.check.setColorFilter(ContextCompat.getColor(context, checkColor))
        holder.check.alpha = if (picked) 1f else UNPICKED_ALPHA
        val background = if (picked) holder.pickedBackground else holder.normalBackground
        if (holder.itemView.background !== background) holder.itemView.background = background
    }

    private fun bindSkeleton(holder: Holder, position: Int) {
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density
        holder.isSkeleton = true
        thumbnails.clear(holder.art)
        holder.art.setImageDrawable(SkeletonDrawable.tile(context))
        holder.title.text = ""
        holder.artist.text = ""
        holder.title.background = SkeletonDrawable.bar(context)
        holder.artist.background = SkeletonDrawable.bar(context)
        setWidth(holder.title, ((TITLE_MIN_DP + (position * 37) % TITLE_SPREAD_DP) * density).toInt())
        setWidth(holder.artist, ((ARTIST_MIN_DP + (position * 29) % ARTIST_SPREAD_DP) * density).toInt())
        holder.playNext.visibility = View.INVISIBLE
        holder.menu.visibility = View.INVISIBLE
        holder.check.visibility = View.GONE
        if (holder.itemView.background !== holder.normalBackground) holder.itemView.background = holder.normalBackground
        holder.divider.visibility = if (position == SKELETON_COUNT - 1) View.INVISIBLE else View.VISIBLE
    }

    private fun setWidth(view: View, width: Int) {
        val params = view.layoutParams
        if (params.width != width) {
            params.width = width
            view.layoutParams = params
        }
    }

    companion object {
        private const val SKELETON_COUNT = 8
        private const val TITLE_MIN_DP = 140
        private const val TITLE_SPREAD_DP = 90
        private const val ARTIST_MIN_DP = 85
        private const val ARTIST_SPREAD_DP = 70
        private const val UNPICKED_ALPHA = 0.35f
        private const val PICKED_COLOR = 0x22FF6D00
        private val PAYLOAD_SELECTION = Any()

        fun diff(previous: List<Track>, next: List<Track>): DiffUtil.DiffResult {
            return DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previous.size
                override fun getNewListSize(): Int = next.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    return previous[oldPos].uri == next[newPos].uri
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    return previous[oldPos] == next[newPos]
                }
            })
        }
    }
}
