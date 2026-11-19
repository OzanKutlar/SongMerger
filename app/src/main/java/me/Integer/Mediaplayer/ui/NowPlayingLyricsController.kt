package me.Integer.Mediaplayer.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.LyricsReader
import me.Integer.Mediaplayer.ui.widgets.ViewGeometry
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Drives the inline synchronized/embedded lyrics view inside the Now Playing screen.
 * Replaces the vinyl disc or queue in the center stage, highlights the active line in
 * real time as playback progresses, and lets the user tap any synced line to seek.
 */
class NowPlayingLyricsController(
    root: View,
    private val host: PlayerHost,
    private val repository: LibraryRepository,
    private val onStateChanged: () -> Unit
) {

    private val context = root.context
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Mediaplayer-Lyrics").apply { isDaemon = true }
    }

    private val discStage: View = root.findViewById(R.id.playerDiscStage)
    private val lyricsStage: View = root.findViewById(R.id.playerLyricsStage)
    private val list: RecyclerView = root.findViewById(R.id.lyricsList)
    private val emptyView: TextView = root.findViewById(R.id.lyricsEmpty)

    private val adapter = LineAdapter { line ->
        if (lyrics.synced && host.nowPlaying.canSeek) {
            host.connection.seekTo(line.timeMs)
        }
    }

    private var loadedUri = ""
    private var loadGen = 0L
    private var lyrics = LyricsReader.Lyrics(emptyList(), false)
    private var activeLine = -1

    var isLyricsVisible: Boolean = false
        private set

    private val tickTask = object : Runnable {
        override fun run() {
            if (!isLyricsVisible) return
            syncActiveLine()
            main.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    init {
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
    }

    fun toggleLyrics(hideQueue: () -> Unit) {
        if (!isLyricsVisible) hideQueue()
        setLyricsVisible(!isLyricsVisible, animate = true)
    }

    fun setLyricsVisible(visible: Boolean, animate: Boolean) {
        if (isLyricsVisible == visible) return
        isLyricsVisible = visible
        onStateChanged()
        main.removeCallbacks(tickTask)
        if (visible) {
            ensureLoaded()
            main.post(tickTask)
        }
        ViewGeometry.transitionOverlayStage(discStage, lyricsStage, visible, animate) { isLyricsVisible }
    }

    fun onPlayerChanged() {
        if (isLyricsVisible) {
            ensureLoaded()
            syncActiveLine()
        }
    }

    fun stop() {
        main.removeCallbacks(tickTask)
        lyricsStage.animate().cancel()
    }

    fun destroy() {
        stop()
        worker.shutdownNow()
    }

    private fun ensureLoaded() {
        val uri = host.nowPlaying.trackUri
        if (uri == loadedUri) return
        loadedUri = uri
        activeLine = -1
        val gen = ++loadGen
        if (uri.isEmpty()) {
            applyLyrics(LyricsReader.Lyrics(emptyList(), false))
            return
        }
        val track = repository.findTrack(uri)
        worker.execute {
            val parsed = LyricsReader.load(context, track)
            main.post {
                if (gen == loadGen) applyLyrics(parsed)
            }
        }
    }

    private fun applyLyrics(next: LyricsReader.Lyrics) {
        lyrics = next
        activeLine = -1
        adapter.submit(next.lines, next.synced)
        emptyView.visibility = if (next.isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (next.isEmpty) View.GONE else View.VISIBLE
        syncActiveLine()
    }

    private fun syncActiveLine() {
        if (!lyrics.synced || lyrics.isEmpty) return
        val pos = host.nowPlaying.positionAt(SystemClock.elapsedRealtime())
        val idx = lyrics.activeIndexAt(pos)
        if (idx == activeLine) return
        activeLine = idx
        adapter.setActive(idx)
        if (idx >= 0) {
            val manager = list.layoutManager as? LinearLayoutManager ?: return
            val offset = maxOf(0, list.height / 3)
            manager.scrollToPositionWithOffset(idx, offset)
        }
    }

    private class LineAdapter(
        private val onClick: (LyricsReader.Line) -> Unit
    ) : RecyclerView.Adapter<LineAdapter.Holder>() {

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        private var lines: List<LyricsReader.Line> = emptyList()
        private var synced = false
        private var activeIndex = -1

        @SuppressLint("NotifyDataSetChanged")
        fun submit(next: List<LyricsReader.Line>, isSynced: Boolean) {
            lines = next
            synced = isSynced
            activeIndex = -1
            notifyDataSetChanged()
        }

        fun setActive(index: Int) {
            if (index == activeIndex) return
            val old = activeIndex
            activeIndex = index
            if (old in lines.indices) notifyItemChanged(old)
            if (index in lines.indices) notifyItemChanged(index)
        }

        override fun getItemCount(): Int = lines.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val padH = (24 * density).roundToInt()
            val padV = (10 * density).roundToInt()
            val tv = TextView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(padH, padV, padH, padV)
            }
            val holder = Holder(tv)
            tv.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos in lines.indices) onClick(lines[pos])
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val line = lines[position]
            val ctx = holder.text.context
            val isCurrent = synced && position == activeIndex
            holder.text.text = line.text
            holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isCurrent) 20f else 16f)
            val colorRes = when {
                !synced -> R.color.text_primary
                isCurrent -> R.color.accent
                else -> R.color.text_secondary
            }
            holder.text.setTextColor(ContextCompat.getColor(ctx, colorRes))
            holder.text.alpha = if (!synced || isCurrent) 1f else 0.72f
        }
    }

    private companion object {
        const val SYNC_INTERVAL_MS = 200L
    }
}
