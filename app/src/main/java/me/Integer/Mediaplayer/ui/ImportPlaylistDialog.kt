package me.Integer.Mediaplayer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.PlaylistImporter
import kotlin.math.roundToInt

/**
 * UI flow for importing a playlist from another music app: destination picker and live
 * waiting / cycling / completed status dialog.
 */
object ImportPlaylistDialog {

    private var activeDialog: AlertDialog? = null

    /** Lets the user choose Favorites or a Playlist, then enters the waiting state. */
    fun startFlow(context: Context, repository: LibraryRepository, host: PlayerHost) {
        val importer = PlaylistImporter.get(context)
        if (importer.state.phase != PlaylistImporter.Phase.IDLE) {
            showStatusDialog(context, importer)
            return
        }
        val playlists = repository.playlists.all()
        val options = ArrayList<String>(playlists.size + 2).apply {
            add(context.getString(R.string.import_target_favorites))
            add(context.getString(R.string.playlist_new_item))
            for (playlist in playlists) add(playlist.name)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.import_playlist_title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> beginImport(context, host, PlaylistImporter.Target.Favorites)
                    1 -> PlaylistDialogs.create(context, repository) { created ->
                        beginImport(context, host, PlaylistImporter.Target.PlaylistTarget(created.id, created.name))
                    }
                    else -> playlists.getOrNull(which - 2)?.let { chosen ->
                        beginImport(context, host, PlaylistImporter.Target.PlaylistTarget(chosen.id, chosen.name))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Re-opens the status dialog if an import is currently active in the background. */
    fun showStatusDialogIfActive(context: Context) {
        val importer = PlaylistImporter.get(context)
        if (importer.state.phase == PlaylistImporter.Phase.IDLE) return
        if (activeDialog?.isShowing == true) return
        showStatusDialog(context, importer)
    }

    private fun beginImport(context: Context, host: PlayerHost, target: PlaylistImporter.Target) {
        val importer = PlaylistImporter.get(context)
        importer.start(target) { host.connection.pause() }
        showStatusDialog(context, importer)
    }

    private fun showStatusDialog(context: Context, importer: PlaylistImporter) {
        activeDialog?.dismiss()
        val density = context.resources.displayMetrics.density
        val padH = (22 * density).roundToInt()
        val padV = (16 * density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV / 2)
        }

        val statusText = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val detailText = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, (6 * density).roundToInt(), 0, (12 * density).roundToInt())
        }
        val logContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (220 * density).roundToInt()
            )
            addView(logContainer)
        }

        root.addView(statusText)
        root.addView(detailText)
        root.addView(scroll)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.import_playlist_title)
            .setView(root)
            .setCancelable(false)
            .setPositiveButton(R.string.import_save_now, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val listener = PlaylistImporter.Listener { state ->
            renderState(context, dialog, statusText, detailText, logContainer, scroll, state)
        }

        dialog.setOnShowListener {
            importer.addListener(listener)
            renderState(context, dialog, statusText, detailText, logContainer, scroll, importer.state)
        }
        dialog.setOnDismissListener {
            importer.removeListener(listener)
            if (activeDialog === dialog) activeDialog = null
        }
        activeDialog = dialog
        dialog.show()
    }

    private fun renderState(
        context: Context,
        dialog: AlertDialog,
        statusView: TextView,
        detailView: TextView,
        logContainer: LinearLayout,
        scrollView: ScrollView,
        state: PlaylistImporter.State
    ) {
        val importer = PlaylistImporter.get(context)
        val targetName = when (val t = state.target) {
            is PlaylistImporter.Target.Favorites -> context.getString(R.string.library_tab_favorites)
            is PlaylistImporter.Target.PlaylistTarget -> t.name
            null -> ""
        }
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

        when (state.phase) {
            PlaylistImporter.Phase.IDLE -> {
                dialog.dismiss()
                return
            }
            PlaylistImporter.Phase.NEEDS_PERMISSION -> {
                statusView.text = context.getString(R.string.import_permission_title)
                detailView.text = context.getString(R.string.import_permission_body)
                scrollView.visibility = View.GONE
                positive.visibility = View.VISIBLE
                positive.setText(R.string.import_grant_access)
                positive.setOnClickListener { openNotificationAccessSettings(context) }
                negative.setText(android.R.string.cancel)
                negative.setOnClickListener { importer.cancel() }
            }
            PlaylistImporter.Phase.WAITING -> {
                statusView.text = context.getString(R.string.import_waiting_title, targetName)
                detailView.text = context.getString(R.string.import_waiting_body)
                scrollView.visibility = View.GONE
                positive.visibility = View.GONE
                negative.setText(android.R.string.cancel)
                negative.setOnClickListener { importer.cancel() }
            }
            PlaylistImporter.Phase.CYCLING -> {
                statusView.text = context.getString(
                    R.string.import_cycling_title,
                    state.matchedCount,
                    state.captured.size
                )
                detailView.text = context.getString(
                    R.string.import_cycling_now,
                    state.currentTitle,
                    state.currentArtist.ifEmpty { context.getString(R.string.library_unknown_artist) }
                )
                scrollView.visibility = View.VISIBLE
                populateLog(context, logContainer, state.captured)
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                positive.visibility = View.VISIBLE
                positive.setText(R.string.import_save_now)
                positive.setOnClickListener { importer.finishNow() }
                negative.setText(android.R.string.cancel)
                negative.setOnClickListener { importer.cancel() }
            }
            PlaylistImporter.Phase.COMPLETED -> {
                statusView.text = context.getString(R.string.import_complete_title)
                detailView.text = context.getString(
                    R.string.import_complete_body,
                    state.matchedCount,
                    state.captured.size,
                    targetName
                )
                scrollView.visibility = if (state.captured.isEmpty()) View.GONE else View.VISIBLE
                populateLog(context, logContainer, state.captured)
                positive.visibility = View.VISIBLE
                positive.setText(android.R.string.ok)
                positive.setOnClickListener { importer.cancel() }
                negative.visibility = View.GONE
            }
        }
    }

    private fun populateLog(
        context: Context,
        container: LinearLayout,
        captured: List<PlaylistImporter.CapturedSong>
    ) {
        val existing = container.childCount
        if (existing > captured.size) {
            container.removeAllViews()
        }
        val start = container.childCount
        val accent = ContextCompat.getColor(context, R.color.accent)
        val primary = ContextCompat.getColor(context, R.color.text_primary)
        val muted = ContextCompat.getColor(context, R.color.text_muted)
        val density = context.resources.displayMetrics.density
        val rowPadV = (4 * density).roundToInt()

        for (i in start until captured.size) {
            val item = captured[i]
            val matched = item.matchedTrack != null
            val row = TextView(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, rowPadV, 0, rowPadV)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (matched) primary else muted)
                val prefix = if (matched) "✓  " else "–  "
                val artistSuffix = if (item.artist.isNotEmpty()) " · ${item.artist}" else ""
                text = "$prefix${item.title}$artistSuffix"
                if (matched) setLinkTextColor(accent)
            }
            container.addView(row)
        }
    }

    private fun openNotificationAccessSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
