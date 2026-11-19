package me.Integer.Mediaplayer.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.AiMashupGenerator
import me.Integer.Mediaplayer.library.AiMashupGenerator.Phase
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track

/**
 * Submenu opened when the user selects "Generate AI mashups".
 * Lets the user choose between Local Server Mashup and LLM Suggested Playlists
 * (LLM Generated Mashup), with room for future generation modes.
 */
class AiMashupMenuFragment : Fragment(R.layout.fragment_ai_mashup_menu), LibraryRepository.Listener {

    private lateinit var repository: LibraryRepository
    private lateinit var host: PlayerHost
    private lateinit var scopeSummary: TextView
    private lateinit var localStatusView: TextView
    private lateinit var llmStatusView: TextView

    private var sourcePlaylistId: Long = NO_PLAYLIST

    private val mashupListener = AiMashupGenerator.Listener { state ->
        if (isAdded) renderLocalServerStatus(state)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LibraryRepository.get(requireContext())
        host = requireActivity() as PlayerHost
        sourcePlaylistId = arguments?.getLong(ARG_PLAYLIST_ID, NO_PLAYLIST) ?: NO_PLAYLIST

        scopeSummary = view.findViewById(R.id.aiMenuScopeSummary)
        localStatusView = view.findViewById(R.id.aiMenuLocalStatus)
        llmStatusView = view.findViewById(R.id.aiMenuLlmStatus)

        view.findViewById<View>(R.id.aiMenuBack).setOnClickListener {
            (requireActivity() as Navigator).goBack()
        }
        view.findViewById<View>(R.id.aiMenuRowLocalServer).setOnClickListener {
            openLocalServerMashup()
        }
        view.findViewById<View>(R.id.aiMenuRowLlmPlaylists).setOnClickListener {
            LlmPlaylistDialog.show(requireContext(), repository, sourcePlaylistId)
        }
    }

    override fun onStart() {
        super.onStart()
        repository.addListener(this)
        val generator = AiMashupGenerator.get(requireContext())
        generator.addListener(mashupListener)
        renderScope()
        renderLocalServerStatus(generator.state)
    }

    override fun onStop() {
        AiMashupGenerator.get(requireContext()).removeListener(mashupListener)
        repository.removeListener(this)
        super.onStop()
    }

    override fun onLibraryChanged() {
        renderScope()
        renderLocalServerStatus(AiMashupGenerator.get(requireContext()).state)
    }

    override fun onScanState(state: LibraryRepository.ScanState) = Unit

    private fun openLocalServerMashup() {
        val scopedTracks = scopedPlaylistTracks()
        if (scopedTracks != null) {
            AiMashupDialog.startFrom(requireContext(), repository, host, scopedTracks)
        } else {
            AiMashupDialog.startFlow(requireContext(), repository, host)
        }
    }

    private fun scopedPlaylistTracks(): List<Track>? {
        if (sourcePlaylistId == NO_PLAYLIST) return null
        val playlist = repository.playlists.get(sourcePlaylistId) ?: return null
        return repository.resolveTracks(playlist.trackUris)
    }

    private fun renderScope() {
        val playlist = if (sourcePlaylistId != NO_PLAYLIST) repository.playlists.get(sourcePlaylistId) else null
        val tracks = if (playlist != null) repository.resolveTracks(playlist.trackUris) else repository.tracks
        val songCountText = resources.getQuantityString(R.plurals.library_song_count, tracks.size, tracks.size)
        scopeSummary.text = if (playlist != null) {
            getString(R.string.ai_menu_scope_playlist, playlist.name, songCountText)
        } else {
            getString(R.string.ai_menu_scope_device, songCountText)
        }
        llmStatusView.text = getString(R.string.ai_menu_llm_action)
    }

    private fun renderLocalServerStatus(state: AiMashupGenerator.State) {
        val url = repository.settings.aiServerUrl
        localStatusView.text = when (state.phase) {
            Phase.CONNECTING -> getString(R.string.mashup_connecting)
            Phase.UPLOADING -> getString(R.string.mashup_uploading, state.done, state.total)
            Phase.ANALYSING -> getString(R.string.mashup_analysing, state.done, state.total)
            Phase.ROUTING -> getString(R.string.mashup_routing)
            Phase.COMPLETED -> resources.getQuantityString(R.plurals.mashup_ready, state.mixes.size, state.mixes.size)
            Phase.FAILED, Phase.IDLE -> {
                if (url.isEmpty()) getString(R.string.settings_ai_not_configured) else url
            }
        }
    }

    companion object {
        const val NO_PLAYLIST = -1L
        private const val ARG_PLAYLIST_ID = "playlist_id"

        fun create(sourcePlaylistId: Long = NO_PLAYLIST): AiMashupMenuFragment {
            return AiMashupMenuFragment().apply {
                arguments = bundleOf(ARG_PLAYLIST_ID to sourcePlaylistId)
            }
        }
    }
}
