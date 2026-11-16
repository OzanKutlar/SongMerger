package me.Integer.Mediaplayer.ui

import android.animation.ValueAnimator
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.library.TrackSorter
import me.Integer.Mediaplayer.ui.widgets.PressFeedback
import java.util.Locale
import kotlin.math.abs

/** The song picker: header with search and menu, Tracks and Artists tabs, and the list card. */
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
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var sortButton: View
    private lateinit var sortIcon: View
    private lateinit var sortLabel: TextView
    private lateinit var shuffleButton: View
    private lateinit var playAllButton: View
    private val tabAnimators = HashMap<TextView, ValueAnimator>()

    private var showingArtists = false
    private var query = ""
    private var tabsStyled = false
    private var visibleTracks: List<Track> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LibraryRepository.get(requireContext())
        host = requireActivity() as PlayerHost
        navigator = requireActivity() as Navigator
        showingArtists = savedInstanceState?.getBoolean(KEY_ARTISTS) ?: showingArtists
        tabsStyled = false
        bindViews(view)
        createAdapters()
        wireHeader(view)
        wireCard()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ARTISTS, showingArtists)
    }

    override fun onStart() {
        super.onStart()
        repository.addListener(this)
        host.addPlayerListener(this)
        if (query.isNotEmpty()) searchBar.visibility = View.VISIBLE
        refresh()
        onPlayerChanged()
    }

    override fun onStop() {
        host.removePlayerListener(this)
        repository.removeListener(this)
        for (animator in tabAnimators.values) animator.end()
        tabAnimators.clear()
        super.onStop()
    }

    override fun onLibraryChanged() {
        refresh()
    }

    override fun onScanState(state: LibraryRepository.ScanState) {
        renderScan(state)
    }

    override fun onPlayerChanged() {
        trackAdapter.setPlaying(host.nowPlaying.trackUri)
    }

    // ------------------------------------------------------------------ setup

    private fun bindViews(view: View) {
        list = view.findViewById(R.id.libraryList)
        emptyView = view.findViewById(R.id.libraryEmpty)
        scanView = view.findViewById(R.id.libraryScan)
        songsTab = view.findViewById(R.id.libraryTabSongs)
        artistsTab = view.findViewById(R.id.libraryTabArtists)
        searchBar = view.findViewById(R.id.librarySearchBar)
        searchInput = view.findViewById(R.id.librarySearch)
        sortButton = view.findViewById(R.id.librarySort)
        sortIcon = view.findViewById(R.id.librarySortIcon)
        sortLabel = view.findViewById(R.id.librarySortLabel)
        shuffleButton = view.findViewById(R.id.libraryShuffle)
        playAllButton = view.findViewById(R.id.libraryPlayAll)
        list.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun createAdapters() {
        val thumbnails = ThumbnailLoader.get(requireContext())
        trackAdapter = TrackAdapter(
            thumbnails = thumbnails,
            artistLine = { artistLineOf(it) },
            onClick = { index -> host.playTracks(visibleTracks, index) },
            onMenu = { track, anchor -> TrackMenu.show(anchor, track, host, repository) }
        )
        artistAdapter = ArtistAdapter(thumbnails) { artist -> navigator.openArtist(artist.name) }
    }

    private fun wireHeader(view: View) {
        view.findViewById<View>(R.id.librarySearchButton).setOnClickListener { openSearch() }
        view.findViewById<View>(R.id.librarySearchClose).setOnClickListener { closeSearch() }
        view.findViewById<View>(R.id.libraryMoreButton).setOnClickListener { showOverflow(it) }
        searchInput.doAfterTextChanged { text ->
            query = text?.toString().orEmpty()
            refresh()
        }
        songsTab.setOnClickListener { selectTab(false) }
        artistsTab.setOnClickListener { selectTab(true) }
    }

    private fun wireCard() {
        sortButton.setOnClickListener { showSortMenu(it) }
        PressFeedback.attach(shuffleButton, null, ROUND_PRESS_SCALE)
        PressFeedback.attach(playAllButton, null, ROUND_PRESS_SCALE)
        shuffleButton.setOnClickListener { host.playAll(visibleTracks, true) }
        playAllButton.setOnClickListener { host.playAll(visibleTracks, false) }
    }

    // ------------------------------------------------------------------ header actions

    private fun openSearch() {
        searchBar.visibility = View.VISIBLE
        searchInput.requestFocus()
        keyboard()?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        searchInput.setText("")
        keyboard()?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
        searchBar.visibility = View.GONE
    }

    private fun keyboard(): InputMethodManager? {
        return requireContext().getSystemService(InputMethodManager::class.java)
    }

    private fun showOverflow(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, ID_RESCAN, 0, R.string.library_rescan)
        popup.menu.add(0, ID_SETTINGS, 1, R.string.library_settings)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_RESCAN -> repository.rescan()
                ID_SETTINGS -> navigator.openSettings()
            }
            true
        }
        popup.show()
    }

    private fun showSortMenu(anchor: View) {
        val current = repository.settings.trackSort
        val popup = PopupMenu(requireContext(), anchor)
        for ((mode, label) in SORT_LABELS.withIndex()) {
            popup.menu.add(0, mode, mode, label).isChecked = mode == current
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            repository.settings.trackSort = item.itemId
            refresh()
            list.scrollToPosition(0)
            true
        }
        popup.show()
    }

    private fun selectTab(artists: Boolean) {
        if (showingArtists == artists) return
        showingArtists = artists
        refresh()
        list.scrollToPosition(0)
    }

    // ------------------------------------------------------------------ rendering

    private fun refresh() {
        val needle = query.trim().lowercase(Locale.ROOT)
        val shown = if (showingArtists) showArtists(needle) else showSongs(needle)
        renderTabs()
        renderCardHeader(shown)
        renderEmpty(shown)
        renderScan(repository.scanState)
    }

    /** @return the number of rows shown */
    private fun showSongs(needle: String): Int {
        val matching = repository.tracks.filter { track ->
            needle.isEmpty() ||
                track.title.lowercase(Locale.ROOT).contains(needle) ||
                track.artistTag.lowercase(Locale.ROOT).contains(needle)
        }
        visibleTracks = TrackSorter.sort(matching, repository.settings.trackSort) { repository.displayArtist(it) }
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
        styleTab(songsTab, !showingArtists)
        styleTab(artistsTab, showingArtists)
        tabsStyled = true
    }

    /** The selected tab grows large and white, the others shrink and fade to grey. */
    private fun styleTab(tab: TextView, selected: Boolean) {
        val metrics = resources.displayMetrics
        val targetPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, if (selected) TAB_SELECTED_SP else TAB_IDLE_SP, metrics
        )
        val color = if (selected) R.color.text_primary else R.color.text_muted
        tab.setTextColor(ContextCompat.getColor(requireContext(), color))
        tabAnimators.remove(tab)?.cancel()
        if (!tabsStyled) {
            tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetPx)
            return
        }
        if (abs(tab.textSize - targetPx) < 0.5f) return
        val grow = ValueAnimator.ofFloat(tab.textSize, targetPx)
        grow.duration = TAB_ANIMATION_MS
        grow.interpolator = DecelerateInterpolator()
        grow.addUpdateListener { tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.animatedValue as Float) }
        tabAnimators[tab] = grow
        grow.start()
    }

    private fun renderCardHeader(shownRows: Int) {
        val tracksTab = !showingArtists
        val buttons = if (tracksTab) View.VISIBLE else View.GONE
        sortIcon.visibility = buttons
        shuffleButton.visibility = buttons
        playAllButton.visibility = buttons
        sortButton.isClickable = tracksTab
        sortLabel.text = if (tracksTab) {
            getString(SORT_LABELS[repository.settings.trackSort])
        } else {
            resources.getQuantityString(R.plurals.library_artist_count, shownRows, shownRows)
        }
        val usable = visibleTracks.isNotEmpty()
        shuffleButton.isEnabled = usable
        playAllButton.isEnabled = usable
        shuffleButton.alpha = if (usable) 1f else DISABLED_ALPHA
        playAllButton.alpha = if (usable) 1f else DISABLED_ALPHA
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

    private fun artistLineOf(track: Track): String {
        return repository.displayArtist(track).ifEmpty { getString(R.string.library_unknown_artist) }
    }

    private companion object {
        const val KEY_ARTISTS = "showing_artists"
        const val ID_RESCAN = 1
        const val ID_SETTINGS = 2
        const val TAB_SELECTED_SP = 30f
        const val TAB_IDLE_SP = 17f
        const val TAB_ANIMATION_MS = 220L
        const val ROUND_PRESS_SCALE = 0.9f
        const val DISABLED_ALPHA = 0.35f

        /** Indexed by the TrackSorter modes. */
        val SORT_LABELS = intArrayOf(R.string.sort_title, R.string.sort_artist, R.string.sort_recent)
    }
}
