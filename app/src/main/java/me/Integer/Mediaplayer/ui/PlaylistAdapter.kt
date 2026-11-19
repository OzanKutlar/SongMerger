package me.Integer.Mediaplayer.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.Playlist
import me.Integer.Mediaplayer.ui.widgets.SkeletonDrawable
import me.Integer.Mediaplayer.ui.widgets.ViewGeometry

/** Compact grid tiles for playlists: square cover collage, overlaid title, song count, and menu. */
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
        var isSkeleton = false
    }

    private var playlists: List<Playlist> = emptyList()
    var isLoading = false
        private set

    fun rows(): List<Playlist> = playlists

    @SuppressLint("NotifyDataSetChanged")
    fun setLoading(loading: Boolean) {
        if (isLoading == loading) return
        isLoading = loading
        if (playlists.isEmpty()) notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<Playlist>, diff: DiffUtil.DiffResult? = null) {
        val wasLoading = isLoading
        val had = playlists.isNotEmpty()
        isLoading = false
        playlists = next
        if (diff != null && !wasLoading && had && next.isNotEmpty()) {
            diff.dispatchUpdatesTo(this)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int {
        return if (isLoading && playlists.isEmpty()) SKELETON_COUNT else playlists.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_grid, parent, false)
        val holder = Holder(view)
        holder.thumb.clipToOutline = true
        ViewGeometry.applySquareGridSize(parent, holder.thumb)
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position in playlists.indices) onClick(playlists[position])
        }
        view.setOnLongClickListener {
            val position = holder.bindingAdapterPosition
            if (position in playlists.indices) {
                onMenu(playlists[position], holder.menu)
                true
            } else {
                false
            }
        }
        holder.menu.setOnClickListener { anchor ->
            val position = holder.bindingAdapterPosition
            if (position in playlists.indices) onMenu(playlists[position], anchor)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (isLoading && playlists.isEmpty()) {
            bindSkeleton(holder)
            return
        }
        val playlist = playlists.getOrNull(position) ?: return
        if (holder.isSkeleton) {
            holder.isSkeleton = false
            holder.name.background = null
            holder.count.background = null
            holder.menu.visibility = View.VISIBLE
        }
        val songs = playlist.trackUris.size
        holder.name.text = playlist.name
        holder.count.text = holder.itemView.resources.getQuantityString(R.plurals.library_song_count, songs, songs)
        covers.bind(holder.art, playlist)
    }

    private fun bindSkeleton(holder: Holder) {
        val context = holder.itemView.context
        holder.isSkeleton = true
        holder.art.setImageDrawable(SkeletonDrawable.tile(context, TILE_CORNER_DP))
        holder.name.text = ""
        holder.count.text = ""
        holder.name.background = SkeletonDrawable.bar(context)
        holder.count.background = null
        holder.menu.visibility = View.INVISIBLE
    }

    companion object {
        private const val SKELETON_COUNT = 9
        private const val TILE_CORNER_DP = 14f

        fun diff(previous: List<Playlist>, next: List<Playlist>): DiffUtil.DiffResult {
            return DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previous.size
                override fun getNewListSize(): Int = next.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    return previous[oldPos].id == next[newPos].id
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    return previous[oldPos] == next[newPos]
                }
            })
        }
    }
}
