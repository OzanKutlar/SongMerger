package me.Integer.Mediaplayer.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.playback.PlaybackClock

/** Song rows: title, artists, length and a three-dot menu. The playing song is highlighted. */
class TrackAdapter(
    private val artistLine: (Track) -> String,
    private val onClick: (Int) -> Unit,
    private val onMenu: (Track, View) -> Unit
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.trackTitle)
        val artist: TextView = view.findViewById(R.id.trackArtist)
        val duration: TextView = view.findViewById(R.id.trackDuration)
        val menu: View = view.findViewById(R.id.trackMenu)
    }

    private var tracks: List<Track> = emptyList()
    private var playingUri = ""

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<Track>) {
        tracks = next
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setPlaying(trackUri: String) {
        if (trackUri == playingUri) return
        playingUri = trackUri
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int {
        return tracks.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        val holder = Holder(view)
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) onClick(position)
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
        holder.duration.text = if (track.durationMs > 0L) PlaybackClock.format(track.durationMs) else ""
        val color = if (track.uri == playingUri) R.color.accent else R.color.text_primary
        holder.title.setTextColor(ContextCompat.getColor(context, color))
    }
}
