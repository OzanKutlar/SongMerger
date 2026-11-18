package me.Integer.Mediaplayer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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
import me.Integer.Mediaplayer.library.MashupRouteParser
import me.Integer.Mediaplayer.library.MixSegment
import me.Integer.Mediaplayer.library.Playlist
import me.Integer.Mediaplayer.library.Track
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Interactive editor for AI Mashup steps and compact route strings.
 * Allows adjusting a step's start time, exit time, crossfade duration, and tempo ratio,
 * or editing/copying the entire route string (`Song A,1:45,Song B,0:33...`).
 */
object MashupStepDialog {

    private const val PADDING_DP = 20
    private const val ROW_GAP_DP = 10

    /** Opens an interactive editor for all steps of [track] in [playlist], or a single step. */
    fun showStepEditor(
        context: Context,
        repository: LibraryRepository,
        playlist: Playlist,
        track: Track
    ) {
        val segments = playlist.segments
        if (segments.isEmpty()) return
        val indices = segments.indices.filter { segments[it].trackUri == track.uri }
        if (indices.isEmpty()) return
        if (indices.size == 1) {
            editSingleStep(context, repository, playlist, track, indices[0])
            return
        }
        val labels = indices.map { idx ->
            val seg = segments[idx]
            val endLabel = if (seg.playsToEnd) context.getString(R.string.playlist_mix_end) else MixSegment.formatTime(seg.endMs)
            "Step ${idx + 1}: ${MixSegment.formatTime(seg.startMs)} → $endLabel"
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(track.title)
            .setItems(labels) { _, which ->
                val stepIdx = indices.getOrNull(which) ?: return@setItems
                editSingleStep(context, repository, playlist, track, stepIdx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editSingleStep(
        context: Context,
        repository: LibraryRepository,
        playlist: Playlist,
        track: Track,
        stepIndex: Int
    ) {
        val seg = playlist.segments.getOrNull(stepIndex) ?: return
        val durationMs = track.durationMs.takeIf { it > 0L } ?: 240_000L
        val pad = dp(context, PADDING_DP)
        val gap = dp(context, ROW_GAP_DP)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        var startMs = seg.startMs.coerceIn(0L, maxOf(0L, durationMs - MixSegment.MIN_LENGTH_MS))
        var endMs = if (seg.playsToEnd) durationMs else seg.endMs.coerceIn(startMs + MixSegment.MIN_LENGTH_MS, durationMs)
        var crossfadeMs = seg.crossfadeMs.coerceIn(0L, MixSegment.MAX_CROSSFADE_MS)
        var tempoRatio = seg.tempoRatio.coerceIn(MixSegment.MIN_TEMPO_RATIO, MixSegment.MAX_TEMPO_RATIO)

        val startLabel = labelView(context)
        val startSeek = SeekBar(context).apply {
            max = 1000
            progress = ((startMs.toDouble() / durationMs) * 1000).roundToInt().coerceIn(0, 1000)
        }

        val endLabel = labelView(context)
        val endSeek = SeekBar(context).apply {
            max = 1000
            progress = ((endMs.toDouble() / durationMs) * 1000).roundToInt().coerceIn(0, 1000)
        }

        val fadeLabel = labelView(context)
        val fadeSeek = SeekBar(context).apply {
            max = (MixSegment.MAX_CROSSFADE_MS / 500L).toInt()
            progress = (crossfadeMs / 500L).toInt()
        }

        val tempoLabel = labelView(context)
        val tempoSeek = SeekBar(context).apply {
            max = 30 // 0.85 to 1.15 in 0.01 steps
            progress = ((tempoRatio - MixSegment.MIN_TEMPO_RATIO) * 100f).roundToInt().coerceIn(0, 30)
        }

        fun refreshLabels() {
            startLabel.text = context.getString(R.string.mashup_step_entry, MixSegment.formatTime(startMs))
            val endText = if (endMs >= durationMs - MixSegment.END_TOLERANCE_MS) {
                context.getString(R.string.playlist_mix_end)
            } else {
                MixSegment.formatTime(endMs)
            }
            endLabel.text = context.getString(R.string.mashup_step_exit, endText)
            fadeLabel.text = context.getString(
                R.string.mashup_step_fade,
                String.format(Locale.US, "%.1f s", crossfadeMs / 1000.0)
            )
            tempoLabel.text = context.getString(
                R.string.mashup_step_tempo,
                String.format(Locale.US, "%.2fx", tempoRatio)
            )
        }

        startSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val candidate = (durationMs * progress) / 1000L
                startMs = candidate.coerceAtMost(maxOf(0L, endMs - MixSegment.MIN_LENGTH_MS))
                refreshLabels()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        endSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val candidate = (durationMs * progress) / 1000L
                endMs = candidate.coerceAtLeast(minOf(durationMs, startMs + MixSegment.MIN_LENGTH_MS))
                refreshLabels()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        fadeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                crossfadeMs = progress * 500L
                refreshLabels()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        tempoSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                tempoRatio = MixSegment.MIN_TEMPO_RATIO + progress * 0.01f
                refreshLabels()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        refreshLabels()
        for (v in listOf(startLabel, startSeek, endLabel, endSeek, fadeLabel, fadeSeek, tempoLabel, tempoSeek)) {
            if (v is TextView && v !== startLabel) v.setPadding(0, gap, 0, 0)
            root.addView(v)
        }

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.mashup_step_title, stepIndex + 1, track.title))
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton(R.string.split_save) { _, _ ->
                val finalEnd = if (stepIndex == playlist.segments.lastIndex || endMs >= durationMs - MixSegment.END_TOLERANCE_MS) {
                    MixSegment.END_OF_TRACK
                } else {
                    endMs
                }
                val updated = MixSegment.create(
                    trackUri = seg.trackUri,
                    startMs = startMs,
                    endMs = finalEnd,
                    crossfadeMs = crossfadeMs,
                    durationMs = track.durationMs,
                    tempoRatio = tempoRatio,
                    gainDb = seg.gainDb
                )
                if (updated != null) {
                    val copy = ArrayList(playlist.segments)
                    copy[stepIndex] = updated
                    repository.playlists.updateSegments(playlist.id, copy)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Dialog to view, copy, or edit a mashup's compact route string (`Song A,1:45,Song B,0:33...`). */
    fun showRouteEditor(context: Context, repository: LibraryRepository, playlist: Playlist) {
        val currentRoute = if (playlist.isMix) {
            MashupRouteParser.format(playlist.segments) { repository.findTrack(it) }
        } else {
            ""
        }
        val pad = dp(context, PADDING_DP)
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(InputFilter.LengthFilter(MashupRouteParser.MAX_ROUTE_CHARS))
            setHint(R.string.mashup_route_hint)
            setText(currentRoute)
            setSelection(text.length)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.mashup_edit_route)
            .setMessage(R.string.mashup_route_help)
            .setView(container)
            .setPositiveButton(R.string.split_save) { _, _ ->
                val raw = input.text?.toString().orEmpty().trim()
                val parsed = MashupRouteParser.parse(raw, repository.tracks)
                if (parsed == null) {
                    Toast.makeText(context, R.string.mashup_route_invalid, Toast.LENGTH_LONG).show()
                } else {
                    repository.playlists.updateSegments(playlist.id, parsed)
                    Toast.makeText(context, R.string.mashup_route_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.mashup_copy_route) { _, _ ->
                val clip = context.getSystemService(ClipboardManager::class.java)
                clip?.setPrimaryClip(ClipData.newPlainText(playlist.name, input.text?.toString().orEmpty()))
                Toast.makeText(context, R.string.mashup_route_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun labelView(context: Context): TextView {
        return TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }
}
