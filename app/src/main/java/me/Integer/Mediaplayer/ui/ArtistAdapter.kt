package me.Integer.Mediaplayer.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.Artist

/** Artist rows: a round cover from their first song, the name and the number of songs. */
class ArtistAdapter(
    private val thumbnails: ThumbnailLoader,
    private val onClick: (Artist) -> Unit
) : RecyclerView.Adapter<ArtistAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: View = view.findViewById(R.id.artistThumb)
        val art: ImageView = view.findViewById(R.id.artistArt)
        val name: TextView = view.findViewById(R.id.artistName)
        val count: TextView = view.findViewById(R.id.artistCount)
        val divider: View = view.findViewById(R.id.artistDivider)
    }

    private var artists: List<Artist> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<Artist>) {
        artists = next
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int {
        return artists.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_artist, parent, false)
        val holder = Holder(view)
        holder.thumb.clipToOutline = true
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position in artists.indices) onClick(artists[position])
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val artist = artists.getOrNull(position) ?: return
        val resources = holder.itemView.resources
        val songs = artist.tracks.size
        holder.name.text = artist.name.ifEmpty { resources.getString(R.string.library_unknown_artist) }
        holder.count.text = resources.getQuantityString(R.plurals.library_song_count, songs, songs)
        holder.divider.visibility = if (position == artists.size - 1) View.INVISIBLE else View.VISIBLE
        thumbnails.bind(holder.art, artist.tracks.firstOrNull()?.uri.orEmpty())
    }
}
