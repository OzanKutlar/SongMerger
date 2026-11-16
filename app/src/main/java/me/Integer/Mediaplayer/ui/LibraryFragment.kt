package me.Integer.Mediaplayer.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.ui.widgets.MarqueeTextView
import me.Integer.Mediaplayer.ui.widgets.PressFeedback
import me.Integer.Mediaplayer.ui.widgets.VinylDiscView
import java.util.Locale

/** The song picker: search, Songs and Artists tabs, and a mini player at the bottom. */
class LibraryFragment : Fragment(R.layout.fragment_library), LibraryRepository.Listener, PlayerHost.Listener {

    private lateinit var repository: LibraryRepository
    private lateinit var host: PlayerHost
    private lateinit var navigator: Navigator
    private lateinit var trackAdapter: TrackAdapter
    private lateinit var artistAdapter: ArtistAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var scanView: TextView
    private lateinit var songsTab: TextView
    private lateinit var artistsTab: TextView
    private lateinit var miniBar: View
    private lateinit var miniVinyl: VinylDiscView
    private lateinit var miniTitle: MarqueeTextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlayIcon: ImageView

    private var showingArtists = false
    private var query = ""
    private var visibleTracks: List<Track> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LibraryRepository.get(requireContext())
        host = requireActivity() as PlayerHost
        navigator = requireActivity() as Navigator
        showingArtists = savedInstanceState?.getBoolean(KEY_ARTISTS) ?: showingArtists
        bindViews(view)
        createAdapters()
        wireControls(view)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ARTISTS, showingArtists)
    }

    override fun onStart() {
        super.onStart()
        repository.addListener(this)
        host.addPlayerListener(this)
        refresh()
        renderMini()
    }

    override fun onStop() {
        host.removePlayerListener(this)
        repository.removeListener(this)
        miniVinyl.setPlaying(false)
        super.onStop()
    }

    override fun onLibraryChanged() {
        refresh()
    }

    override fun onScanState(state: LibraryRepository.ScanState) {
        renderScan(state)
    }

    override fun onPlayerChanged() {
        renderMini()
    }

    // ------------------------------------------------------------------ setup

    private fun bindViews(view: View) {
        list = view.findViewById(R.id.libraryList)
        emptyView = view.findViewById(R.id.libraryEmpty)
        scanView = view.findViewById(R.id.libraryScan)
        songsTab = view.findViewById(R.id.libraryTabSongs)
        artistsTab = view.findViewById(R.id.libraryTabArtists)
        miniBar = view.findViewById(R.id.miniBar)
        miniVinyl = view.findViewById(R.id.miniVinyl)
        miniTitle = view.findViewById(R.id.miniTitle)
        miniArtist = view.findViewById(R.id.miniArtist)
        miniPlayIcon = view.findViewById(R.id.miniPlayIcon)
        list.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun createAdapters() {
        trackAdapter = TrackAdapter(
            artistLine = { artistLineOf(it) },
            onClick = { index -> host.playTracks(visibleTracks, index) },
            onMenu = { track, anchor -> TrackMenu.show(anchor, track, host, repository) }
        )
        artistAdapter = ArtistAdapter { artist -> navigator.openArtist(artist.name) }
    }

    private fun wireControls(view: View) {
        view.findViewById<EditText>(R.id.librarySearch).doAfterTextChanged { text ->
            query = text?.toString().orEmpty()
            refresh()
        }
        view.findViewById<View>(R.id.librarySettings).setOnClickListener { navigator.openSettings() }
        songsTab.setOnClickListener { selectTab(false) }
        artistsTab.setOnClickListener { selectTab(true) }
        miniBar.setOnClickListener { navigator.openNowPlaying() }
        val miniPlay = view.findViewById<View>(R.id.miniPlay)
        val miniNext = view.findViewById<View>(R.id.miniNext)
        PressFeedback.attach(miniPlay, miniPlayIcon)
        PressFeedback.attach(miniNext, view.findViewById(R.id.miniNextIcon))
        miniPlay.setOnClickListener { togglePlayback() }
        miniNext.setOnClickListener { host.connection.next() }
    }

    private fun selectTab(artists: Boolean) {
        if (showingArtists == artists) return
        showingArtists = artists
        refresh()
    }

    private fun togglePlayback() {
        if (host.nowPlaying.playing) host.connection.pause() else host.connection.play()
    }

    // ------------------------------------------------------------------ rendering

    private fun refresh() {
        val needle = query.trim().lowercase(Locale.ROOT)
        val shown = if (showingArtists) showArtists(needle) else showSongs(needle)
        renderTabs()
        renderEmpty(shown)
        renderScan(repository.scanState)
    }

    /** @return the number of rows shown */
    private fun showSongs(needle: String): Int {
        visibleTracks = repository.tracks.filter { track ->
            needle.isEmpty() ||
                track.title.lowercase(Locale.ROOT).contains(needle) ||
                track.artistTag.lowercase(Locale.ROOT).contains(needle)
        }
        trackAdapter.submit(visibleTracks)
        if (list.adapter !== trackAdapter) list.adapter = trackAdapter
        return visibleTracks.size
    }

    private fun showArtists(needle: String): Int {
        val artists = repository.artists().filter { artist ->
            needle.isEmpty() || artist.name.lowercase(Locale.ROOT).contains(needle)
        }
        artistAdapter.submit(artists)
        if (list.adapter !== artistAdapter) list.adapter = artistAdapter
        return artists.size
    }

    private fun renderTabs() {
        val context = requireContext()
        val on = ContextCompat.getColor(context, R.color.accent)
        val off = ContextCompat.getColor(context, R.color.text_muted)
        songsTab.setTextColor(if (showingArtists) off else on)
        artistsTab.setTextColor(if (showingArtists) on else off)
    }

    private fun renderEmpty(shownRows: Int) {
        emptyView.visibility = if (shownRows == 0) View.VISIBLE else View.GONE
        if (shownRows > 0) return
        emptyView.setText(if (repository.tracks.isEmpty()) R.string.library_empty else R.string.library_no_match)
    }

    private fun renderScan(state: LibraryRepository.ScanState) {
        scanView.visibility = if (state.running) View.VISIBLE else View.GONE
        if (!state.running) return
        scanView.text = if (state.total <= 0) {
            getString(R.string.library_scanning_folders)
        } else {
            getString(R.string.library_scanning, state.done, state.total)
        }
    }

    private fun renderMini() {
        val state = host.nowPlaying
        miniBar.visibility = if (state.active) View.VISIBLE else View.GONE
        trackAdapter.setPlaying(state.trackUri)
        if (!state.active) {
            miniVinyl.setPlaying(false)
            return
        }
        miniTitle.setText(state.title.ifEmpty { getString(R.string.player_unknown_title) })
        miniArtist.text = state.artist.ifEmpty { getString(R.string.library_unknown_artist) }
        if (host.isArtResolved) miniVinyl.setArt(host.currentArt)
        miniVinyl.setPlaying(state.clockRunning)
        miniPlayIcon.setImageResource(if (state.playing) R.drawable.player_ic_pause else R.drawable.player_ic_play)
    }

    private fun artistLineOf(track: Track): String {
        return repository.displayArtist(track).ifEmpty { getString(R.string.library_unknown_artist) }
    }

    private companion object {
        const val KEY_ARTISTS = "showing_artists"
    }
}
