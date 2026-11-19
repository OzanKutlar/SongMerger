package me.Integer.Mediaplayer.ui

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import me.Integer.Mediaplayer.R
import me.Integer.Mediaplayer.library.AiMashupGenerator
import me.Integer.Mediaplayer.library.AiMashupGenerator.Phase
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.PlayerSettings
import me.Integer.Mediaplayer.library.Playlist
import me.Integer.Mediaplayer.library.Track
import kotlin.math.roundToInt

/**
 * UI flow for AI mashups: the server address, the choice of songs, live progress, and the
 * results, which can be played at once or saved as mashup playlists. Main thread only.
 */
object AiMashupDialog {

    private const val PADDING_DP = 20
    private const val LIST_HEIGHT_DP = 260
    private const val ROW_PADDING_DP = 8
    private const val DETAIL_TOP_DP = 6
    private const val DETAIL_BOTTOM_DP = 10
    private const val PERCENT = 100
    private const val ROUTE_LINES = 3

    private var activeDialog: AlertDialog? = null

    /** The views of the status dialog that change with the state. */
    private class Views(
        val root: View,
        val status: TextView,
        val detail: TextView,
        val progress: ProgressBar,
        val scroll: ScrollView,
        val list: LinearLayout
    )

    // ------------------------------------------------------------------ entry points

    /** Shows the running or finished job, or asks for a server and the songs to use. */
    fun startFlow(context: Context, repository: LibraryRepository, host: PlayerHost) {
        if (AiMashupGenerator.get(context).state.phase != Phase.IDLE) {
            showStatusDialog(context, host)
            return
        }
        withServer(context, repository) { chooseSource(context, repository, host) }
    }

    /** Prompts options and starts with [tracks], for example the songs of one playlist. */
    fun startFrom(context: Context, repository: LibraryRepository, host: PlayerHost, tracks: List<Track>) {
        if (AiMashupGenerator.get(context).state.isRunning) {
            showStatusDialog(context, host)
            return
        }
        withServer(context, repository) { promptOptions(context, host, tracks) }
    }

    /** Re-opens the status dialog when a job is running or has results waiting. */
    fun showStatusDialogIfActive(context: Context, host: PlayerHost) {
        if (AiMashupGenerator.get(context).state.phase == Phase.IDLE) return
        if (activeDialog?.isShowing == true) return
        showStatusDialog(context, host)
    }

    /** Asks for the server address; [then] runs once a usable one is saved. */
    fun promptServer(context: Context, repository: LibraryRepository, then: () -> Unit = {}) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
            filters = arrayOf(InputFilter.LengthFilter(PlayerSettings.MAX_SERVER_URL_LENGTH))
            setHint(R.string.mashup_server_hint)
            setText(repository.settings.aiServerUrl)
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.mashup_server_title)
            .setView(framed(context, input))
            .setPositiveButton(R.string.split_save) { _, _ ->
                val url = PlayerSettings.sanitiseServerUrl(input.text.toString())
                if (url.isEmpty()) {
                    toast(context, context.getString(R.string.mashup_server_invalid))
                } else {
                    repository.settings.aiServerUrl = url
                    then()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ flow

    private fun withServer(context: Context, repository: LibraryRepository, then: () -> Unit) {
        if (repository.settings.aiServerUrl.isNotEmpty()) {
            then()
            return
        }
        promptServer(context, repository, then)
    }

    private fun chooseSource(context: Context, repository: LibraryRepository, host: PlayerHost) {
        val playlists = repository.playlists.all()
        val labels = ArrayList<String>(playlists.size + 2).apply {
            add(context.getString(R.string.mashup_source_all))
            add(context.getString(R.string.mashup_source_favorites))
            for (playlist in playlists) add(playlist.name)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.mashup_source_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val source = when (which) {
                    0 -> repository.tracks
                    1 -> repository.favoriteTracks()
                    else -> playlists.getOrNull(which - 2)?.let { repository.resolveTracks(it.trackUris) }
                } ?: return@setItems
                promptOptions(context, host, source)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Lets the user pick how many mashups, songs per mashup, and an optional opening seed track. */
    private fun promptOptions(context: Context, host: PlayerHost, source: List<Track>) {
        if (source.size < 2) {
            begin(context, host, source, AiMashupGenerator.Options())
            return
        }
        val pad = dp(context, PADDING_DP)
        var count = AiMashupGenerator.DEFAULT_PLAYLIST_COUNT
        var length = minOf(AiMashupGenerator.DEFAULT_TRACKS_PER_PLAYLIST, source.size.coerceAtLeast(2))
        var seedTrack: Track? = null

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 4)
        }
        val countLabel = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val countSeek = SeekBar(context).apply {
            max = 9 // 1..10
            progress = count - 1
        }
        val lengthLabel = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(context, DETAIL_BOTTOM_DP), 0, 0)
        }
        val maxLen = minOf(20, source.size).coerceAtLeast(2)
        val lengthSeek = SeekBar(context).apply {
            max = (maxLen - 2).coerceAtLeast(1)
            progress = (length - 2).coerceIn(0, max)
        }
        val seedButton = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(context, DETAIL_BOTTOM_DP), 0, dp(context, DETAIL_TOP_DP))
            isClickable = true
            isFocusable = true
        }

        fun refreshLabels() {
            countLabel.text = context.getString(R.string.mashup_opt_count, count)
            lengthLabel.text = context.getString(R.string.mashup_opt_tracks, length)
            val seedName = seedTrack?.title ?: context.getString(R.string.mashup_opt_seed_any)
            seedButton.text = context.getString(R.string.mashup_opt_seed, seedName)
        }

        countSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                count = progress + 1
                refreshLabels()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        lengthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                length = (progress + 2).coerceAtMost(maxLen)
                refreshLabels()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        seedButton.setOnClickListener {
            val candidates = source.take(200)
            val names = arrayOf(context.getString(R.string.mashup_opt_seed_any)) + candidates.map { it.title }
            AlertDialog.Builder(context)
                .setTitle(R.string.mashup_opt_seed_choose)
                .setItems(names) { _, which ->
                    seedTrack = if (which == 0) null else candidates.getOrNull(which - 1)
                    refreshLabels()
                }
                .show()
        }
        refreshLabels()
        root.addView(countLabel)
        root.addView(countSeek)
        root.addView(lengthLabel)
        root.addView(lengthSeek)
        root.addView(seedButton)

        AlertDialog.Builder(context)
            .setTitle(R.string.mashup_title)
            .setView(root)
            .setPositiveButton(R.string.mashup_start) { _, _ ->
                begin(
                    context,
                    host,
                    source,
                    AiMashupGenerator.Options(count, length, seedTrack?.uri.orEmpty())
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A start that cannot begin still shows its reason in the status dialog. */
    private fun begin(context: Context, host: PlayerHost, source: List<Track>, options: AiMashupGenerator.Options) {
        AiMashupGenerator.get(context).start(source, options)
        showStatusDialog(context, host)
    }

    // ------------------------------------------------------------------ status dialog

    private fun showStatusDialog(context: Context, host: PlayerHost) {
        activeDialog?.dismiss()
        val generator = AiMashupGenerator.get(context)
        val views = buildViews(context)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.mashup_title)
            .setView(views.root)
            .setCancelable(false)
            .setPositiveButton(R.string.mashup_save_all, null)
            .setNeutralButton(R.string.mashup_hide, null)
            .setNegativeButton(R.string.mashup_close, null)
            .create()
        val listener = AiMashupGenerator.Listener { state -> render(context, host, dialog, views, state) }
        dialog.setOnShowListener {
            generator.addListener(listener)
            render(context, host, dialog, views, generator.state)
        }
        dialog.setOnDismissListener {
            generator.removeListener(listener)
            if (activeDialog === dialog) activeDialog = null
        }
        activeDialog = dialog
        dialog.show()
    }

    private fun buildViews(context: Context): Views {
        val pad = dp(context, PADDING_DP)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3 / 4, pad, pad / 2)
        }
        val status = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val detail = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(context, DETAIL_TOP_DP), 0, dp(context, DETAIL_BOTTOM_DP))
        }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, LIST_HEIGHT_DP)
            )
            addView(list)
        }
        root.addView(status)
        root.addView(detail)
        root.addView(progress)
        root.addView(scroll)
        return Views(root, status, detail, progress, scroll, list)
    }

    private fun render(
        context: Context,
        host: PlayerHost,
        dialog: AlertDialog,
        views: Views,
        state: AiMashupGenerator.State
    ) {
        when (state.phase) {
            Phase.IDLE -> {
                dialog.dismiss()
                return
            }
            Phase.CONNECTING, Phase.UPLOADING, Phase.ANALYSING, Phase.ROUTING -> renderRunning(context, views, state)
            Phase.COMPLETED -> renderResults(context, host, views, state)
            Phase.FAILED -> renderFailure(context, views, state)
        }
        renderButtons(context, host, dialog, state)
    }

    private fun renderRunning(context: Context, views: Views, state: AiMashupGenerator.State) {
        views.scroll.visibility = View.GONE
        views.progress.visibility = View.VISIBLE
        when (state.phase) {
            Phase.UPLOADING -> {
                views.status.text = context.getString(R.string.mashup_uploading, state.done, state.total)
                val last = if (state.currentTitle.isEmpty()) {
                    ""
                } else {
                    context.getString(R.string.mashup_uploading_now, state.currentTitle)
                }
                views.detail.text = last + skippedLine(context, state)
                showCount(views.progress, state.done, state.total)
            }
            Phase.ANALYSING -> {
                views.status.text = context.getString(R.string.mashup_analysing, state.done, state.total)
                views.detail.text = context.getString(R.string.mashup_analysing_on, state.device.ifEmpty { "?" }) +
                    skippedLine(context, state)
                showCount(views.progress, state.done, state.total)
            }
            Phase.ROUTING -> {
                views.status.setText(R.string.mashup_routing)
                views.detail.text = context.getString(R.string.mashup_routing_body) + skippedLine(context, state)
                views.progress.isIndeterminate = true
            }
            else -> {
                views.status.setText(R.string.mashup_connecting)
                views.detail.text = LibraryRepository.get(context).settings.aiServerUrl
                views.progress.isIndeterminate = true
            }
        }
    }

    private fun renderResults(context: Context, host: PlayerHost, views: Views, state: AiMashupGenerator.State) {
        views.progress.visibility = View.GONE
        views.scroll.visibility = View.VISIBLE
        val count = state.mixes.size
        views.status.text = context.resources.getQuantityString(R.plurals.mashup_ready, count, count)
        views.detail.text = context.getString(R.string.mashup_ready_body) + skippedLine(context, state)
        views.list.removeAllViews()
        for (mix in state.mixes) views.list.addView(rowOf(context, host, mix))
    }

    private fun renderFailure(context: Context, views: Views, state: AiMashupGenerator.State) {
        views.progress.visibility = View.GONE
        views.scroll.visibility = View.GONE
        views.status.setText(R.string.mashup_failed)
        val reason = when (state.failure) {
            AiMashupGenerator.Failure.NO_SERVER -> context.getString(R.string.mashup_failed_no_server)
            AiMashupGenerator.Failure.TOO_FEW_SONGS -> context.getString(R.string.mashup_failed_too_few)
            AiMashupGenerator.Failure.NO_ROUTES -> context.getString(R.string.mashup_failed_no_routes)
            AiMashupGenerator.Failure.SERVER, AiMashupGenerator.Failure.NONE ->
                context.getString(R.string.mashup_failed_server, state.detail.ifEmpty { "?" })
        }
        views.detail.text = reason + skippedLine(context, state)
    }

    /** Buttons are wired by hand so that a tap does not close the dialog by itself. */
    private fun renderButtons(context: Context, host: PlayerHost, dialog: AlertDialog, state: AiMashupGenerator.State) {
        val generator = AiMashupGenerator.get(context)
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE) ?: return
        val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL) ?: return
        val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE) ?: return
        val running = state.isRunning
        val completed = state.phase == Phase.COMPLETED
        // Running: run in background or stop. Finished: keep the results for later or discard them.
        neutral.visibility = if (running || completed) View.VISIBLE else View.GONE
        neutral.setText(if (running) R.string.mashup_hide else R.string.mashup_keep)
        neutral.setOnClickListener { dialog.dismiss() }
        negative.setText(
            when {
                running -> R.string.mashup_stop
                completed -> R.string.mashup_discard
                else -> R.string.mashup_close
            }
        )
        negative.setOnClickListener { generator.cancel() }
        when {
            state.phase == Phase.COMPLETED -> {
                positive.visibility = View.VISIBLE
                positive.setText(R.string.mashup_save_all)
                positive.setOnClickListener { saveAll(context, state.mixes) }
            }
            state.failure == AiMashupGenerator.Failure.NO_SERVER -> {
                positive.visibility = View.VISIBLE
                positive.setText(R.string.mashup_set_server)
                positive.setOnClickListener {
                    val repository = LibraryRepository.get(context)
                    generator.cancel()
                    promptServer(context, repository) { chooseSource(context, repository, host) }
                }
            }
            else -> positive.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ results

    private fun rowOf(context: Context, host: PlayerHost, mix: AiMashupGenerator.Mix): View {
        val pad = dp(context, ROW_PADDING_DP)
        val percent = (mix.score * PERCENT).roundToInt().coerceIn(0, PERCENT)
        val name = TextView(context).apply {
            text = context.getString(R.string.mashup_score, mix.name, percent)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val route = TextView(context).apply {
            text = mix.route.replace(",", ", ")
            setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = ROUTE_LINES
            ellipsize = TextUtils.TruncateAt.END
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, pad)
            isClickable = true
            isFocusable = true
            addView(name)
            addView(route)
            setOnClickListener { showMixMenu(context, host, mix) }
        }
    }

    private fun showMixMenu(context: Context, host: PlayerHost, mix: AiMashupGenerator.Mix) {
        val labels = arrayOf(
            context.getString(R.string.mashup_play_now),
            context.getString(R.string.mashup_save)
        )
        AlertDialog.Builder(context)
            .setTitle(mix.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> host.playMix(mix.segments, 0)
                    1 -> promptMixName(context, mix)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptMixName(context: Context, mix: AiMashupGenerator.Mix) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            filters = arrayOf(InputFilter.LengthFilter(Playlist.MAX_NAME_LENGTH))
            setHint(R.string.playlist_name_hint)
            setText(mix.name)
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.mashup_save)
            .setView(framed(context, input))
            .setPositiveButton(R.string.split_save) { _, _ ->
                val saved = AiMashupGenerator.get(context).save(mix, input.text.toString())
                val message = if (saved == null) {
                    context.getString(R.string.mashup_save_failed)
                } else {
                    context.getString(R.string.mashup_saved_one, saved.name)
                }
                toast(context, message)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Saves every mashup with its generated name and closes the job. */
    private fun saveAll(context: Context, mixes: List<AiMashupGenerator.Mix>) {
        val generator = AiMashupGenerator.get(context)
        val saved = mixes.count { generator.save(it, null) != null }
        val message = if (saved == 0) {
            context.getString(R.string.mashup_save_failed)
        } else {
            context.resources.getQuantityString(R.plurals.mashup_saved, saved, saved)
        }
        toast(context, message)
        generator.cancel()
    }

    // ------------------------------------------------------------------ helpers

    /** A determinate bar once the total is known, an indeterminate one before. */
    private fun showCount(progress: ProgressBar, done: Int, total: Int) {
        progress.isIndeterminate = total <= 0
        progress.max = maxOf(1, total)
        progress.progress = done.coerceIn(0, maxOf(1, total))
    }

    private fun skippedLine(context: Context, state: AiMashupGenerator.State): String {
        if (state.skipped <= 0) return ""
        return "\n" + context.resources.getQuantityString(R.plurals.mashup_skipped, state.skipped, state.skipped)
    }

    private fun framed(context: Context, view: View): View {
        val padding = dp(context, PADDING_DP)
        return FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(view)
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
