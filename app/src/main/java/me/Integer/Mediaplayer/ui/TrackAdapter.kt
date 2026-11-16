package me.Integer.Mediaplayer.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.ui.widgets.PressFeedback

/**
 * Song rows: cover, title, artists, a quick "Play next" button, and a three-dot menu.
 * The playing song is highlighted.
 */
class TrackAdapter(
    private val thumbnails: ThumbnailLoader,
    private val artistLine: (Track) -> String,
    private val onClick: (Int) -> Unit,
    private val onPlayNext: (Track) -> Unit,
    private val onMenu: (Track, View) -> Unit
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: View = view.findViewById(R.id.trackThumb)
        val art: ImageView = view.findViewById(R.id.trackArt)
        val title: TextView = view.findViewById(R.id.trackTitle)
        val artist: TextView = view.findViewById(R.id.trackArtist)
        val playNext: View = view.findViewById(R.id.trackPlayNext)
        val menu: View = view.findViewById(R.id.trackMenu)
        val divider: View = view.findViewById(R.id.trackDivider)
    }

    private var tracks: List<Track> = emptyList()
    private var playingUri = ""

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<Track>) {
        tracks = next
        notifyDataSetChanged()
    }

    /** Only the two affected rows are rebound, so the highlight crossfades instead of the list flashing. */
    fun setPlaying(trackUri: String) {
        if (trackUri == playingUri) return
        val previous = playingUri
        playingUri = trackUri
        notifyRow(previous)
        notifyRow(trackUri)
    }

    private fun notifyRow(trackUri: String) {
        if (trackUri.isEmpty()) return
        val index = tracks.indexOfFirst { it.uri == trackUri }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun getItemCount(): Int {
        return tracks.size
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
        val track = tracks.getOrNull(position) ?: return
        val context = holder.itemView.context
        holder.title.text = track.title
        holder.artist.text = artistLine(track)
        val color = if (track.uri == playingUri) R.color.accent else R.color.text_primary
        holder.title.setTextColor(ContextCompat.getColor(context, color))
        holder.divider.visibility = if (position == tracks.size - 1) View.INVISIBLE else View.VISIBLE
        thumbnails.bind(holder.art, track.uri)
    }
}
