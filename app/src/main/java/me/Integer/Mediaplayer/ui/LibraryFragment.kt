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
import android.content.ActivityNotFoundException
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.AiMashupGenerator
import me.Integer.Mediaplayer.library.AlbumSorter
import me.Integer.Mediaplayer.library.ArtistSorter
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.M3uCodec
import me.Integer.Mediaplayer.library.PlaylistImporter
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.library.TrackSorter
import me.Integer.Mediaplayer.ui.widgets.FastScroller
import me.Integer.Mediaplayer.ui.widgets.PressFeedback
import me.Integer.Mediaplayer.ui.widgets.SkeletonDrawable
import java.io.IOException
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
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var favoritesAdapter: FavoritesShowcaseAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var scanView: TextView
    private lateinit var songsTab: TextView
    private lateinit var favoritesTab: TextView
    private lateinit var artistsTab: TextView
    private lateinit var albumsTab: TextView
    private lateinit var playlistsTab: TextView

    private val pickM3uFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importM3uFromUri(uri)
    }
    private lateinit var addButton: View
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var sortButton: View
    private lateinit var sortIcon: View
    private lateinit var sortLabel: TextView
    private lateinit var shuffleButton: View
    private lateinit var playAllButton: View
    private lateinit var emptyBlock: View
    private lateinit var addFolderButton: View
    private lateinit var mashupChip: TextView
    private lateinit var selectionController: TrackSelectionController
    private val trackSelection = TrackSelection()
    private val mashupListener = AiMashupGenerator.Listener { state -> renderMashupChip(state) }
    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> onFolderPicked(uri) }
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
    private var initialBatchReady = false
    private var shownPlaylistCount = 0
    private var shownFavoritesPlaylistCount = 0
    private var lastRowCount = 0
    private var visibleTracks: List<Track> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LibraryRepository.get(requireContext())
        host = requireActivity() as PlayerHost
        navigator = requireActivity() as Navigator
        activeTab = savedInstanceState?.getInt(KEY_TAB, TAB_SONGS) ?: activeTab
        tabsStyled = false
        initialBatchReady = repository.isLoaded && repository.tracks.isNotEmpty()
        bindViews(view)
        createAdapters()
        selectionController = TrackSelectionController(
            this, view, view.findViewById(R.id.libraryCardHeader), trackAdapter, trackSelection, host, repository
        )
        wireHeader(view)
        wireCard()
        renderTabs()
        if (!initialBatchReady) {
            showSkeleton()
            renderCardHeader()
        }
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
        val generator = AiMashupGenerator.get(requireContext())
        generator.addListener(mashupListener)
        generator.resumeIfPending()
        renderMashupChip(generator.state)
    }

    override fun onStop() {
        AiMashupGenerator.get(requireContext()).removeListener(mashupListener)
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
        // A mashup job interrupted last time can only resume once the library has loaded.
        if (AiMashupGenerator.get(requireContext()).resumeIfPending()) {
            AiMashupDialog.showStatusDialogIfActive(requireContext(), host)
        }
    }

    override fun onScanState(state: LibraryRepository.ScanState) {
        renderScan(state)
        if (state.running && repository.tracks.isEmpty()) {
            showSkeleton()
            renderCardHeader()
        }
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
        albumsTab = view.findViewById(R.id.libraryTabAlbums)
        playlistsTab = view.findViewById(R.id.libraryTabPlaylists)
        addButton = view.findViewById(R.id.libraryAdd)
        searchBar = view.findViewById(R.id.librarySearchBar)
        searchInput = view.findViewById(R.id.librarySearch)
        sortButton = view.findViewById(R.id.librarySort)
        sortIcon = view.findViewById(R.id.librarySortIcon)
        sortLabel = view.findViewById(R.id.librarySortLabel)
        shuffleButton = view.findViewById(R.id.libraryShuffle)
        playAllButton = view.findViewById(R.id.libraryPlayAll)
        emptyBlock = view.findViewById(R.id.libraryEmptyBlock)
        addFolderButton = view.findViewById(R.id.libraryAddFolder)
        mashupChip = view.findViewById(R.id.libraryMashupChip)
        updateLayoutManager()
        FastScroller.attach(list) { index -> sectionLabelAt(index) }
    }

    private fun updateLayoutManager() {
        val wantGrid = activeTab == TAB_PLAYLISTS || activeTab == TAB_ARTISTS || activeTab == TAB_FAVORITES
        val current = list.layoutManager
        val isGrid = current is GridLayoutManager
        if (wantGrid && !isGrid) {
            val grid = GridLayoutManager(requireContext(), GRID_SPAN_COUNT)
            grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (activeTab == TAB_FAVORITES && ::favoritesAdapter.isInitialized) {
                        favoritesAdapter.spanSizeAt(position, GRID_SPAN_COUNT)
                    } else {
                        1
                    }
                }
            }
            list.layoutManager = grid
        } else if (!wantGrid && (current == null || isGrid)) {
            list.layoutManager = LinearLayoutManager(requireContext())
        }
        val horizontalPad = if (wantGrid) (GRID_HORIZONTAL_PAD_DP * resources.displayMetrics.density).toInt() else 0
        if (list.paddingLeft != horizontalPad || list.paddingRight != horizontalPad) {
            list.setPadding(horizontalPad, list.paddingTop, horizontalPad, list.paddingBottom)
        }
    }

    private fun sectionLabelAt(index: Int): String {
        return when (activeTab) {
            TAB_ARTISTS -> FastScroller.initialOf(artistAdapter.rows().getOrNull(index)?.name)
            TAB_ALBUMS -> FastScroller.initialOf(albumAdapter.rows().getOrNull(index)?.name)
            TAB_PLAYLISTS -> FastScroller.initialOf(playlistAdapter.rows().getOrNull(index)?.name)
            TAB_FAVORITES -> FastScroller.initialOf(favoritesAdapter.sectionLabelAt(index))
            else -> {
                val track = trackAdapter.rows().getOrNull(index) ?: return "#"
                if (repository.settings.trackSort == TrackSorter.BY_ARTIST) {
                    FastScroller.initialOf(repository.displayArtist(track))
                } else {
                    FastScroller.initialOf(track.title)
                }
            }
        }
    }

    private fun createAdapters() {
        val thumbnails = ThumbnailLoader.get(requireContext())
        trackAdapter = TrackAdapter(
            thumbnails = thumbnails,
            artistLine = { artistLineOf(it) },
            onClick = { index ->
                if (!selectionController.handleTap(index)) host.launchRow(list, visibleTracks, index)
            },
            onPlayNext = { track -> TrackMenu.playNext(requireContext(), track, host, repository) },
            onMenu = { track, anchor -> TrackMenu.show(anchor, track, host, repository) },
            selection = trackSelection,
            onLongPress = { index -> selectionController.enter(index) }
        )
        artistAdapter = ArtistAdapter(thumbnails) { artist -> navigator.openArtist(artist.name) }
        albumAdapter = AlbumAdapter(thumbnails) { album -> navigator.openAlbum(album.name) }
        val covers = PlaylistCoverLoader.get(requireContext())
        playlistAdapter = PlaylistAdapter(
            covers = covers,
            onClick = { playlist -> navigator.openPlaylist(playlist.id) },
            onMenu = { playlist, anchor -> PlaylistDialogs.showRowMenu(anchor, playlist, host, repository) }
        )
        favoritesAdapter = FavoritesShowcaseAdapter(
            covers = covers,
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
        albumsTab.setOnClickListener { selectTab(TAB_ALBUMS) }
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
        shuffleButton.setOnClickListener { host.launchAll(list, visibleTracks, true) }
        playAllButton.setOnClickListener { host.launchAll(list, visibleTracks, false) }
        PressFeedback.attach(addFolderButton, null, CTA_PRESS_SCALE)
        addFolderButton.setOnClickListener { launchFolderPicker() }
        mashupChip.setOnClickListener { AiMashupDialog.showStatusDialogIfActive(requireContext(), host) }
    }

    // ------------------------------------------------------------------ first run

    private fun launchFolderPicker() {
        try {
            pickFolder.launch(null)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.library_folder_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** A folder picked from the empty library is kept and scanned right away. */
    private fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        if (!repository.folders.add(uri)) {
            Toast.makeText(requireContext(), R.string.library_folder_failed, Toast.LENGTH_LONG).show()
            return
        }
        repository.rescan()
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
        popup.menu.add(0, ID_IMPORT_M3U, 1, R.string.playlist_import_m3u)
        popup.menu.add(0, ID_MASHUP, 1, R.string.library_ai_mashup)
        popup.menu.add(0, ID_RESCAN, 2, R.string.library_rescan)
        popup.menu.add(0, ID_SETTINGS, 3, R.string.library_settings)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_QUEUE -> navigator.openQueue()
                ID_IMPORT -> ImportPlaylistDialog.startFlow(requireContext(), repository, host)
                ID_IMPORT_M3U -> pickM3uFile.launch(arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "*/*"))
                ID_MASHUP -> navigator.openAiMashupMenu()
                ID_RESCAN -> repository.rescan()
                ID_SETTINGS -> navigator.openSettings()
            }
            true
        }
        popup.show()
    }

    private fun showSortMenu(anchor: View) {
        when (activeTab) {
            TAB_ARTISTS -> showArtistSortMenu(anchor)
            TAB_ALBUMS -> showAlbumSortMenu(anchor)
            else -> showTrackSortMenu(anchor)
        }
    }

    private fun showAlbumSortMenu(anchor: View) {
        val current = repository.settings.albumSort
        val popup = PopupMenu(requireContext(), anchor)
        for ((mode, label) in ALBUM_SORT_LABELS.withIndex()) {
            popup.menu.add(0, mode, mode, label).isChecked = mode == current
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            repository.settings.albumSort = item.itemId
            refresh()
            replayRows()
            true
        }
        popup.show()
    }

    private fun importM3uFromUri(uri: Uri) {
        val appContext = requireContext().applicationContext
        worker.execute {
            val text = try {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    String(stream.readBytes(), Charsets.UTF_8)
                }
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            if (text.isNullOrBlank()) {
                main.post { Toast.makeText(appContext, R.string.playlist_m3u_failed, Toast.LENGTH_SHORT).show() }
                return@execute
            }
            val fallback = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Imported"
            val decoded = M3uCodec.decode(text, fallback, repository.tracks)
            main.post {
                if (decoded.tracks.isEmpty()) {
                    Toast.makeText(appContext, R.string.playlist_m3u_empty, Toast.LENGTH_LONG).show()
                    return@post
                }
                val created = if (decoded.segments.size >= 2) {
                    repository.playlists.createMix(decoded.name, decoded.segments)
                } else {
                    repository.playlists.create(decoded.name)?.also { pl ->
                        repository.playlists.addTracks(pl.id, decoded.tracks.map { it.uri })
                    }
                }
                if (created != null) {
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.playlist_m3u_imported, created.name, decoded.tracks.size),
                        Toast.LENGTH_SHORT
                    ).show()
                    if (isAdded) navigator.openPlaylist(created.id)
                }
            }
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
        selectionController.exit()
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
            TAB_ALBUMS -> albumsTab
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
        updateLayoutManager()
        renderTabs()
        renderScan(repository.scanState)
        if ((!repository.isLoaded || repository.scanState.running) && repository.tracks.isEmpty()) {
            refreshGeneration++
            showSkeleton()
            renderCardHeader()
            return
        }
        val gen = ++refreshGeneration
        val libVer = libraryVersion
        val needle = query.trim().lowercase(Locale.ROOT)
        when (activeTab) {
            TAB_ARTISTS -> {
                if (artistAdapter.rows().isEmpty()) showSkeleton()
                renderCardHeader()
                refreshArtists(gen, libVer, needle)
            }
            TAB_ALBUMS -> {
                if (albumAdapter.rows().isEmpty()) showSkeleton()
                renderCardHeader()
                refreshAlbums(gen, libVer, needle)
            }
            TAB_PLAYLISTS -> {
                if (playlistAdapter.rows().isEmpty()) showSkeleton()
                renderCardHeader()
                refreshPlaylists(gen, libVer, needle)
            }
            TAB_FAVORITES -> {
                if (favoritesAdapter.rows().isEmpty()) showSkeleton()
                renderCardHeader()
                refreshFavoritesShowcase(gen, libVer, needle)
            }
            else -> {
                if (trackAdapter.rows().isEmpty()) showSkeleton()
                renderCardHeader()
                refreshSongs(gen, libVer, favoritesOnly = false, needle = needle)
            }
        }
    }

    private fun showSkeleton() {
        emptyBlock.visibility = View.GONE
        when (activeTab) {
            TAB_ARTISTS -> {
                artistAdapter.setLoading(true)
                if (list.adapter !== artistAdapter) list.adapter = artistAdapter
            }
            TAB_ALBUMS -> {
                albumAdapter.setLoading(true)
                if (list.adapter !== albumAdapter) list.adapter = albumAdapter
            }
            TAB_PLAYLISTS -> {
                playlistAdapter.setLoading(true)
                if (list.adapter !== playlistAdapter) list.adapter = playlistAdapter
            }
            TAB_FAVORITES -> {
                favoritesAdapter.setLoading(true)
                if (list.adapter !== favoritesAdapter) list.adapter = favoritesAdapter
            }
            else -> {
                trackAdapter.setLoading(true)
                if (list.adapter !== trackAdapter) list.adapter = trackAdapter
            }
        }
    }

    private fun refreshSongs(gen: Long, libVer: Long, favoritesOnly: Boolean, needle: String) {
        val trackSort = repository.settings.trackSort
        val oldRows = if (list.adapter === trackAdapter && !trackAdapter.isLoading) trackAdapter.rows() else emptyList()
        val canDiff = oldRows.isNotEmpty() && lastDiffLibVersion == libVer
        worker.execute {
            val source = if (favoritesOnly) repository.favoriteTracks() else repository.tracks
            val matching = if (needle.isEmpty()) {
                source
            } else {
                source.filter { track ->
                    track.title.lowercase(Locale.ROOT).contains(needle) ||
                        track.artistTag.lowercase(Locale.ROOT).contains(needle)
                }
            }
            val artistLines = matching.associate { it.uri to repository.displayArtist(it) }
            val sorted = TrackSorter.sort(matching, trackSort) { artistLines[it.uri].orEmpty() }
            val diff = if (canDiff && sorted.isNotEmpty()) TrackAdapter.diff(oldRows, sorted) else null
            main.post {
                if (!isAdded || gen != refreshGeneration) return@post
                initialBatchReady = true
                lastDiffLibVersion = libVer
                visibleTracks = sorted
                trackAdapter.submit(sorted, diff)
                if (list.adapter !== trackAdapter) list.adapter = trackAdapter
                selectionController.onRowsChanged()
                onRowsReady(sorted.size)
            }
        }
    }

    private fun refreshArtists(gen: Long, libVer: Long, needle: String) {
        val artistSort = repository.settings.artistSort
        val oldRows = if (list.adapter === artistAdapter && !artistAdapter.isLoading) artistAdapter.rows() else emptyList()
        val canDiff = oldRows.isNotEmpty() && lastDiffLibVersion == libVer
        worker.execute {
            val source = repository.artists()
            val matching = if (needle.isEmpty()) {
                source
            } else {
                source.filter { artist -> artist.name.lowercase(Locale.ROOT).contains(needle) }
            }
            val sorted = ArtistSorter.sort(matching, artistSort)
            val diff = if (canDiff && sorted.isNotEmpty()) ArtistAdapter.diff(oldRows, sorted) else null
            main.post {
                if (!isAdded || gen != refreshGeneration) return@post
                initialBatchReady = true
                lastDiffLibVersion = libVer
                artistAdapter.submit(sorted, diff)
                if (list.adapter !== artistAdapter) list.adapter = artistAdapter
                onRowsReady(sorted.size)
            }
        }
    }

    private fun refreshAlbums(gen: Long, libVer: Long, needle: String) {
        val albumSort = repository.settings.albumSort
        val oldRows = if (list.adapter === albumAdapter && !albumAdapter.isLoading) albumAdapter.rows() else emptyList()
        val canDiff = oldRows.isNotEmpty() && lastDiffLibVersion == libVer
        worker.execute {
            val source = repository.albums()
            val matching = if (needle.isEmpty()) {
                source
            } else {
                source.filter { album ->
                    album.name.lowercase(Locale.ROOT).contains(needle) ||
                        album.artist.lowercase(Locale.ROOT).contains(needle)
                }
            }
            val sorted = AlbumSorter.sort(matching, albumSort)
            val diff = if (canDiff && sorted.isNotEmpty()) AlbumAdapter.diff(oldRows, sorted) else null
            main.post {
                if (!isAdded || gen != refreshGeneration) return@post
                initialBatchReady = true
                lastDiffLibVersion = libVer
                albumAdapter.submit(sorted, diff)
                if (list.adapter !== albumAdapter) list.adapter = albumAdapter
                onRowsReady(sorted.size)
            }
        }
    }

    private fun refreshFavoritesShowcase(gen: Long, libVer: Long, needle: String) {
        val recapsHeader = getString(R.string.sys_section_recaps)
        worker.execute {
            val showcase = repository.favoritesShowcase()
            val pinned = if (needle.isEmpty()) {
                showcase.pinned
            } else {
                showcase.pinned.filter { it.name.lowercase(Locale.ROOT).contains(needle) }
            }
            val recaps = if (needle.isEmpty()) {
                showcase.recaps
            } else {
                showcase.recaps.filter { it.name.lowercase(Locale.ROOT).contains(needle) }
            }
            val rows = ArrayList<FavoritesShowcaseAdapter.Row>(pinned.size + recaps.size + 1)
            for (playlist in pinned) {
                rows.add(FavoritesShowcaseAdapter.Row.PlaylistCard(playlist))
            }
            if (recaps.isNotEmpty()) {
                rows.add(FavoritesShowcaseAdapter.Row.SectionHeader(recapsHeader))
                for (recap in recaps) {
                    rows.add(FavoritesShowcaseAdapter.Row.PlaylistCard(recap))
                }
            }
            val totalCards = pinned.size + recaps.size
            main.post {
                if (!isAdded || gen != refreshGeneration) return@post
                initialBatchReady = true
                shownFavoritesPlaylistCount = totalCards
                lastDiffLibVersion = libVer
                favoritesAdapter.submit(rows)
                if (list.adapter !== favoritesAdapter) list.adapter = favoritesAdapter
                onRowsReady(totalCards)
            }
        }
    }

    private fun refreshPlaylists(gen: Long, libVer: Long, needle: String) {
        val oldRows = if (list.adapter === playlistAdapter && !playlistAdapter.isLoading) playlistAdapter.rows() else emptyList()
        val canDiff = oldRows.isNotEmpty() && lastDiffLibVersion == libVer
        worker.execute {
            val source = repository.playlists.all()
            val matching = if (needle.isEmpty()) {
                source
            } else {
                source.filter { playlist -> playlist.name.lowercase(Locale.ROOT).contains(needle) }
            }
            val diff = if (canDiff && matching.isNotEmpty()) PlaylistAdapter.diff(oldRows, matching) else null
            main.post {
                if (!isAdded || gen != refreshGeneration) return@post
                initialBatchReady = true
                shownPlaylistCount = source.size
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
        styleTab(albumsTab, activeTab == TAB_ALBUMS)
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

    private fun isHeaderSkeletonActive(): Boolean {
        return !initialBatchReady || ((!repository.isLoaded || repository.scanState.running) && repository.tracks.isEmpty())
    }

    private fun renderCardHeader() {
        val showingSongs = activeTab == TAB_SONGS
        val showingPlaylists = activeTab == TAB_PLAYLISTS
        val showingFavorites = activeTab == TAB_FAVORITES
        val buttons = if (showingSongs) View.VISIBLE else View.GONE
        shuffleButton.visibility = buttons
        playAllButton.visibility = buttons
        addButton.visibility = if (showingPlaylists) View.VISIBLE else View.GONE
        val loadingHeader = isHeaderSkeletonActive()
        if (loadingHeader) {
            sortIcon.visibility = View.GONE
            sortButton.isClickable = false
            sortLabel.text = ""
            sortLabel.background = SkeletonDrawable.bar(requireContext(), 6f)
            val params = sortLabel.layoutParams
            val targetWidth = (110 * resources.displayMetrics.density).toInt()
            if (params.width != targetWidth) {
                params.width = targetWidth
                sortLabel.layoutParams = params
            }
        } else {
            sortLabel.background = null
            val params = sortLabel.layoutParams
            if (params.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                sortLabel.layoutParams = params
            }
            val nonSortable = showingPlaylists || showingFavorites
            sortIcon.visibility = if (nonSortable) View.GONE else View.VISIBLE
            sortButton.isClickable = !nonSortable
            sortLabel.text = when {
                showingSongs -> getString(TRACK_SORT_LABELS[repository.settings.trackSort])
                showingPlaylists ->
                    resources.getQuantityString(R.plurals.library_playlist_count, shownPlaylistCount, shownPlaylistCount)
                showingFavorites ->
                    resources.getQuantityString(
                        R.plurals.library_playlist_count,
                        shownFavoritesPlaylistCount,
                        shownFavoritesPlaylistCount
                    )
                activeTab == TAB_ALBUMS -> getString(ALBUM_SORT_LABELS[repository.settings.albumSort])
                else -> getString(ARTIST_SORT_LABELS[repository.settings.artistSort])
            }
        }
        val usable = !loadingHeader && visibleTracks.isNotEmpty()
        shuffleButton.isEnabled = usable
        playAllButton.isEnabled = usable
        shuffleButton.alpha = if (usable) 1f else DISABLED_ALPHA
        playAllButton.alpha = if (usable) 1f else DISABLED_ALPHA
    }

    private fun renderEmpty(shownRows: Int) {
        if (isHeaderSkeletonActive()) {
            emptyBlock.visibility = View.GONE
            return
        }
        emptyBlock.visibility = if (shownRows == 0) View.VISIBLE else View.GONE
        if (shownRows > 0) return
        val noSongs = repository.tracks.isEmpty()
        val message = when {
            noSongs -> R.string.library_empty
            query.isNotBlank() -> R.string.library_no_match
            activeTab == TAB_FAVORITES -> R.string.library_favorites_empty
            activeTab == TAB_PLAYLISTS -> R.string.library_playlists_empty
            else -> R.string.library_no_match
        }
        emptyView.setText(message)
        addFolderButton.visibility = if (noSongs) View.VISIBLE else View.GONE
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

    /** A chip under the header follows a running or finished AI mashup job. */
    private fun renderMashupChip(state: AiMashupGenerator.State) {
        if (!isAdded || view == null) return
        val status = mashupStatusOf(state)
        mashupChip.visibility = if (status == null) View.GONE else View.VISIBLE
        if (status != null) mashupChip.text = getString(R.string.mashup_chip, status)
    }

    private fun mashupStatusOf(state: AiMashupGenerator.State): String? {
        return when (state.phase) {
            AiMashupGenerator.Phase.IDLE -> null
            AiMashupGenerator.Phase.CONNECTING -> getString(R.string.mashup_connecting)
            AiMashupGenerator.Phase.UPLOADING -> getString(R.string.mashup_uploading, state.done, state.total)
            AiMashupGenerator.Phase.ANALYSING -> getString(R.string.mashup_analysing, state.done, state.total)
            AiMashupGenerator.Phase.ROUTING -> getString(R.string.mashup_routing)
            AiMashupGenerator.Phase.COMPLETED ->
                resources.getQuantityString(R.plurals.mashup_ready, state.mixes.size, state.mixes.size)
            AiMashupGenerator.Phase.FAILED -> getString(R.string.mashup_failed)
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
        const val TAB_ALBUMS = 4

        const val ID_QUEUE = 1
        const val ID_RESCAN = 2
        const val ID_SETTINGS = 3
        const val ID_IMPORT = 4
        const val ID_MASHUP = 5
        const val ID_IMPORT_M3U = 6
        const val TAB_SELECTED_SP = 28f
        const val TAB_IDLE_SP = 17f
        const val TAB_ANIMATION_MS = 220L
        const val GRID_SPAN_COUNT = 3
        const val GRID_HORIZONTAL_PAD_DP = 11f
        const val ROUND_PRESS_SCALE = 0.9f
        const val CTA_PRESS_SCALE = 0.95f
        const val DISABLED_ALPHA = 0.35f

        /** Indexed by the TrackSorter modes. */
        val TRACK_SORT_LABELS = intArrayOf(R.string.sort_title, R.string.sort_artist, R.string.sort_recent)

        /** Indexed by the ArtistSorter modes. */
        val ARTIST_SORT_LABELS = intArrayOf(R.string.sort_artist_name, R.string.sort_artist_count)

        /** Indexed by the AlbumSorter modes. */
        val ALBUM_SORT_LABELS = intArrayOf(R.string.sort_artist_name, R.string.sort_artist, R.string.sort_artist_count)
    }
}
