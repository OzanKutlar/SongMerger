package me.Integer.Mediaplayer.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.AiMashupClient
import me.Integer.Mediaplayer.library.AiServerDiscovery
import me.Integer.Mediaplayer.library.ArtistSplitter
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.MusicFolderStore
import me.Integer.Mediaplayer.library.PlayerSettings
import java.io.IOException
import java.util.concurrent.Executors

/** Music folders, the artist separator, crossfade, and the AI mashup server. */
class SettingsFragment : Fragment(R.layout.fragment_settings), LibraryRepository.Listener {

    private lateinit var repository: LibraryRepository
    private lateinit var folderList: LinearLayout
    private lateinit var scanView: TextView
    private lateinit var rescanButton: View
    private lateinit var separatorInput: EditText
    private lateinit var previewView: TextView
    private lateinit var aiServerInput: EditText
    private lateinit var aiStatusView: TextView
    private lateinit var aiTokenInput: EditText
    private lateinit var crossfadeBar: SeekBar
    private lateinit var crossfadeLabel: TextView
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Mediaplayer-AiServerTest").apply { isDaemon = true }
    }

    /** Answers from an older test carry an older number and are dropped. */
    private var testGeneration = 0L

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> onFolderPicked(uri) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LibraryRepository.get(requireContext())
        folderList = view.findViewById(R.id.settingsFolders)
        scanView = view.findViewById(R.id.settingsScan)
        rescanButton = view.findViewById(R.id.settingsRescan)
        separatorInput = view.findViewById(R.id.settingsSeparator)
        previewView = view.findViewById(R.id.settingsPreview)
        view.findViewById<View>(R.id.settingsBack).setOnClickListener { (requireActivity() as Navigator).goBack() }
        view.findViewById<View>(R.id.settingsAddFolder).setOnClickListener { pickFolder.launch(null) }
        view.findViewById<View>(R.id.settingsApply).setOnClickListener { applySeparator() }
        rescanButton.setOnClickListener { repository.rescan() }
        if (savedInstanceState == null) separatorInput.setText(repository.settings.artistSeparator)
        separatorInput.doAfterTextChanged { renderPreview() }
        wireAiServer(view, savedInstanceState)
        wireCrossfade(view)
    }

    override fun onStart() {
        super.onStart()
        repository.addListener(this)
        renderFolders()
        renderScan(repository.scanState)
        renderPreview()
    }

    override fun onStop() {
        repository.removeListener(this)
        super.onStop()
    }

    override fun onLibraryChanged() {
        renderPreview()
    }

    override fun onScanState(state: LibraryRepository.ScanState) {
        renderScan(state)
    }

    // ------------------------------------------------------------------ folders

    private fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        if (!repository.folders.add(uri)) {
            Toast.makeText(requireContext(), R.string.library_folder_failed, Toast.LENGTH_LONG).show()
            return
        }
        renderFolders()
        repository.rescan()
    }

    private fun removeFolder(folder: MusicFolderStore.Folder) {
        repository.folders.remove(folder.uri)
        renderFolders()
        repository.rescan()
    }

    private fun renderFolders() {
        folderList.removeAllViews()
        val folders = repository.folders.folders()
        if (folders.isEmpty()) {
            val hint = TextView(requireContext())
            hint.setText(R.string.settings_no_folders)
            folderList.addView(hint)
            return
        }
        val inflater = LayoutInflater.from(requireContext())
        for (folder in folders) {
            val row = inflater.inflate(R.layout.item_folder, folderList, false)
            row.findViewById<TextView>(R.id.folderName).text = folder.name
            row.findViewById<View>(R.id.folderRemove).setOnClickListener { removeFolder(folder) }
            folderList.addView(row)
        }
    }

    private fun renderScan(state: LibraryRepository.ScanState) {
        rescanButton.isEnabled = !state.running
        scanView.text = when {
            !state.running -> resources.getQuantityString(
                R.plurals.library_song_count, repository.tracks.size, repository.tracks.size
            )
            state.total <= 0 -> getString(R.string.library_scanning_folders)
            else -> getString(R.string.library_scanning, state.done, state.total)
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ AI mashup server

    private fun wireAiServer(view: View, savedInstanceState: Bundle?) {
        aiServerInput = view.findViewById(R.id.settingsAiServer)
        aiStatusView = view.findViewById(R.id.settingsAiStatus)
        aiTokenInput = view.findViewById(R.id.settingsAiToken)
        if (savedInstanceState == null) {
            aiServerInput.setText(repository.settings.aiServerUrl)
            aiTokenInput.setText(repository.settings.aiServerToken)
        }
        view.findViewById<View>(R.id.settingsAiSave).setOnClickListener { saveAiServer() }
        view.findViewById<View>(R.id.settingsAiTest).setOnClickListener { testAiServer() }
        view.findViewById<View>(R.id.settingsAiDiscover).setOnClickListener { discoverAiServer() }
    }

    /** Broadcasts a LAN UDP probe to locate a running ai_router/server.py on Wi-Fi. */
    private fun discoverAiServer() {
        val run = ++testGeneration
        aiStatusView.setText(R.string.settings_ai_discovering)
        worker.execute {
            val found = AiServerDiscovery.discover()
            main.post {
                if (!isAdded || view == null || run != testGeneration) return@post
                if (found != null) {
                    aiServerInput.setText(found.url)
                    repository.settings.aiServerUrl = found.url
                    aiStatusView.text = getString(R.string.settings_ai_discovered, found.url, found.device)
                } else {
                    aiStatusView.setText(R.string.settings_ai_not_found)
                }
            }
        }
    }

    /** A blank field clears the address; an unusable one is refused. */
    private fun saveAiServer() {
        val typed = aiServerInput.text?.toString().orEmpty()
        val clean = PlayerSettings.sanitiseServerUrl(typed)
        if (typed.isNotBlank() && clean.isEmpty()) {
            aiStatusView.setText(R.string.mashup_server_invalid)
            return
        }
        repository.settings.aiServerUrl = clean
        aiServerInput.setText(clean)
        repository.settings.aiServerToken = aiTokenInput.text?.toString().orEmpty()
        aiTokenInput.setText(repository.settings.aiServerToken)
        val message = if (clean.isEmpty()) R.string.settings_ai_cleared else R.string.settings_ai_saved
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    /** Checks the typed address without saving it. */
    private fun testAiServer() {
        val url = PlayerSettings.sanitiseServerUrl(aiServerInput.text?.toString())
        if (url.isEmpty()) {
            aiStatusView.setText(R.string.settings_ai_empty)
            return
        }
        val run = ++testGeneration
        aiStatusView.setText(R.string.settings_ai_testing)
        val token = PlayerSettings.sanitiseToken(aiTokenInput.text?.toString())
        val client = AiMashupClient(requireContext(), url, token)
        worker.execute {
            val outcome = pingServer(client)
            main.post { showTestOutcome(run, outcome) }
        }
    }

    /** Worker thread. The server's health and an empty reason, or null and the reason. */
    private fun pingServer(client: AiMashupClient): Pair<AiMashupClient.Health?, String> {
        return try {
            client.ping() to ""
        } catch (e: IOException) {
            null to (e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            null to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun showTestOutcome(run: Long, outcome: Pair<AiMashupClient.Health?, String>) {
        if (!isAdded || view == null || run != testGeneration) return
        val (health, reason) = outcome
        aiStatusView.text = if (health != null) {
            getString(R.string.settings_ai_ok, health.device, health.cachedTracks)
        } else {
            getString(R.string.settings_ai_failed, reason)
        }
    }

    // ------------------------------------------------------------------ crossfade

    private fun wireCrossfade(view: View) {
        crossfadeBar = view.findViewById(R.id.settingsCrossfade)
        crossfadeLabel = view.findViewById(R.id.settingsCrossfadeValue)
        crossfadeBar.max = PlayerSettings.MAX_CROSSFADE_SECONDS
        crossfadeBar.progress = repository.settings.crossfadeSeconds
        renderCrossfade(crossfadeBar.progress)
        crossfadeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderCrossfade(progress)
                if (fromUser) repository.settings.crossfadeSeconds = progress
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit

            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    private fun renderCrossfade(seconds: Int) {
        val value = if (seconds <= 0) {
            getString(R.string.settings_crossfade_off)
        } else {
            getString(R.string.settings_crossfade_seconds, seconds)
        }
        crossfadeLabel.text = getString(R.string.settings_crossfade_label, value)
    }

    // ------------------------------------------------------------------ separator

    private fun typedSeparator(): String {
        return PlayerSettings.sanitise(separatorInput.text?.toString())
    }

    private fun applySeparator() {
        repository.settings.artistSeparator = typedSeparator()
        repository.notifyArtistRulesChanged()
        Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    /** Shows how a real tag from the library splits with the separator being typed. */
    private fun renderPreview() {
        val separator = typedSeparator()
        val sample = repository.tracks.firstOrNull { it.artistTag.contains(separator) }?.artistTag
            ?: getString(R.string.settings_sample_tag)
        val artists = ArtistSplitter.split(sample, separator)
        previewView.text = getString(R.string.settings_preview, sample, artists.joinToString("  |  "))
    }
}
