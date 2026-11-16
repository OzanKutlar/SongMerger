package me.Integer.Mediaplayer.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.Artist

/** Artist rows: name and number of songs. */
class ArtistAdapter(private val onClick: (Artist) -> Unit) : RecyclerView.Adapter<ArtistAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.artistName)
        val count: TextView = view.findViewById(R.id.artistCount)
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
    }
}
