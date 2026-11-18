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
import me.Integer.Mediaplayer.library.Album
import me.Integer.Mediaplayer.ui.widgets.SkeletonDrawable

/** Album rows: rounded cover art from the first song, album name, artist, and track count. */
class AlbumAdapter(
    private val thumbnails: ThumbnailLoader,
    private val onClick: (Album) -> Unit
) : RecyclerView.Adapter<AlbumAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: View = view.findViewById(R.id.playlistRowThumb)
        val art: ImageView = view.findViewById(R.id.playlistRowArt)
        val name: TextView = view.findViewById(R.id.playlistRowName)
        val count: TextView = view.findViewById(R.id.playlistRowCount)
        val menu: View = view.findViewById(R.id.playlistRowMenu)
        val divider: View = view.findViewById(R.id.playlistRowDivider)
        var isSkeleton = false
    }

    private var albums: List<Album> = emptyList()
    var isLoading = false
        private set

    fun rows(): List<Album> = albums

    @SuppressLint("NotifyDataSetChanged")
    fun setLoading(loading: Boolean) {
        if (isLoading == loading) return
        isLoading = loading
        if (albums.isEmpty()) notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<Album>, diff: DiffUtil.DiffResult? = null) {
        val wasLoading = isLoading
        val hadAlbums = albums.isNotEmpty()
        isLoading = false
        albums = next
        if (diff != null && !wasLoading && hadAlbums && next.isNotEmpty()) {
            diff.dispatchUpdatesTo(this)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int {
        return if (isLoading && albums.isEmpty()) SKELETON_COUNT else albums.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        val holder = Holder(view)
        holder.thumb.clipToOutline = true
        holder.menu.visibility = View.GONE
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position in albums.indices) onClick(albums[position])
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (isLoading && albums.isEmpty()) {
            bindSkeleton(holder, position)
            return
        }
        val album = albums.getOrNull(position) ?: return
        if (holder.isSkeleton) {
            holder.isSkeleton = false
            holder.name.background = null
            holder.count.background = null
            setWidth(holder.name, ViewGroup.LayoutParams.MATCH_PARENT)
            setWidth(holder.count, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        holder.menu.visibility = View.GONE
        val resources = holder.itemView.resources
        val songs = album.tracks.size
        val countText = resources.getQuantityString(R.plurals.library_song_count, songs, songs)
        val artistText = album.artist.ifEmpty { resources.getString(R.string.library_unknown_artist) }
        holder.name.text = album.name.ifEmpty { resources.getString(R.string.album_unknown) }
        holder.count.text = "$artistText · $countText"
        holder.divider.visibility = if (position == albums.size - 1) View.INVISIBLE else View.VISIBLE
        thumbnails.bind(holder.art, album.tracks.firstOrNull()?.uri.orEmpty())
    }

    private fun bindSkeleton(holder: Holder, position: Int) {
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density
        holder.isSkeleton = true
        thumbnails.clear(holder.art)
        holder.art.setImageDrawable(SkeletonDrawable.tile(context))
        holder.name.text = ""
        holder.count.text = ""
        holder.name.background = SkeletonDrawable.bar(context)
        holder.count.background = SkeletonDrawable.bar(context)
        setWidth(holder.name, ((NAME_MIN_DP + (position * 31) % NAME_SPREAD_DP) * density).toInt())
        setWidth(holder.count, (COUNT_WIDTH_DP * density).toInt())
        holder.menu.visibility = View.GONE
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
        private const val NAME_MIN_DP = 120
        private const val NAME_SPREAD_DP = 80
        private const val COUNT_WIDTH_DP = 96

        fun diff(previous: List<Album>, next: List<Album>): DiffUtil.DiffResult {
            return DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previous.size
                override fun getNewListSize(): Int = next.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    return previous[oldPos].name.equals(next[newPos].name, ignoreCase = true)
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    return previous[oldPos] == next[newPos]
                }
            })
        }
    }
}
