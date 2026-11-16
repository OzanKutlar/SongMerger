package me.Integer.Mediaplayer.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.Playlist

/** Playlist rows: cover, name, number of songs and a three-dot menu. */
class PlaylistAdapter(
    private val covers: PlaylistCoverLoader,
    private val onClick: (Playlist) -> Unit,
    private val onMenu: (Playlist, View) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: View = view.findViewById(R.id.playlistRowThumb)
        val art: ImageView = view.findViewById(R.id.playlistRowArt)
        val name: TextView = view.findViewById(R.id.playlistRowName)
        val count: TextView = view.findViewById(R.id.playlistRowCount)
        val menu: View = view.findViewById(R.id.playlistRowMenu)
        val divider: View = view.findViewById(R.id.playlistRowDivider)
    }

    private var playlists: List<Playlist> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<Playlist>) {
        playlists = next
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int {
        return playlists.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        val holder = Holder(view)
        holder.thumb.clipToOutline = true
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position in playlists.indices) onClick(playlists[position])
        }
        holder.menu.setOnClickListener { anchor ->
            val position = holder.bindingAdapterPosition
            if (position in playlists.indices) onMenu(playlists[position], anchor)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val playlist = playlists.getOrNull(position) ?: return
        val songs = playlist.trackUris.size
        holder.name.text = playlist.name
        holder.count.text = holder.itemView.resources.getQuantityString(R.plurals.library_song_count, songs, songs)
        holder.divider.visibility = if (position == playlists.size - 1) View.INVISIBLE else View.VISIBLE
        covers.bind(holder.art, playlist)
    }
}
