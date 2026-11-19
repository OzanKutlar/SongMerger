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
import me.Integer.Mediaplayer.library.Artist
import me.Integer.Mediaplayer.ui.widgets.SkeletonDrawable
import me.Integer.Mediaplayer.ui.widgets.ViewGeometry

/** Compact grid tiles for artists: square cover from their first song, overlaid name and song count. */
class ArtistAdapter(
    private val thumbnails: ThumbnailLoader,
    private val onClick: (Artist) -> Unit
) : RecyclerView.Adapter<ArtistAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: View = view.findViewById(R.id.artistThumb)
        val art: ImageView = view.findViewById(R.id.artistArt)
        val name: TextView = view.findViewById(R.id.artistName)
        val count: TextView = view.findViewById(R.id.artistCount)
        var isSkeleton = false
    }

    private var artists: List<Artist> = emptyList()
    var isLoading = false
        private set

    fun rows(): List<Artist> = artists

    @SuppressLint("NotifyDataSetChanged")
    fun setLoading(loading: Boolean) {
        if (isLoading == loading) return
        isLoading = loading
        if (artists.isEmpty()) notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<Artist>, diff: DiffUtil.DiffResult? = null) {
        val wasLoading = isLoading
        val hadArtists = artists.isNotEmpty()
        isLoading = false
        artists = next
        if (diff != null && !wasLoading && hadArtists && next.isNotEmpty()) {
            diff.dispatchUpdatesTo(this)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int {
        return if (isLoading && artists.isEmpty()) SKELETON_COUNT else artists.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_artist, parent, false)
        val holder = Holder(view)
        holder.thumb.clipToOutline = true
        ViewGeometry.applySquareGridSize(parent, holder.thumb)
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position in artists.indices) onClick(artists[position])
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (isLoading && artists.isEmpty()) {
            bindSkeleton(holder)
            return
        }
        val artist = artists.getOrNull(position) ?: return
        if (holder.isSkeleton) {
            holder.isSkeleton = false
            holder.name.background = null
            holder.count.background = null
        }
        val resources = holder.itemView.resources
        val songs = artist.tracks.size
        holder.name.text = artist.name.ifEmpty { resources.getString(R.string.library_unknown_artist) }
        holder.count.text = resources.getQuantityString(R.plurals.library_song_count, songs, songs)
        thumbnails.bind(holder.art, artist.tracks.firstOrNull()?.uri.orEmpty())
    }

    private fun bindSkeleton(holder: Holder) {
        val context = holder.itemView.context
        holder.isSkeleton = true
        thumbnails.clear(holder.art)
        holder.art.setImageDrawable(SkeletonDrawable.tile(context, TILE_CORNER_DP))
        holder.name.text = ""
        holder.count.text = ""
        holder.name.background = SkeletonDrawable.bar(context)
        holder.count.background = null
    }

    companion object {
        private const val SKELETON_COUNT = 9
        private const val TILE_CORNER_DP = 14f

        fun diff(previous: List<Artist>, next: List<Artist>): DiffUtil.DiffResult {
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
