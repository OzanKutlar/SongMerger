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
        if (isLoading && playlists.isEmpty()) {
            bindSkeleton(holder, position)
            return
        }
        val playlist = playlists.getOrNull(position) ?: return
        if (holder.isSkeleton) {
            holder.isSkeleton = false
            holder.name.background = null
            holder.count.background = null
            setWidth(holder.name, ViewGroup.LayoutParams.MATCH_PARENT)
            setWidth(holder.count, ViewGroup.LayoutParams.MATCH_PARENT)
            holder.menu.visibility = View.VISIBLE
        }
        val songs = playlist.trackUris.size
        holder.name.text = playlist.name
        holder.count.text = holder.itemView.resources.getQuantityString(R.plurals.library_song_count, songs, songs)
        holder.divider.visibility = if (position == playlists.size - 1) View.INVISIBLE else View.VISIBLE
        covers.bind(holder.art, playlist)
    }

    private fun bindSkeleton(holder: Holder, position: Int) {
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density
        holder.isSkeleton = true
        holder.art.setImageDrawable(SkeletonDrawable.tile(context))
        holder.name.text = ""
        holder.count.text = ""
        holder.name.background = SkeletonDrawable.bar(context)
        holder.count.background = SkeletonDrawable.bar(context)
        setWidth(holder.name, ((NAME_MIN_DP + (position * 33) % NAME_SPREAD_DP) * density).toInt())
        setWidth(holder.count, (COUNT_WIDTH_DP * density).toInt())
        holder.menu.visibility = View.INVISIBLE
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
        private const val NAME_MIN_DP = 130
        private const val NAME_SPREAD_DP = 85
        private const val COUNT_WIDTH_DP = 72

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
