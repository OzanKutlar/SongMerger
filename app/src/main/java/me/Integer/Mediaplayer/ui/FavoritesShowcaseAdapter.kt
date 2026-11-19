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
import me.Integer.Mediaplayer.ui.widgets.SkeletonDrawable

/**
 * Renders the Favorites tab as a 3-column grid showcase:
 * - Top pinned system playlists ('Favorites', 'Most Listened', 'Most Listened This Week', 'Most Listened This Month')
 * - A full-width 'Recaps' section header
 * - Seasonal and monthly Recap playlists (e.g. 'Summer 2026 Recap', 'June 2026 Recap')
 */
class FavoritesShowcaseAdapter(
    private val covers: PlaylistCoverLoader,
    private val onClick: (Playlist) -> Unit,
    private val onMenu: (Playlist, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class PlaylistCard(val playlist: Playlist) : Row()
        data class SectionHeader(val title: String) : Row()
    }

    class PlaylistHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: View = view.findViewById(R.id.playlistRowThumb)
        val art: ImageView = view.findViewById(R.id.playlistRowArt)
        val name: TextView = view.findViewById(R.id.playlistRowName)
        val count: TextView = view.findViewById(R.id.playlistRowCount)
        val menu: View = view.findViewById(R.id.playlistRowMenu)
        var isSkeleton = false
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.sectionHeaderTitle)
    }

    private var items: List<Row> = emptyList()
    var isLoading = false
        private set

    fun rows(): List<Row> = items

    fun playlistCount(): Int = items.count { it is Row.PlaylistCard }

    /** Returns 3 (full row width) for section headers and 1 for playlist cards. */
    fun spanSizeAt(position: Int, spanCount: Int): Int {
        if (isLoading && items.isEmpty()) return 1
        return when (items.getOrNull(position)) {
            is Row.SectionHeader -> spanCount
            else -> 1
        }
    }

    fun sectionLabelAt(position: Int): String {
        return when (val row = items.getOrNull(position)) {
            is Row.PlaylistCard -> row.playlist.name
            is Row.SectionHeader -> row.title
            null -> ""
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setLoading(loading: Boolean) {
        if (isLoading == loading) return
        isLoading = loading
        if (items.isEmpty()) notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<Row>) {
        isLoading = false
        items = next
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int {
        return if (isLoading && items.isEmpty()) SKELETON_COUNT else items.size
    }

    override fun getItemViewType(position: Int): Int {
        if (isLoading && items.isEmpty()) return TYPE_PLAYLIST
        return when (items.getOrNull(position)) {
            is Row.SectionHeader -> TYPE_HEADER
            else -> TYPE_PLAYLIST
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_section_header, parent, false)
            return HeaderHolder(view)
        }
        val view = inflater.inflate(R.layout.item_playlist_grid, parent, false)
        val holder = PlaylistHolder(view)
        holder.thumb.clipToOutline = true
        applySquareSize(parent, holder.thumb)
        view.setOnClickListener {
            val row = items.getOrNull(holder.bindingAdapterPosition) as? Row.PlaylistCard ?: return@setOnClickListener
            onClick(row.playlist)
        }
        view.setOnLongClickListener {
            val row = items.getOrNull(holder.bindingAdapterPosition) as? Row.PlaylistCard ?: return@setOnLongClickListener false
            onMenu(row.playlist, holder.menu)
            true
        }
        holder.menu.setOnClickListener { anchor ->
            val row = items.getOrNull(holder.bindingAdapterPosition) as? Row.PlaylistCard ?: return@setOnClickListener
            onMenu(row.playlist, anchor)
        }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderHolder) {
            val header = items.getOrNull(position) as? Row.SectionHeader ?: return
            holder.title.text = header.title
            return
        }
        val playlistHolder = holder as PlaylistHolder
        if (isLoading && items.isEmpty()) {
            bindSkeleton(playlistHolder)
            return
        }
        val card = items.getOrNull(position) as? Row.PlaylistCard ?: return
        val playlist = card.playlist
        if (playlistHolder.isSkeleton) {
            playlistHolder.isSkeleton = false
            playlistHolder.name.background = null
            playlistHolder.count.background = null
            playlistHolder.menu.visibility = View.VISIBLE
        }
        val songs = playlist.trackUris.size
        playlistHolder.name.text = playlist.name
        playlistHolder.count.text =
            playlistHolder.itemView.resources.getQuantityString(R.plurals.library_song_count, songs, songs)
        covers.bind(playlistHolder.art, playlist)
    }

    private fun bindSkeleton(holder: PlaylistHolder) {
        val context = holder.itemView.context
        holder.isSkeleton = true
        holder.art.setImageDrawable(SkeletonDrawable.tile(context, TILE_CORNER_DP))
        holder.name.text = ""
        holder.count.text = ""
        holder.name.background = SkeletonDrawable.bar(context)
        holder.count.background = null
        holder.menu.visibility = View.INVISIBLE
    }

    private fun applySquareSize(parent: ViewGroup, thumb: View) {
        val usableWidth = parent.measuredWidth - parent.paddingLeft - parent.paddingRight
        val density = parent.resources.displayMetrics.density
        val cellPaddingPx = (CELL_PADDING_DP * 2 * density).toInt()
        val fallbackPx = (DEFAULT_TILE_DP * density).toInt()
        val side = if (usableWidth > 0) (usableWidth / GRID_COLUMNS) - cellPaddingPx else fallbackPx
        if (side > 0) {
            val params = thumb.layoutParams
            params.height = side
            thumb.layoutParams = params
        }
    }

    companion object {
        const val TYPE_PLAYLIST = 0
        const val TYPE_HEADER = 1
        private const val SKELETON_COUNT = 6
        private const val GRID_COLUMNS = 3
        private const val CELL_PADDING_DP = 5f
        private const val DEFAULT_TILE_DP = 112f
        private const val TILE_CORNER_DP = 14f
    }
}
