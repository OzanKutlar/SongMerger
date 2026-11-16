package me.Integer.Mediaplayer.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.Track
import me.Integer.Mediaplayer.library.TrackDetailsReader
import me.Integer.Mediaplayer.ui.widgets.SkeletonDrawable
import java.util.concurrent.Executors

/**
 * Shows a song's metadata and file location on disk. Opens immediately with skeleton
 * placeholders while reading file metadata on a background thread.
 */
object TrackDetailsDialog {

    private const val SKELETON_ROWS = 7
    private const val LABEL_WIDTH_DP = 76
    private const val VALUE_MIN_DP = 110
    private const val VALUE_SPREAD_DP = 80

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Mediaplayer-Details").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun show(context: Context, repository: LibraryRepository, track: Track) {
        val appContext = context.applicationContext
        val unknownArtist = context.getString(R.string.library_unknown_artist)
        val unknownAlbum = context.getString(R.string.album_unknown)
        val unknownValue = context.getString(R.string.details_unknown)
        val formattedArtists = repository.displayArtist(track).ifEmpty { unknownArtist }

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_track_details, null)
        val container = view.findViewById<LinearLayout>(R.id.detailsContainer)
        addSkeletonRows(context, inflater, container)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.menu_track_details)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        worker.execute {
            val details = TrackDetailsReader.read(appContext, track, formattedArtists)
            main.post {
                if (!dialog.isShowing) return@post
                container.removeAllViews()
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
            }
        }
    }

    private fun addSkeletonRows(context: Context, inflater: LayoutInflater, parent: LinearLayout) {
        val density = context.resources.displayMetrics.density
        for (i in 0 until SKELETON_ROWS) {
            val row: View = inflater.inflate(R.layout.item_detail_row, parent, false)
            val label = row.findViewById<TextView>(R.id.detailLabel)
            val value = row.findViewById<TextView>(R.id.detailValue)
            label.text = ""
            value.text = ""
            label.background = SkeletonDrawable.bar(context)
            value.background = SkeletonDrawable.bar(context)
            label.layoutParams = label.layoutParams.apply { width = (LABEL_WIDTH_DP * density).toInt() }
            value.layoutParams = value.layoutParams.apply {
                width = ((VALUE_MIN_DP + (i * 31) % VALUE_SPREAD_DP) * density).toInt()
            }
            parent.addView(row)
        }
    }

    private fun addRow(inflater: LayoutInflater, parent: LinearLayout, label: String, value: String) {
        val row: View = inflater.inflate(R.layout.item_detail_row, parent, false)
        row.findViewById<TextView>(R.id.detailLabel).text = label
        row.findViewById<TextView>(R.id.detailValue).text = value
        parent.addView(row)
    }
}
