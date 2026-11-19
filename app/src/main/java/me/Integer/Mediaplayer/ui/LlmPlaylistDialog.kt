package me.Integer.Mediaplayer.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.LlmPlaylistPrompt
import me.Integer.Mediaplayer.library.LlmPlaylistPrompt.SuggestedPlaylist
import me.Integer.Mediaplayer.library.Track
import kotlin.math.roundToInt

/**
 * UI flow for LLM Suggested Playlists:
 * 1. Generates an instruction set telling an LLM chat interface to research the genre and mood
 *    of each song on the device and output named playlist JSONs (with no emojis).
 * 2. Imports the LLM's JSON response back into [LibraryRepository.playlists].
 */
object LlmPlaylistDialog {

    private const val PADDING_DP = 20
    private const val GAP_DP = 12
    private const val PREVIEW_HEIGHT_DP = 240
    private const val MAX_JSON_INPUT_LENGTH = 250_000
    private const val MAX_PREVIEW_LINES = 4

    private sealed class Source {
        object AllTracks : Source()
        object Favorites : Source()
        data class PlaylistSource(val id: Long, val name: String) : Source()
    }

    fun show(
        context: Context,
        repository: LibraryRepository,
        initialPlaylistId: Long = AiMashupMenuFragment.NO_PLAYLIST
    ) {
        val pad = dp(context, PADDING_DP)
        val gap = dp(context, GAP_DP)
        var currentSource: Source = resolveInitialSource(repository, initialPlaylistId)
        var requestedCount = LlmPlaylistPrompt.AUTO_PLAYLIST_COUNT

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 4)
        }

        val description = TextView(context).apply {
            setText(R.string.llm_playlist_description)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        val sourceButton = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, gap, 0, gap / 2)
            isClickable = true
            isFocusable = true
        }

        val countLabel = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, gap / 2, 0, 0)
        }

        val countSeek = SeekBar(context).apply {
            max = 10 // 0 = Automatic (3-6), 1..10 = exact count
            progress = requestedCount
        }

        fun refreshLabels() {
            val tracks = tracksForSource(repository, currentSource)
            val sourceName = labelForSource(context, currentSource)
            sourceButton.text = context.getString(R.string.llm_playlist_source_value, sourceName, tracks.size)
            countLabel.text = if (requestedCount <= 0) {
                context.getString(R.string.llm_playlist_count_auto)
            } else {
                context.getString(R.string.llm_playlist_count_exact, requestedCount)
            }
        }

        sourceButton.setOnClickListener {
            promptSourcePicker(context, repository) { picked ->
                currentSource = picked
                refreshLabels()
            }
        }

        countSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                requestedCount = progress
                refreshLabels()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        refreshLabels()
        root.addView(description)
        root.addView(sourceButton)
        root.addView(countLabel)
        root.addView(countSeek)

        AlertDialog.Builder(context)
            .setTitle(R.string.llm_playlist_title)
            .setView(root)
            .setPositiveButton(R.string.llm_playlist_generate_btn) { _, _ ->
                val tracks = tracksForSource(repository, currentSource)
                generateInstructionSet(context, repository, tracks, requestedCount)
            }
            .setNeutralButton(R.string.llm_playlist_import_btn) { _, _ ->
                showImportJsonDialog(context, repository)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resolveInitialSource(repository: LibraryRepository, playlistId: Long): Source {
        if (playlistId != AiMashupMenuFragment.NO_PLAYLIST) {
            val playlist = repository.playlists.get(playlistId)
            if (playlist != null) return Source.PlaylistSource(playlist.id, playlist.name)
        }
        return Source.AllTracks
    }

    private fun tracksForSource(repository: LibraryRepository, source: Source): List<Track> {
        return when (source) {
            is Source.AllTracks -> repository.tracks
            is Source.Favorites -> repository.favoriteTracks()
            is Source.PlaylistSource -> {
                val playlist = repository.playlists.get(source.id)
                if (playlist != null) repository.resolveTracks(playlist.trackUris) else emptyList()
            }
        }
    }

    private fun labelForSource(context: Context, source: Source): String {
        return when (source) {
            is Source.AllTracks -> context.getString(R.string.mashup_source_all)
            is Source.Favorites -> context.getString(R.string.mashup_source_favorites)
            is Source.PlaylistSource -> source.name
        }
    }

    private fun promptSourcePicker(
        context: Context,
        repository: LibraryRepository,
        onPicked: (Source) -> Unit
    ) {
        val playlists = repository.playlists.all()
        val labels = ArrayList<String>(playlists.size + 2).apply {
            add(context.getString(R.string.mashup_source_all))
            add(context.getString(R.string.mashup_source_favorites))
            for (playlist in playlists) add(playlist.name)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.mashup_source_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = when (which) {
                    0 -> Source.AllTracks
                    1 -> Source.Favorites
                    else -> playlists.getOrNull(which - 2)?.let { Source.PlaylistSource(it.id, it.name) }
                } ?: return@setItems
                onPicked(chosen)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ Instruction set generation

    private fun generateInstructionSet(
        context: Context,
        repository: LibraryRepository,
        tracks: List<Track>,
        playlistCount: Int
    ) {
        if (tracks.isEmpty()) {
            Toast.makeText(context, R.string.llm_playlist_no_tracks, Toast.LENGTH_SHORT).show()
            return
        }
        val result = LlmPlaylistPrompt.buildPrompt(
            tracks = tracks,
            artistOf = { repository.displayArtist(it) },
            playlistCount = playlistCount
        )
        copyToClipboard(context, result.prompt)
        showPromptPreviewDialog(context, repository, result)
    }

    private fun showPromptPreviewDialog(
        context: Context,
        repository: LibraryRepository,
        result: LlmPlaylistPrompt.PromptResult
    ) {
        val pad = dp(context, PADDING_DP)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 4)
        }
        val summaryText = if (result.truncated) {
            context.getString(R.string.llm_playlist_prompt_truncated, result.includedTracks, result.totalTracks)
        } else {
            context.getString(R.string.llm_playlist_prompt_ready, result.includedTracks)
        }
        val summary = TextView(context).apply {
            text = summaryText
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(context, 8))
        }
        val promptView = TextView(context).apply {
            text = result.prompt
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, PREVIEW_HEIGHT_DP)
            )
            addView(promptView)
        }
        root.addView(summary)
        root.addView(scroll)

        AlertDialog.Builder(context)
            .setTitle(R.string.llm_playlist_prompt_title)
            .setView(root)
            .setPositiveButton(R.string.llm_playlist_import_btn) { _, _ ->
                showImportJsonDialog(context, repository)
            }
            .setNeutralButton(R.string.llm_playlist_share_btn) { _, _ ->
                sharePrompt(context, result.prompt)
            }
            .setNegativeButton(R.string.mashup_copy_route) { _, _ ->
                copyToClipboard(context, result.prompt)
            }
            .show()
    }

    private fun copyToClipboard(context: Context, text: String) {
        try {
            val clip = context.getSystemService(ClipboardManager::class.java)
            clip?.setPrimaryClip(ClipData.newPlainText("LLM Playlist Prompt", text))
            Toast.makeText(context, R.string.llm_playlist_copied, Toast.LENGTH_SHORT).show()
        } catch (_: RuntimeException) {
            Toast.makeText(context, R.string.llm_playlist_copy_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePrompt(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.llm_playlist_share_btn)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.llm_playlist_copy_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ JSON import

    fun showImportJsonDialog(context: Context, repository: LibraryRepository) {
        val pad = dp(context, PADDING_DP)
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter.LengthFilter(MAX_JSON_INPUT_LENGTH))
            setHint(R.string.llm_playlist_import_hint)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.llm_playlist_import_title)
            .setMessage(R.string.llm_playlist_import_help)
            .setView(container)
            .setPositiveButton(R.string.llm_playlist_parse_btn, null)
            .setNeutralButton(R.string.llm_playlist_paste_btn, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                val pasted = readClipboardText(context)
                if (pasted.isNotEmpty()) {
                    input.setText(pasted)
                    input.setSelection(input.text.length)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val raw = input.text?.toString().orEmpty()
                val parsed = LlmPlaylistPrompt.parsePlaylists(raw, repository.tracks)
                if (parsed.isEmpty()) {
                    Toast.makeText(context, R.string.llm_playlist_parse_empty, Toast.LENGTH_LONG).show()
                } else {
                    dialog.dismiss()
                    showParsedPlaylistsDialog(context, repository, parsed)
                }
            }
        }
        dialog.show()
    }

    private fun readClipboardText(context: Context): String {
        return try {
            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
            } else {
                ""
            }
        } catch (_: RuntimeException) {
            ""
        }
    }

    private fun showParsedPlaylistsDialog(
        context: Context,
        repository: LibraryRepository,
        playlists: List<SuggestedPlaylist>
    ) {
        val pad = dp(context, PADDING_DP)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 4)
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        for (suggested in playlists) {
            listContainer.addView(buildSuggestedRow(context, repository, suggested))
        }
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, PREVIEW_HEIGHT_DP)
            )
            addView(listContainer)
        }
        root.addView(scroll)

        AlertDialog.Builder(context)
            .setTitle(context.resources.getQuantityString(R.plurals.llm_playlist_found, playlists.size, playlists.size))
            .setView(root)
            .setPositiveButton(R.string.mashup_save_all) { _, _ ->
                saveAllSuggested(context, repository, playlists)
            }
            .setNegativeButton(R.string.mashup_close, null)
            .show()
    }

    private fun buildSuggestedRow(
        context: Context,
        repository: LibraryRepository,
        suggested: SuggestedPlaylist
    ): View {
        val rowPad = dp(context, 8)
        val titleView = TextView(context).apply {
            text = context.getString(
                R.string.llm_playlist_row_title,
                suggested.name,
                suggested.tracks.size,
                suggested.entries.size
            )
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val songsPreview = suggested.tracks.joinToString(", ") { it.title }
            .ifEmpty { context.getString(R.string.llm_playlist_row_no_matches) }
        val detailView = TextView(context).apply {
            text = songsPreview
            setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = MAX_PREVIEW_LINES
            ellipsize = TextUtils.TruncateAt.END
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, rowPad, 0, rowPad)
            isClickable = true
            isFocusable = true
            addView(titleView)
            addView(detailView)
            setOnClickListener {
                val saved = saveSuggestedPlaylist(repository, suggested)
                val msg = if (saved != null) {
                    context.getString(R.string.mashup_saved_one, saved)
                } else {
                    context.getString(R.string.llm_playlist_save_failed)
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveAllSuggested(
        context: Context,
        repository: LibraryRepository,
        playlists: List<SuggestedPlaylist>
    ) {
        var savedCount = 0
        for (suggested in playlists) {
            if (saveSuggestedPlaylist(repository, suggested) != null) {
                savedCount++
            }
        }
        val msg = if (savedCount > 0) {
            context.resources.getQuantityString(R.plurals.llm_playlist_saved_many, savedCount, savedCount)
        } else {
            context.getString(R.string.llm_playlist_save_failed)
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun saveSuggestedPlaylist(repository: LibraryRepository, suggested: SuggestedPlaylist): String? {
        if (suggested.tracks.isEmpty()) return null
        val created = repository.playlists.create(suggested.name) ?: return null
        val uris = suggested.tracks.map { it.uri }
        repository.playlists.addTracks(created.id, uris)
        return created.name
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }
}
