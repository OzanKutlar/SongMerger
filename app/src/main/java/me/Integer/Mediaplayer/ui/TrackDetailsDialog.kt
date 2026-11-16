package me.Integer.Mediaplayer.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.library.TrackDetailsReader

/**
 * Shows a song's metadata and file location on disk.
 */
object TrackDetailsDialog {

    fun show(context: Context, repository: LibraryRepository, track: Track) {
        val unknownArtist = context.getString(R.string.library_unknown_artist)
        val unknownAlbum = context.getString(R.string.album_unknown)
        val unknownValue = context.getString(R.string.details_unknown)
        val formattedArtists = repository.displayArtist(track).ifEmpty { unknownArtist }
        val details = TrackDetailsReader.read(context, track, formattedArtists)

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_track_details, null)
        val container = view.findViewById<LinearLayout>(R.id.detailsContainer)

        addRow(inflater, container, context.getString(R.string.details_title), details.title.ifEmpty { unknownValue })
        addRow(inflater, container, context.getString(R.string.details_artists), details.artists)
        if (details.rawArtistTag.isNotEmpty() && details.rawArtistTag != details.artists) {
            addRow(inflater, container, context.getString(R.string.details_raw_tag), details.rawArtistTag)
        }
        addRow(inflater, container, context.getString(R.string.details_album), details.album.ifEmpty { unknownAlbum })
        addRow(inflater, container, context.getString(R.string.details_year), details.year.ifEmpty { unknownValue })
        if (details.genre.isNotEmpty()) {
            addRow(inflater, container, context.getString(R.string.details_genre), details.genre)
        }
        if (details.trackNumber.isNotEmpty()) {
            addRow(inflater, container, context.getString(R.string.details_track_num), details.trackNumber)
        }
        addRow(inflater, container, context.getString(R.string.details_duration), details.duration.ifEmpty { unknownValue })
        if (details.bitrate.isNotEmpty()) {
            addRow(inflater, container, context.getString(R.string.details_bitrate), details.bitrate)
        }
        if (details.sampleRate.isNotEmpty()) {
            addRow(inflater, container, context.getString(R.string.details_sample_rate), details.sampleRate)
        }
        if (details.format.isNotEmpty()) {
            addRow(inflater, container, context.getString(R.string.details_format), details.format)
        }
        if (details.fileSize.isNotEmpty()) {
            addRow(inflater, container, context.getString(R.string.details_size), details.fileSize)
        }
        if (details.modifiedDate.isNotEmpty()) {
            addRow(inflater, container, context.getString(R.string.details_modified), details.modifiedDate)
        }
        addRow(inflater, container, context.getString(R.string.details_location), details.location)

        AlertDialog.Builder(context)
            .setTitle(R.string.menu_track_details)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun addRow(inflater: LayoutInflater, parent: LinearLayout, label: String, value: String) {
        val row: View = inflater.inflate(R.layout.item_detail_row, parent, false)
        row.findViewById<TextView>(R.id.detailLabel).text = label
        row.findViewById<TextView>(R.id.detailValue).text = value
        parent.addView(row)
    }
}
