package me.Integer.Mediaplayer.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import me.Integer.Mediaplayer.library.ArtistSorter
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.PlaylistImporter
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.library.TrackSorter
import me.Integer.Mediaplayer.ui.widgets.PressFeedback
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The song picker: header with search and menu, Tracks / Favorites / Artists tabs,
 * sort options (including sorting artists by song count), and the list card.
 */
class LibraryFragment : Fragment(R.layout.fragment_library), LibraryRepository.Listener, PlayerHost.Listener {

    private lateinit var repository: LibraryRepository
    private lateinit var host: PlayerHost
    private lateinit var navigator: Navigator
    private lateinit var trackAdapter: TrackAdapter
    private lateinit var artistAdapter: ArtistAdapter
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var scanView: TextView
    private lateinit var songsTab: TextView
    private lateinit var favoritesTab: TextView
    private lateinit var artistsTab: TextView
    private lateinit var playlistsTab: TextView
    private lateinit var addButton: View
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var sortButton: View
    private lateinit var sortIcon: View
    private lateinit var sortLabel: TextView
    private lateinit var shuffleButton: View
    private lateinit var playAllButton: View
    private val tabAnimators = HashMap<TextView, ValueAnimator>()

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Mediaplayer-LibraryFilter").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var refreshGeneration = 0L
    private var libraryVersion = 0L
    private var lastDiffLibVersion = -1L

    private var activeTab = TAB_SONGS
    private var query = ""
    private var tabsStyled = false
    private var lastRowCount = 0
    private var visibleTracks: List<Track> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LibraryRepository.get(requireContext())
        host = requireActivity() as PlayerHost
        navigator = requireActivity() as Navigator
        activeTab = savedInstanceState?.getInt(KEY_TAB, TAB_SONGS) ?: activeTab
        tabsStyled = false
        bindViews(view)
        createAdapters()
        wireHeader(view)
        wireCard()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, activeTab)
    }

    override fun onStart() {
        super.onStart()
        repository.addListener(this)
        host.addPlayerListener(this)
        if (query.isNotEmpty()) searchBar.visibility = View.VISIBLE
        refresh()
        onPlayerChanged()
        PlaylistImporter.get(requireContext()).onAppResumed()
        ImportPlaylistDialog.showStatusDialogIfActive(requireContext())
    }

    override fun onStop() {
        host.removePlayerListener(this)
        repository.removeListener(this)
        for (animator in tabAnimators.values) animator.end()
        tabAnimators.clear()
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onLibraryChanged() {
        libraryVersion++
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
        favoritesTab = view.findViewById(R.id.libraryTabFavorites)
        artistsTab = view.findViewById(R.id.libraryTabArtists)
        playlistsTab = view.findViewById(R.id.libraryTabPlaylists)
        addButton = view.findViewById(R.id.libraryAdd)
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
            onPlayNext = { track -> TrackMenu.playNext(requireContext(), track, host, repository) },
            onMenu = { track, anchor -> TrackMenu.show(anchor, track, host, repository) }
        )
        artistAdapter = ArtistAdapter(thumbnails) { artist -> navigator.openArtist(artist.name) }
        playlistAdapter = PlaylistAdapter(
            covers = PlaylistCoverLoader.get(requireContext()),
            onClick = { playlist -> navigator.openPlaylist(playlist.id) },
            onMenu = { playlist, anchor -> PlaylistDialogs.showRowMenu(anchor, playlist, host, repository) }
        )
    }

    private fun wireHeader(view: View) {
        view.findViewById<View>(R.id.librarySearchButton).setOnClickListener { openSearch() }
        view.findViewById<View>(R.id.librarySearchClose).setOnClickListener { closeSearch() }
        view.findViewById<View>(R.id.libraryMoreButton).setOnClickListener { showOverflow(it) }
        searchInput.doAfterTextChanged { text ->
            query = text?.toString().orEmpty()
            refresh()
        }
        songsTab.setOnClickListener { selectTab(TAB_SONGS) }
        favoritesTab.setOnClickListener { selectTab(TAB_FAVORITES) }
        artistsTab.setOnClickListener { selectTab(TAB_ARTISTS) }
        playlistsTab.setOnClickListener { selectTab(TAB_PLAYLISTS) }
    }

    private fun wireCard() {
        sortButton.setOnClickListener { showSortMenu(it) }
        PressFeedback.attach(addButton, null, ROUND_PRESS_SCALE)
        addButton.setOnClickListener {
            PlaylistDialogs.create(requireContext(), repository) { created -> navigator.openPlaylist(created.id) }
        }
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
        popup.menu.add(0, ID_QUEUE, 0, R.string.menu_current_queue)
        popup.menu.add(0, ID_IMPORT, 1, R.string.library_import_playlist)
        popup.menu.add(0, ID_RESCAN, 2, R.string.library_rescan)
        popup.menu.add(0, ID_SETTINGS, 3, R.string.library_settings)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_QUEUE -> navigator.openQueue()
                ID_IMPORT -> ImportPlaylistDialog.startFlow(requireContext(), repository, host)
                ID_RESCAN -> repository.rescan()
                ID_SETTINGS -> navigator.openSettings()
            }
            true
        }
        popup.show()
    }

    private fun showSortMenu(anchor: View) {
        if (activeTab == TAB_ARTISTS) {
            showArtistSortMenu(anchor)
        } else {
            showTrackSortMenu(anchor)
        }
    }

    private fun showTrackSortMenu(anchor: View) {
        val current = repository.settings.trackSort
        val popup = PopupMenu(requireContext(), anchor)
        for ((mode, label) in TRACK_SORT_LABELS.withIndex()) {
            popup.menu.add(0, mode, mode, label).isChecked = mode == current
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            repository.settings.trackSort = item.itemId
            refresh()
            replayRows()
            true
        }
        popup.show()
    }

    private fun showArtistSortMenu(anchor: View) {
        val current = repository.settings.artistSort
        val popup = PopupMenu(requireContext(), anchor)
        for ((mode, label) in ARTIST_SORT_LABELS.withIndex()) {
            popup.menu.add(0, mode, mode, label).isChecked = mode == current
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            repository.settings.artistSort = item.itemId
            refresh()
            replayRows()
            true
        }
        popup.show()
    }

    private fun selectTab(tab: Int) {
        if (activeTab == tab) return
        activeTab = tab
        refresh()
        replayRows()
        revealTab(tabView(tab))
    }

    private fun tabView(tab: Int): TextView {
        return when (tab) {
            TAB_FAVORITES -> favoritesTab
            TAB_PLAYLISTS -> playlistsTab
            TAB_ARTISTS -> artistsTab
            else -> songsTab
        }
    }

    /** Scrolls the tab row so the chosen tab is in view once it has grown. */
    private fun revealTab(tab: TextView) {
        tab.postDelayed({
            val scroller = tab.parent?.parent as? android.widget.HorizontalScrollView
            scroller?.smoothScrollTo(maxOf(0, tab.left - tab.paddingLeft), 0)
        }, TAB_ANIMATION_MS)
    }

    /** New rows rise in one after another instead of popping in. */
    private fun replayRows() {
        list.scrollToPosition(0)
        list.scheduleLayoutAnimation()
    }

    // ------------------------------------------------------------------ rendering

    private fun refresh() {
        renderTabs()
        renderCardHeader()
        renderScan(repository.scanState)
        if (activeTab != TAB_PLAYLISTS && !repository.isLoaded && repository.tracks.isEmpty()) {
            refreshGeneration++
            showSkeleton()
            return
        }
        val gen = ++refreshGeneration
        val libVer = libraryVersion
        val needle = query.trim().lowercase(Locale.ROOT)
        when (activeTab) {
            TAB_ARTISTS -> refreshArtists(gen, libVer, needle)
            TAB_PLAYLISTS -> refreshPlaylists(gen, libVer, needle)
            TAB_FAVORITES -> refreshSongs(gen, libVer, repository.favoriteTracks(), needle)
            else -> refreshSongs(gen, libVer, repository.tracks, needle)
        }
    }

    private fun showSkeleton() {
        emptyView.visibility = View.GONE
        if (activeTab == TAB_ARTISTS) {
            artistAdapter.setLoading(true)
            if (list.adapter !== artistAdapter) list.adapter = artistAdapter
        } else {
            trackAdapter.setLoading(true)
            if (list.adapter !== trackAdapter) list.adapter = trackAdapter
        }
    }

    private fun refreshSongs(gen: Long, libVer: Long, source: List<Track>, needle: String) {
        val trackSort = repository.settings.trackSort
        val artistLines = source.associate { it.uri to repository.displayArtist(it) }
        val oldRows = if (list.adapter === trackAdapter && !trackAdapter.isLoading) trackAdapter.rows() else emptyList()
        val canDiff = oldRows.isNotEmpty() && lastDiffLibVersion == libVer
        worker.execute {
            val matching = if (needle.isEmpty()) {
                source
            } else {
                source.filter { track ->
                    track.title.lowercase(Locale.ROOT).contains(needle) ||
                        track.artistTag.lowercase(Locale.ROOT).contains(needle)
                }
            }
            val sorted = TrackSorter.sort(matching, trackSort) { artistLines[it.uri].orEmpty() }
            val diff = if (canDiff && sorted.isNotEmpty()) TrackAdapter.diff(oldRows, sorted) else null
            main.post {
                if (!isAdded || gen != refreshGeneration) return@post
                lastDiffLibVersion = libVer
                visibleTracks = sorted
                trackAdapter.submit(sorted, diff)
                if (list.adapter !== trackAdapter) list.adapter = trackAdapter
                onRowsReady(sorted.size)
            }
        }
    }

    private fun refreshArtists(gen: Long, libVer: Long, needle: String) {
        val source = repository.artists()
        val artistSort = repository.settings.artistSort
        val oldRows = if (list.adapter === artistAdapter && !artistAdapter.isLoading) artistAdapter.rows() else emptyList()
        val canDiff = oldRows.isNotEmpty() && lastDiffLibVersion == libVer
        worker.execute {
            val matching = if (needle.isEmpty()) {
                source
            } else {
                source.filter { artist -> artist.name.lowercase(Locale.ROOT).contains(needle) }
            }
            val sorted = ArtistSorter.sort(matching, artistSort)
            val diff = if (canDiff && sorted.isNotEmpty()) ArtistAdapter.diff(oldRows, sorted) else null
            main.post {
                if (!isAdded || gen != refreshGeneration) return@post
                lastDiffLibVersion = libVer
                artistAdapter.submit(sorted, diff)
                if (list.adapter !== artistAdapter) list.adapter = artistAdapter
                onRowsReady(sorted.size)
            }
        }
    }

    private fun refreshPlaylists(gen: Long, libVer: Long, needle: String) {
        val source = repository.playlists.all()
        val oldRows = if (list.adapter === playlistAdapter) playlistAdapter.rows() else emptyList()
        val canDiff = oldRows.isNotEmpty() && lastDiffLibVersion == libVer
        worker.execute {
            val matching = if (needle.isEmpty()) {
                source
            } else {
                source.filter { playlist -> playlist.name.lowercase(Locale.ROOT).contains(needle) }
            }
            val diff = if (canDiff && matching.isNotEmpty()) PlaylistAdapter.diff(oldRows, matching) else null
            main.post {
                if (!isAdded || gen != refreshGeneration) return@post
                lastDiffLibVersion = libVer
                playlistAdapter.submit(matching, diff)
                if (list.adapter !== playlistAdapter) list.adapter = playlistAdapter
                onRowsReady(matching.size)
            }
        }
    }

    private fun onRowsReady(shown: Int) {
        if (lastRowCount == 0 && shown > 0) list.scheduleLayoutAnimation()
        lastRowCount = shown
        renderCardHeader()
        renderEmpty(shown)
    }

    private fun renderTabs() {
        styleTab(songsTab, activeTab == TAB_SONGS)
        styleTab(favoritesTab, activeTab == TAB_FAVORITES)
        styleTab(artistsTab, activeTab == TAB_ARTISTS)
        styleTab(playlistsTab, activeTab == TAB_PLAYLISTS)
        tabsStyled = true
    }

    /** The selected tab grows large and white, the others shrink and fade to grey. */
    private fun styleTab(tab: TextView, selected: Boolean) {
        val metrics = resources.displayMetrics
        val targetPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, if (selected) TAB_SELECTED_SP else TAB_IDLE_SP, metrics
        )
        val colorRes = if (selected) R.color.text_primary else R.color.text_muted
        val targetColor = ContextCompat.getColor(requireContext(), colorRes)
        tabAnimators.remove(tab)?.cancel()
        if (!tabsStyled) {
            tab.setTextColor(targetColor)
            tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetPx)
            return
        }
        val startPx = tab.textSize
        val startColor = tab.currentTextColor
        if (abs(startPx - targetPx) < 0.5f && startColor == targetColor) return
        // Size and colour blend together from wherever the tab is right now.
        val blend = ArgbEvaluator()
        val morph = ValueAnimator.ofFloat(0f, 1f)
        morph.duration = TAB_ANIMATION_MS
        morph.interpolator = DecelerateInterpolator()
        morph.addUpdateListener {
            val t = it.animatedFraction
            tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, startPx + (targetPx - startPx) * t)
            tab.setTextColor(blend.evaluate(t, startColor, targetColor) as Int)
        }
        tabAnimators[tab] = morph
        morph.start()
    }

    private fun renderCardHeader() {
        val showingSongs = activeTab == TAB_SONGS || activeTab == TAB_FAVORITES
        val showingPlaylists = activeTab == TAB_PLAYLISTS
        val buttons = if (showingSongs) View.VISIBLE else View.GONE
        shuffleButton.visibility = buttons
        playAllButton.visibility = buttons
        addButton.visibility = if (showingPlaylists) View.VISIBLE else View.GONE
        sortIcon.visibility = if (showingPlaylists) View.GONE else View.VISIBLE
        sortButton.isClickable = !showingPlaylists
        val playlistCount = if (showingPlaylists) repository.playlists.all().size else 0
        sortLabel.text = when {
            showingSongs -> getString(TRACK_SORT_LABELS[repository.settings.trackSort])
            showingPlaylists ->
                resources.getQuantityString(R.plurals.library_playlist_count, playlistCount, playlistCount)
            else -> getString(ARTIST_SORT_LABELS[repository.settings.artistSort])
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
        val message = when {
            repository.tracks.isEmpty() -> R.string.library_empty
            query.isNotBlank() -> R.string.library_no_match
            activeTab == TAB_FAVORITES -> R.string.library_favorites_empty
            activeTab == TAB_PLAYLISTS -> R.string.library_playlists_empty
            else -> R.string.library_no_match
        }
        emptyView.setText(message)
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
        const val KEY_TAB = "active_tab"
        const val TAB_SONGS = 0
        const val TAB_FAVORITES = 1
        const val TAB_ARTISTS = 2
        const val TAB_PLAYLISTS = 3

        const val ID_QUEUE = 1
        const val ID_RESCAN = 2
        const val ID_SETTINGS = 3
        const val ID_IMPORT = 4
        const val TAB_SELECTED_SP = 28f
        const val TAB_IDLE_SP = 17f
        const val TAB_ANIMATION_MS = 220L
        const val ROUND_PRESS_SCALE = 0.9f
        const val DISABLED_ALPHA = 0.35f

        /** Indexed by the TrackSorter modes. */
        val TRACK_SORT_LABELS = intArrayOf(R.string.sort_title, R.string.sort_artist, R.string.sort_recent)

        /** Indexed by the ArtistSorter modes. */
        val ARTIST_SORT_LABELS = intArrayOf(R.string.sort_artist_name, R.string.sort_artist_count)
    }
}
