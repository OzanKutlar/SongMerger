package me.Integer.Mediaplayer.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.ArtistSplitter
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.MusicFolderStore
import me.Integer.Mediaplayer.library.PlayerSettings

/** Music folders and the artist separator. */
class SettingsFragment : Fragment(R.layout.fragment_settings), LibraryRepository.Listener {

    private lateinit var repository: LibraryRepository
    private lateinit var folderList: LinearLayout
    private lateinit var scanView: TextView
    private lateinit var rescanButton: View
    private lateinit var separatorInput: EditText
    private lateinit var previewView: TextView

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
