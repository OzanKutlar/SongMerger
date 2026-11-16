package me.Integer.Mediaplayer.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track
import kotlin.math.roundToInt

/**
 * "Manually split artists": edit the artists of one song, one per line. Saving stores the
 * list in the app (the audio file is not changed); "Automatic" goes back to the separator.
 */
object SplitArtistsDialog {

    private const val PADDING_DP = 20
    private const val MIN_LINES = 3

    fun show(context: Context, repository: LibraryRepository, track: Track) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = MIN_LINES
            gravity = Gravity.TOP or Gravity.START
            setText(repository.artistsOf(track).joinToString("\n"))
        }
        val padding = (PADDING_DP * context.resources.displayMetrics.density).roundToInt()
        val container = FrameLayout(context).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        val tag = track.artistTag.ifEmpty { context.getString(R.string.library_unknown_artist) }
        AlertDialog.Builder(context)
            .setTitle(R.string.split_title)
            .setMessage(context.getString(R.string.split_message, tag))
            .setView(container)
            .setPositiveButton(R.string.split_save) { _, _ -> save(repository, track, input.text.toString()) }
            .setNeutralButton(R.string.split_reset) { _, _ -> reset(repository, track) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** An empty list would hide the song from every artist, so it resets to automatic. */
    private fun save(repository: LibraryRepository, track: Track, text: String) {
        val artists = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (artists.isEmpty()) {
            repository.overrides.clear(track.uri)
        } else {
            repository.overrides.set(track.uri, artists)
        }
        repository.notifyArtistRulesChanged()
    }

    private fun reset(repository: LibraryRepository, track: Track) {
        repository.overrides.clear(track.uri)
        repository.notifyArtistRulesChanged()
    }
}
