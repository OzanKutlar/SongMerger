package me.Integer.Mediaplayer.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.AiMashupClient
import me.Integer.Mediaplayer.library.AiServerDiscovery
import me.Integer.Mediaplayer.library.ArtistSplitter
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.MusicFolderStore
import me.Integer.Mediaplayer.library.PlayerSettings
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Grouped, card-based Settings screen: Playback (sleep timer, play speed, crossfade,
 * skip silence), Playlists & Library (music folders, rescan, artist separator, duplicate
 * prevention), and AI Mashup Server (address/token, LAN auto-discovery, connection test).
 */
class SettingsFragment : Fragment(R.layout.fragment_settings), LibraryRepository.Listener {

    private lateinit var repository: LibraryRepository
    private lateinit var host: PlayerHost

    private lateinit var sleepTimerSummary: TextView
    private lateinit var playSpeedBar: SeekBar
    private lateinit var playSpeedLabel: TextView
    private lateinit var crossfadeBar: SeekBar
    private lateinit var crossfadeLabel: TextView
    private lateinit var skipSilenceSwitch: SwitchCompat

    private lateinit var foldersSummary: TextView
    private lateinit var scanView: TextView
    private lateinit var rescanRow: View
    private lateinit var previewView: TextView
    private lateinit var noDuplicatesSwitch: SwitchCompat

    private lateinit var aiServerSummary: TextView
    private lateinit var aiStatusView: TextView

    private var activeFoldersContainer: LinearLayout? = null

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
        host = requireActivity() as PlayerHost

        sleepTimerSummary = view.findViewById(R.id.settingsSleepTimerSummary)
        playSpeedBar = view.findViewById(R.id.settingsPlaySpeed)
        playSpeedLabel = view.findViewById(R.id.settingsPlaySpeedValue)
        crossfadeBar = view.findViewById(R.id.settingsCrossfade)
        crossfadeLabel = view.findViewById(R.id.settingsCrossfadeValue)
        skipSilenceSwitch = view.findViewById(R.id.settingsSwitchSkipSilence)

        foldersSummary = view.findViewById(R.id.settingsFoldersSummary)
        scanView = view.findViewById(R.id.settingsScan)
        rescanRow = view.findViewById(R.id.settingsRescan)
        previewView = view.findViewById(R.id.settingsPreview)
        noDuplicatesSwitch = view.findViewById(R.id.settingsSwitchNoDuplicates)

        aiServerSummary = view.findViewById(R.id.settingsAiServerSummary)
        aiStatusView = view.findViewById(R.id.settingsAiStatus)

        view.findViewById<View>(R.id.settingsBack).setOnClickListener { (requireActivity() as Navigator).goBack() }

        wirePlaybackSection(view)
        wireLibrarySection(view)
        wireAiServerSection(view)
    }

    override fun onStart() {
        super.onStart()
        repository.addListener(this)
        renderSleepTimer()
        renderFoldersSummary()
        renderScan(repository.scanState)
        renderSeparatorSummary()
        renderAiServerSummary()
    }

    override fun onStop() {
        repository.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onLibraryChanged() {
        renderFoldersSummary()
        renderSeparatorSummary()
    }

    override fun onScanState(state: LibraryRepository.ScanState) {
        renderScan(state)
    }

    // ------------------------------------------------------------------ Playback section

    private fun wirePlaybackSection(view: View) {
        view.findViewById<View>(R.id.settingsRowSleepTimer).setOnClickListener {
            SleepTimerDialog.show(requireContext(), host.connection)
            view.postDelayed({ if (isAdded) renderSleepTimer() }, 250L)
        }

        // Play speed: 0.50x to 2.00x in 0.05x steps (30 steps, default 1.0x = step 10)
        playSpeedBar.max = 30
        val initialSpeed = repository.settings.playbackSpeed
        playSpeedBar.progress = ((initialSpeed - PlayerSettings.MIN_PLAY_SPEED) * 20f).roundToInt().coerceIn(0, 30)
        renderPlaySpeed(initialSpeed)
        playSpeedLabel.setOnClickListener {
            playSpeedBar.progress = 10
            applyPlaySpeed(1.0f)
        }
        playSpeedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = (PlayerSettings.MIN_PLAY_SPEED + progress * 0.05f)
                    .coerceIn(PlayerSettings.MIN_PLAY_SPEED, PlayerSettings.MAX_PLAY_SPEED)
                renderPlaySpeed(speed)
                if (fromUser) applyPlaySpeed(speed)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        // Crossfade between tracks: 0..12 seconds
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

        // Skip silence between tracks
        skipSilenceSwitch.isChecked = repository.settings.skipSilence
        skipSilenceSwitch.setOnCheckedChangeListener { _, checked ->
            repository.settings.skipSilence = checked
            host.connection.applyAudioPreferences(checked, repository.settings.playbackSpeed)
        }
        view.findViewById<View>(R.id.settingsRowSkipSilence).setOnClickListener {
            skipSilenceSwitch.toggle()
        }
    }

    private fun renderSleepTimer() {
        val rem = host.connection.sleepTimerRemainingMs()
        sleepTimerSummary.text = when {
            rem == -1L -> getString(R.string.sleep_status_end_of_track)
            rem > 0L -> {
                val mins = ((rem + 59_999L) / 60_000L).toInt()
                getString(R.string.sleep_status_minutes, mins)
            }
            else -> getString(R.string.settings_crossfade_off)
        }
    }

    private fun renderPlaySpeed(speed: Float) {
        playSpeedLabel.text = String.format(Locale.US, "%.2fx", speed).replace(".00x", ".0x").replace("0x", "x")
    }

    private fun applyPlaySpeed(speed: Float) {
        repository.settings.playbackSpeed = speed
        renderPlaySpeed(speed)
        host.connection.applyAudioPreferences(repository.settings.skipSilence, speed)
    }

    private fun renderCrossfade(seconds: Int) {
        crossfadeLabel.text = if (seconds <= 0) {
            getString(R.string.settings_crossfade_off)
        } else {
            resources.getQuantityString(R.plurals.settings_crossfade_seconds_plural, seconds, seconds)
        }
    }

    // ------------------------------------------------------------------ Library & Playlists section

    private fun wireLibrarySection(view: View) {
        view.findViewById<View>(R.id.settingsRowFolders).setOnClickListener { showFoldersDialog() }
        rescanRow.setOnClickListener { repository.rescan() }
        view.findViewById<View>(R.id.settingsRowSeparator).setOnClickListener { showSeparatorDialog() }

        noDuplicatesSwitch.isChecked = repository.settings.preventDuplicatesInQueue
        noDuplicatesSwitch.setOnCheckedChangeListener { _, checked ->
            repository.settings.preventDuplicatesInQueue = checked
        }
        view.findViewById<View>(R.id.settingsRowNoDuplicates).setOnClickListener {
            noDuplicatesSwitch.toggle()
        }
    }

    private fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        if (!repository.folders.add(uri)) {
            Toast.makeText(requireContext(), R.string.library_folder_failed, Toast.LENGTH_LONG).show()
            return
        }
        renderFoldersSummary()
        activeFoldersContainer?.let { populateDialogFolders(it) }
        repository.rescan()
    }

    private fun removeFolder(folder: MusicFolderStore.Folder) {
        repository.folders.remove(folder.uri)
        renderFoldersSummary()
        activeFoldersContainer?.let { populateDialogFolders(it) }
        repository.rescan()
    }

    private fun renderFoldersSummary() {
        val folders = repository.folders.folders()
        foldersSummary.text = if (folders.isEmpty()) {
            getString(R.string.settings_no_folders)
        } else {
            folders.joinToString(", ") { it.name }
        }
    }

    private fun showFoldersDialog() {
        val context = requireContext()
        val pad = dp(20)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        activeFoldersContainer = container
        populateDialogFolders(container)

        AlertDialog.Builder(context)
            .setTitle(R.string.settings_folders_row_title)
            .setView(container)
            .setPositiveButton(R.string.settings_add_folder) { _, _ ->
                activeFoldersContainer = null
                pickFolder.launch(null)
            }
            .setNegativeButton(R.string.mashup_close) { _, _ ->
                activeFoldersContainer = null
            }
            .setOnDismissListener { activeFoldersContainer = null }
            .show()
    }

    private fun populateDialogFolders(container: LinearLayout) {
        container.removeAllViews()
        val folders = repository.folders.folders()
        if (folders.isEmpty()) {
            val hint = TextView(requireContext()).apply {
                setText(R.string.settings_no_folders)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
            }
            container.addView(hint)
            return
        }
        val inflater = LayoutInflater.from(requireContext())
        for (folder in folders) {
            val row = inflater.inflate(R.layout.item_folder, container, false)
            row.findViewById<TextView>(R.id.folderName).text = folder.name
            row.findViewById<View>(R.id.folderRemove).setOnClickListener { removeFolder(folder) }
            container.addView(row)
        }
    }

    private fun renderScan(state: LibraryRepository.ScanState) {
        rescanRow.isEnabled = !state.running
        scanView.text = when {
            !state.running -> resources.getQuantityString(
                R.plurals.library_song_count, repository.tracks.size, repository.tracks.size
            )
            state.total <= 0 -> getString(R.string.library_scanning_folders)
            else -> getString(R.string.library_scanning, state.done, state.total)
        }
    }

    private fun renderSeparatorSummary() {
        val separator = repository.settings.artistSeparator
        val sample = repository.tracks.firstOrNull { it.artistTag.contains(separator) }?.artistTag
            ?: getString(R.string.settings_sample_tag)
        val artists = ArtistSplitter.split(sample, separator)
        previewView.text = "\"$separator\"  →  ${artists.joinToString(" | ")}"
    }

    private fun showSeparatorDialog() {
        val context = requireContext()
        val pad = dp(20)
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            filters = arrayOf(InputFilter.LengthFilter(PlayerSettings.MAX_SEPARATOR_LENGTH))
            setText(repository.settings.artistSeparator)
            setSelection(text.length)
        }
        val help = TextView(context).apply {
            setText(R.string.settings_separator_help)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 0, 0, dp(8))
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(help)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_separator_row_title)
            .setView(box)
            .setPositiveButton(R.string.split_save) { _, _ ->
                repository.settings.artistSeparator = PlayerSettings.sanitise(input.text?.toString())
                repository.notifyArtistRulesChanged()
                renderSeparatorSummary()
                Toast.makeText(context, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ AI Mashup Server section

    private fun wireAiServerSection(view: View) {
        view.findViewById<View>(R.id.settingsRowAiServer).setOnClickListener { showAiServerDialog() }
        view.findViewById<View>(R.id.settingsAiDiscover).setOnClickListener { discoverAiServer() }
        view.findViewById<View>(R.id.settingsAiTest).setOnClickListener { testAiServer() }
    }

    private fun renderAiServerSummary() {
        val url = repository.settings.aiServerUrl
        val hasToken = repository.settings.aiServerToken.isNotEmpty()
        aiServerSummary.text = when {
            url.isEmpty() -> getString(R.string.settings_ai_not_configured)
            hasToken -> "$url  ·  ${getString(R.string.settings_ai_token_active)}"
            else -> url
        }
    }

    private fun showAiServerDialog() {
        val context = requireContext()
        val pad = dp(20)
        val urlInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
            filters = arrayOf(InputFilter.LengthFilter(PlayerSettings.MAX_SERVER_URL_LENGTH))
            setHint(R.string.mashup_server_hint)
            setText(repository.settings.aiServerUrl)
            setSelection(text.length)
        }
        val tokenInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
            filters = arrayOf(InputFilter.LengthFilter(PlayerSettings.MAX_TOKEN_LENGTH))
            setHint(R.string.settings_ai_token_hint)
            setText(repository.settings.aiServerToken)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(urlInput)
            addView(tokenInput)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.mashup_server_title)
            .setMessage(R.string.settings_ai_help)
            .setView(container)
            .setPositiveButton(R.string.split_save) { _, _ ->
                val typed = urlInput.text?.toString().orEmpty()
                val clean = PlayerSettings.sanitiseServerUrl(typed)
                if (typed.isNotBlank() && clean.isEmpty()) {
                    Toast.makeText(context, R.string.mashup_server_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                repository.settings.aiServerUrl = clean
                repository.settings.aiServerToken = tokenInput.text?.toString().orEmpty()
                renderAiServerSummary()
                val msg = if (clean.isEmpty()) R.string.settings_ai_cleared else R.string.settings_ai_saved
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun discoverAiServer() {
        val run = ++testGeneration
        aiStatusView.setText(R.string.settings_ai_discovering)
        worker.execute {
            val found = AiServerDiscovery.discover()
            main.post {
                if (!isAdded || view == null || run != testGeneration) return@post
                if (found != null) {
                    repository.settings.aiServerUrl = found.url
                    renderAiServerSummary()
                    aiStatusView.text = getString(R.string.settings_ai_discovered, found.url, found.device)
                } else {
                    aiStatusView.setText(R.string.settings_ai_not_found)
                }
            }
        }
    }

    private fun testAiServer() {
        val url = repository.settings.aiServerUrl
        if (url.isEmpty()) {
            aiStatusView.setText(R.string.settings_ai_empty)
            return
        }
        val run = ++testGeneration
        aiStatusView.setText(R.string.settings_ai_testing)
        val token = repository.settings.aiServerToken
        val client = AiMashupClient(requireContext(), url, token)
        worker.execute {
            val outcome = pingServer(client)
            main.post { showTestOutcome(run, outcome) }
        }
    }

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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }
}
