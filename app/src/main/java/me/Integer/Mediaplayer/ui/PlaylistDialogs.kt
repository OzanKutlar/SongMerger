package me.Integer.Mediaplayer.ui

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Playlist
import me.Integer.Mediaplayer.library.Track
import kotlin.math.roundToInt

/** Dialogs and menus for playlists: create, rename, delete, add a song, choose a cover. */
object PlaylistDialogs {

    private const val PADDING_DP = 20
    private const val MAX_SONG_CHOICES = 300
    private const val ID_PLAY = 1
    private const val ID_SHUFFLE = 2
    private const val ID_RENAME = 3
    private const val ID_DELETE = 4
    private const val COVER_CHOICE_AUTO = 0
    private const val COVER_CHOICE_SONG = 1
    private const val COVER_CHOICE_IMAGE = 2

    /** Asks for a name and creates the playlist; [then] gets the new playlist. */
    fun create(context: Context, repository: LibraryRepository, then: (Playlist) -> Unit = {}) {
        promptName(context, R.string.playlist_new, "") { name ->
            val created = repository.playlists.create(name)
            if (created == null) {
                toast(context, context.getString(R.string.playlist_name_invalid))
            } else {
                then(created)
            }
        }
    }

    fun rename(context: Context, repository: LibraryRepository, playlist: Playlist) {
        promptName(context, R.string.playlist_rename, playlist.name) { name ->
            if (!repository.playlists.rename(playlist.id, name)) {
                toast(context, context.getString(R.string.playlist_name_invalid))
            }
        }
    }

    fun confirmDelete(
        context: Context,
        repository: LibraryRepository,
        playlist: Playlist,
        onDeleted: () -> Unit = {}
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.playlist_delete)
            .setMessage(context.getString(R.string.playlist_delete_message, playlist.name))
            .setPositiveButton(R.string.playlist_delete) { _, _ ->
                repository.playlists.delete(playlist.id)
                onDeleted()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Lets the user pick the playlist a song goes into, or create a new one for it. */
    fun addTrack(context: Context, repository: LibraryRepository, track: Track) {
        val playlists = repository.playlists.all()
        val labels = arrayOf(context.getString(R.string.playlist_new_item)) + playlists.map { it.name }
        AlertDialog.Builder(context)
            .setTitle(R.string.menu_add_playlist)
            .setItems(labels) { _, which ->
                if (which == 0) {
                    create(context, repository) { created -> addTo(context, repository, created, track) }
                } else {
                    playlists.getOrNull(which - 1)?.let { addTo(context, repository, it, track) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addTo(context: Context, repository: LibraryRepository, playlist: Playlist, track: Track) {
        val added = repository.playlists.addTrack(playlist.id, track.uri)
        val message = if (added) R.string.playlist_added else R.string.playlist_already
        toast(context, context.getString(message, playlist.name))
    }

    /** The three-dot menu of a playlist row in the library. */
    fun showRowMenu(anchor: View, playlist: Playlist, host: PlayerHost, repository: LibraryRepository) {
        val context = anchor.context
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, ID_PLAY, 0, R.string.library_play_all)
        popup.menu.add(0, ID_SHUFFLE, 1, R.string.library_shuffle_all)
        popup.menu.add(0, ID_RENAME, 2, R.string.playlist_rename)
        popup.menu.add(0, ID_DELETE, 3, R.string.playlist_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_PLAY -> host.playAll(repository.resolveTracks(playlist.trackUris), false)
                ID_SHUFFLE -> host.playAll(repository.resolveTracks(playlist.trackUris), true)
                ID_RENAME -> rename(context, repository, playlist)
                ID_DELETE -> confirmDelete(context, repository, playlist)
            }
            true
        }
        popup.show()
    }

    /**
     * Cover choices: automatic grid, the cover of one of the playlist's songs, or an image.
     * [pickImage] opens the system image picker; the caller stores the result.
     */
    fun chooseCover(context: Context, repository: LibraryRepository, playlist: Playlist, pickImage: () -> Unit) {
        val labels = arrayOf(
            context.getString(R.string.playlist_cover_auto),
            context.getString(R.string.playlist_cover_song),
            context.getString(R.string.playlist_cover_image)
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.playlist_change_cover)
            .setItems(labels) { _, which ->
                when (which) {
                    COVER_CHOICE_AUTO -> repository.playlists.setCover(playlist.id, Playlist.COVER_AUTO, "")
                    COVER_CHOICE_SONG -> chooseSongCover(context, repository, playlist)
                    COVER_CHOICE_IMAGE -> pickImage()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseSongCover(context: Context, repository: LibraryRepository, playlist: Playlist) {
        val tracks = repository.resolveTracks(playlist.trackUris).take(MAX_SONG_CHOICES)
        if (tracks.isEmpty()) {
            toast(context, context.getString(R.string.playlist_no_songs))
            return
        }
        val labels = tracks.map { it.title }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.playlist_cover_choose_song)
            .setItems(labels) { _, which ->
                tracks.getOrNull(which)?.let {
                    repository.playlists.setCover(playlist.id, Playlist.COVER_TRACK, it.uri)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptName(context: Context, titleRes: Int, initial: String, onName: (String) -> Unit) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            filters = arrayOf(InputFilter.LengthFilter(Playlist.MAX_NAME_LENGTH))
            setHint(R.string.playlist_name_hint)
            setText(initial)
            setSelection(text.length)
        }
        val padding = (PADDING_DP * context.resources.displayMetrics.density).roundToInt()
        val container = FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.split_save) { _, _ -> onName(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
