package me.Integer.Mediaplayer.library

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import me.Integer.Mediaplayer.playback.MediaCaptureService

/**
 * Imports a playlist from another music app by waiting for playback to begin, matching each
 * playing song against [LibraryRepository], and skipping forward until the external player
 * rolls back around to the first song.
 *
 * Main thread only.
 */
class PlaylistImporter private constructor(context: Context) {

    enum class Phase {
        IDLE,
        NEEDS_PERMISSION,
        WAITING,
        CYCLING,
        COMPLETED
    }

    sealed class Target {
        object Favorites : Target()
        data class PlaylistTarget(val id: Long, val name: String) : Target()
    }

    data class CapturedSong(
        val title: String,
        val artist: String,
        val matchedTrack: Track?
    )

    data class State(
        val phase: Phase = Phase.IDLE,
        val target: Target? = null,
        val sourcePackage: String = "",
        val currentTitle: String = "",
        val currentArtist: String = "",
        val captured: List<CapturedSong> = emptyList(),
        val savedCount: Int = 0
    ) {
        val matchedCount: Int
            get() = captured.count { it.matchedTrack != null }
    }

    fun interface Listener {
        fun onImportStateChanged(state: State)
    }

    private val appContext = context.applicationContext
    private val repository = LibraryRepository.get(appContext)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val capturedList = ArrayList<CapturedSong>()

    private var target: Target? = null
    private var sourcePackage = ""
    private var firstFingerprint = ""
    private var lastFingerprint = ""
    private var currentTitle = ""
    private var currentArtist = ""
    private var savedCount = 0
    private var pollTicks = 0
    private var skipScheduled = false
    private var lastSkipAtMs = 0L
    private var skipRetries = 0

    var state: State = State()
        private set

    private val pollTask = object : Runnable {
        override fun run() {
            if (state.phase != Phase.WAITING && state.phase != Phase.CYCLING) return
            if (pollTicks >= MAX_POLL_TICKS) {
                finishNow()
                return
            }
            pollTicks++
            inspectSessions()
            if (state.phase == Phase.WAITING || state.phase == Phase.CYCLING) {
                main.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private val skipTask = Runnable {
        skipScheduled = false
        sendSkipToNext()
    }

    fun addListener(listener: Listener) {
        if (listener !in listeners) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Begins an import session into [chosenTarget]. */
    fun start(chosenTarget: Target, pauseOwnPlayer: () -> Unit) {
        stopTasks()
        pauseOwnPlayer()
        target = chosenTarget
        sourcePackage = ""
        firstFingerprint = ""
        lastFingerprint = ""
        currentTitle = ""
        currentArtist = ""
        savedCount = 0
        pollTicks = 0
        skipRetries = 0
        capturedList.clear()

        if (!MediaCaptureService.hasAccess(appContext)) {
            publish(Phase.NEEDS_PERMISSION)
            return
        }
        publish(Phase.WAITING)
        main.post(pollTask)
    }

    /** Re-checks notification permission when returning from system Settings. */
    fun onAppResumed() {
        if (state.phase == Phase.NEEDS_PERMISSION && MediaCaptureService.hasAccess(appContext)) {
            pollTicks = 0
            publish(Phase.WAITING)
            main.removeCallbacks(pollTask)
            main.post(pollTask)
        }
    }

    /** Saves all songs captured so far and stops cycling. */
    fun finishNow() {
        if (state.phase != Phase.WAITING && state.phase != Phase.CYCLING) return
        pauseExternalPlayer()
        stopTasks()
        savedCount = saveCaptured()
        publish(Phase.COMPLETED)
    }

    /** Aborts the import without saving, or closes a completed session. */
    fun cancel() {
        stopTasks()
        target = null
        sourcePackage = ""
        firstFingerprint = ""
        lastFingerprint = ""
        currentTitle = ""
        currentArtist = ""
        savedCount = 0
        capturedList.clear()
        publish(Phase.IDLE)
    }

    // ------------------------------------------------------------------ session monitoring

    private fun inspectSessions() {
        if (!MediaCaptureService.hasAccess(appContext)) {
            stopTasks()
            publish(Phase.NEEDS_PERMISSION)
            return
        }
        val controller = findExternalController() ?: return
        val metadata = controller.metadata ?: return
        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val title = rawTitle.trim()
        val artist = rawArtist.trim()
        val fp = TrackMatcher.fingerprint(title, artist)
        if (fp.isEmpty()) return

        if (state.phase == Phase.WAITING) {
            if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) return
            sourcePackage = controller.packageName
            firstFingerprint = fp
            lastFingerprint = fp
            recordSong(title, artist)
            publish(Phase.CYCLING)
            scheduleSkip()
            return
        }

        if (state.phase == Phase.CYCLING) {
            onCyclingMetadata(fp, title, artist)
        }
    }

    private fun onCyclingMetadata(fp: String, title: String, artist: String) {
        if (fp != lastFingerprint) {
            skipRetries = 0
            if (fp == firstFingerprint && capturedList.isNotEmpty()) {
                finishNow()
                return
            }
            if (capturedList.size >= MAX_CAPTURED_SONGS) {
                finishNow()
                return
            }
            lastFingerprint = fp
            recordSong(title, artist)
            publish(Phase.CYCLING)
            scheduleSkip()
            return
        }

        // Still on the same song: retry skipping if the external player ignored the command.
        val now = SystemClock.elapsedRealtime()
        if (!skipScheduled && lastSkipAtMs > 0L && now - lastSkipAtMs >= SKIP_RETRY_MS) {
            if (skipRetries < MAX_SKIP_RETRIES) {
                skipRetries++
                sendSkipToNext()
            } else if (capturedList.size == 1) {
                // Single-song playlist looped onto itself.
                finishNow()
            }
        }
    }

    private fun recordSong(title: String, artist: String) {
        currentTitle = title
        currentArtist = artist
        val matched = TrackMatcher.match(title, artist, repository.tracks)
        capturedList.add(CapturedSong(title, artist, matched))
    }

    private fun scheduleSkip() {
        if (skipScheduled) return
        skipScheduled = true
        main.removeCallbacks(skipTask)
        main.postDelayed(skipTask, SKIP_SETTLE_MS)
    }

    private fun sendSkipToNext() {
        val controller = findExternalController() ?: return
        lastSkipAtMs = SystemClock.elapsedRealtime()
        try {
            controller.transportControls.skipToNext()
        } catch (e: RuntimeException) {
            Log.w(TAG, "skipToNext failed: ${e.message}")
        }
    }

    private fun pauseExternalPlayer() {
        val controller = findExternalController() ?: return
        try {
            controller.transportControls.pause()
        } catch (e: RuntimeException) {
            Log.w(TAG, "pause failed: ${e.message}")
        }
    }

    private fun findExternalController(): MediaController? {
        val manager = appContext.getSystemService(MediaSessionManager::class.java) ?: return null
        val sessions = try {
            manager.getActiveSessions(MediaCaptureService.component(appContext))
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot read active sessions: ${e.message}")
            return null
        }
        val others = sessions.filter { it.packageName != appContext.packageName }
        if (sourcePackage.isNotEmpty()) {
            others.firstOrNull { it.packageName == sourcePackage }?.let { return it }
        }
        return others.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: others.firstOrNull()
    }

    private fun saveCaptured(): Int {
        val destination = target ?: return 0
        val uris = capturedList.mapNotNull { it.matchedTrack?.uri }.distinct()
        if (uris.isEmpty()) return 0
        return when (destination) {
            is Target.Favorites -> repository.addFavorites(uris)
            is Target.PlaylistTarget -> repository.playlists.addTracks(destination.id, uris)
        }
    }

    private fun stopTasks() {
        main.removeCallbacks(pollTask)
        main.removeCallbacks(skipTask)
        skipScheduled = false
    }

    private fun publish(phase: Phase) {
        state = State(
            phase = phase,
            target = target,
            sourcePackage = sourcePackage,
            currentTitle = currentTitle,
            currentArtist = currentArtist,
            captured = ArrayList(capturedList),
            savedCount = savedCount
        )
        for (listener in ArrayList(listeners)) listener.onImportStateChanged(state)
    }

    companion object {
        private const val TAG = "PlaylistImporter"
        private const val POLL_INTERVAL_MS = 450L
        private const val SKIP_SETTLE_MS = 550L
        private const val SKIP_RETRY_MS = 1600L
        private const val MAX_SKIP_RETRIES = 4
        private const val MAX_CAPTURED_SONGS = 2000
        private const val MAX_POLL_TICKS = 8000

        @Volatile
        private var instance: PlaylistImporter? = null

        fun get(context: Context): PlaylistImporter {
            return instance ?: synchronized(this) {
                instance ?: PlaylistImporter(context.applicationContext).also { instance = it }
            }
        }
    }
}
