package me.Integer.Mediaplayer.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import android.os.SystemClock
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import me.Integer.Mediaplayer.MainActivity
import me.Integer.Mediaplayer.library.LibraryRepository
import me.Integer.Mediaplayer.library.ListeningHistoryStore
import me.Integer.Mediaplayer.library.PlayerSettings
import kotlin.random.Random

/**
 * Owns the player. Running it as a media session service gives background playback, the
 * notification, lock-screen controls and headset buttons without extra code.
 * Audio focus and "pause when headphones are unplugged" are handled by the player.
 *
 * It also owns shuffle: the queue is physically reordered here, directly on the player,
 * and the pre-shuffle order is remembered here so it survives the screens being recreated.
 *
 * And it remembers the queue: songs, position, shuffle and repeat are saved as they change,
 * and come back paused when the app opens, or playing when a headset or Bluetooth play
 * button wakes the app after it was closed.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private var deckARef: ExoPlayer? = null
    private var deckBRef: ExoPlayer? = null
    private var artLoader: EmbeddedArtBitmapLoader? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var memory: QueueMemory? = null
    private val main = Handler(Looper.getMainLooper())

    /** True once the queue changed since it was last saved; a fresh, empty service never erases the memory. */
    private var queueDirty = false

    /** Blends mashup steps into each other across two ping-pong decks; idle for normal songs. */
    private var crossfader: DjCrossfader? = null

    // Sleep Timer state
    private var sleepDeadlineMs = 0L
    private var sleepEndOfTrack = false
    private var sleepFadingSinceMs = 0L

    // Song listen tracking: counts once the user has actively listened to at least half the song
    private var trackedMediaId = ""
    private var trackedUid = 0L
    private var trackedAccumulatedMs = 0L
    private var trackedPlayStartElapsedMs = 0L
    private var trackedCounted = false

    private val listenCheckTask = object : Runnable {
        override fun run() {
            checkListenProgress()
        }
    }

    private val saveQueueTask = Runnable { saveQueueNow() }

    private val savePositionTask = object : Runnable {
        override fun run() {
            savePositionNow()
            if (player?.isPlaying == true) main.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }

    private val sleepTickTask = object : Runnable {
        override fun run() {
            onSleepTick()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                crossfader?.setDucking(false)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    player?.play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> crossfader?.setDucking(true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = player?.playWhenReady == true
                crossfader?.abort()
                player?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                crossfader?.abort()
                player?.pause()
            }
        }
    }

    private val focusTriggerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) requestAudioFocus() else if (!resumeOnFocusGain) abandonAudioFocus()
        }
    }

    /** Saves what changed on the active deck; the idle deck only ever holds a copy. */
    private val memoryListener = object : Player.Listener {
        override fun onEvents(eventPlayer: Player, events: Player.Events) {
            if (eventPlayer !== player) return
            if (sleepEndOfTrack && events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                finishSleepTimer()
            }
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) scheduleQueueSave()
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED
                )
            ) {
                savePositionNow()
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                onTrackTransitionForHistory(eventPlayer)
            }
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                onPlayingChangedForHistory(eventPlayer)
                updatePositionTicker()
            }
        }
    }

    private var shuffleOn = false

    /** Slot ids in the order to return to when shuffle is turned off. */
    private var originalOrder = LongArray(0)

    private val sessionCallback = object : MediaSession.Callback {

        /** Allows the app's queue commands on top of the defaults. */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(QueueCommands.SET_SHUFFLE)
                .add(QueueCommands.ADOPT_ORDER)
                .add(QueueCommands.GET_STATE)
                .add(QueueCommands.RESTORE_LAST)
                .add(QueueCommands.SET_SLEEP_TIMER)
                .add(QueueCommands.SET_AUDIO_PREFS)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        /**
         * Media items lose their URI on the way from the app to the service, so it is
         * restored from the media id, which is the song's content URI. Anything else is dropped.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val playable = mediaItems
                .filter { it.mediaId.startsWith(CONTENT_PREFIX) }
                .map { it.buildUpon().setUri(it.mediaId).build() }
                .toMutableList()
            return Futures.immediateFuture(playable)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == QueueCommands.ACTION_RESTORE_LAST) return restoreLast()
            val handled = handleQueueCommand(customCommand.customAction, args)
            val code = if (handled) SessionResult.RESULT_SUCCESS else SessionError.ERROR_NOT_SUPPORTED
            return Futures.immediateFuture(SessionResult(code, stateExtras()))
        }

        /**
         * A play button pressed while the app was closed (headset, Bluetooth, system media
         * controls): Media3 asks for the queue to resume with and starts it.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val store = memory ?: return Futures.immediateFailedFuture(UnsupportedOperationException(NOTHING_SAVED))
            return Futures.transform<RestoredQueue?, MediaSession.MediaItemsWithStartPosition>(
                store.load(),
                { restored ->
                    val items = restored?.queue?.items?.map { toMediaItem(it) }.orEmpty()
                    if (restored == null || items.isEmpty()) throw UnsupportedOperationException(NOTHING_SAVED)
                    player?.let { adoptRestoredModes(it, restored) }
                    MediaSession.MediaItemsWithStartPosition(
                        items,
                        restored.startIndex.coerceIn(0, items.size - 1),
                        restored.startPositionMs
                    )
                },
                ContextCompat.getMainExecutor(this@PlaybackService)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        memory = QueueMemory(this)
        val loader = EmbeddedArtBitmapLoader(this)
        artLoader = loader
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val settings = PlayerSettings(this)
        val eqA = BassSwapProcessor()
        val eqB = BassSwapProcessor()
        val deckA = buildPlayer(eqA, settings.skipSilence, settings.playbackSpeed)
        val deckB = buildPlayer(eqB, settings.skipSilence, settings.playbackSpeed)
        deckARef = deckA
        deckBRef = deckB
        deckA.addListener(focusTriggerListener)
        deckB.addListener(focusTriggerListener)
        deckA.addListener(memoryListener)
        deckB.addListener(memoryListener)
        player = deckA
        crossfader = DjCrossfader(deckA, deckB, eqA, eqB) { newActive ->
            if (sleepEndOfTrack) {
                finishSleepTimer()
            }
            player = newActive
            session?.player = newActive
            onTrackTransitionForHistory(newActive)
            savePositionNow()
            updatePositionTicker()
        }
        session = MediaSession.Builder(this, deckA)
            .setCallback(sessionCallback)
            .setSessionActivity(openPlayerIntent())
            .setBitmapLoader(loader)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return session
    }

    /** Swiping the app away stops the service unless music is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueueNow()
        savePositionNow()
        val current = session?.player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        checkListenProgress()
        main.removeCallbacks(listenCheckTask)
        main.removeCallbacks(savePositionTask)
        main.removeCallbacks(sleepTickTask)
        saveQueueNow()
        savePositionNow()
        memory?.shutdown()
        memory = null
        abandonAudioFocus()
        crossfader?.release()
        crossfader = null
        session?.release()
        session = null
        player = null
        deckARef = null
        deckBRef = null
        artLoader?.shutdown()
        artLoader = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ shuffle

    /** @return false for a command this service does not know */
    private fun handleQueueCommand(action: String, args: Bundle): Boolean {
        return when (action) {
            QueueCommands.ACTION_SET_SHUFFLE -> {
                setShuffle(args.getBoolean(QueueCommands.ARG_ENABLED, false))
                scheduleQueueSave()
                true
            }
            QueueCommands.ACTION_ADOPT_ORDER -> {
                shuffleOn = args.getBoolean(QueueCommands.ARG_ENABLED, false)
                originalOrder = args.getLongArray(QueueCommands.ARG_UIDS) ?: LongArray(0)
                scheduleQueueSave()
                true
            }
            QueueCommands.ACTION_SET_SLEEP_TIMER -> {
                val minutes = args.getInt(QueueCommands.ARG_MINUTES, 0)
                val endOfTrack = args.getBoolean(QueueCommands.ARG_END_OF_TRACK, false)
                configureSleepTimer(minutes, endOfTrack)
                true
            }
            QueueCommands.ACTION_SET_AUDIO_PREFS -> {
                val skipSilence = args.getBoolean(QueueCommands.ARG_SKIP_SILENCE, false)
                val speed = args.getFloat(QueueCommands.ARG_PLAY_SPEED, 1.0f)
                    .coerceIn(PlayerSettings.MIN_PLAY_SPEED, PlayerSettings.MAX_PLAY_SPEED)
                deckARef?.skipSilenceEnabled = skipSilence
                deckBRef?.skipSilenceEnabled = skipSilence
                player?.playbackParameters = PlaybackParameters(speed, 1f)
                true
            }
            QueueCommands.ACTION_GET_STATE -> true
            else -> false
        }
    }

    private fun stateExtras(): Bundle {
        val now = SystemClock.elapsedRealtime()
        val remaining = when {
            sleepEndOfTrack -> -1L
            sleepDeadlineMs > now -> sleepDeadlineMs - now
            else -> 0L
        }
        return Bundle().apply {
            putBoolean(QueueCommands.ARG_ENABLED, shuffleOn)
            putLong(QueueCommands.ARG_SLEEP_REMAINING_MS, remaining)
        }
    }

    private fun configureSleepTimer(minutes: Int, endOfTrack: Boolean) {
        main.removeCallbacks(sleepTickTask)
        sleepFadingSinceMs = 0L
        crossfader?.setSleepGain(1f)
        if (endOfTrack) {
            sleepEndOfTrack = true
            sleepDeadlineMs = 0L
            return
        }
        sleepEndOfTrack = false
        if (minutes <= 0) {
            sleepDeadlineMs = 0L
            return
        }
        sleepDeadlineMs = SystemClock.elapsedRealtime() + minutes * 60_000L
        main.postDelayed(sleepTickTask, SLEEP_TICK_MS)
    }

    private fun onSleepTick() {
        val now = SystemClock.elapsedRealtime()
        val remaining = sleepDeadlineMs - now
        if (remaining <= 0L) {
            finishSleepTimer()
            return
        }
        if (remaining <= SLEEP_FADE_MS) {
            val gain = (remaining.toFloat() / SLEEP_FADE_MS.toFloat()).coerceIn(0f, 1f)
            crossfader?.setSleepGain(gain)
            main.postDelayed(sleepTickTask, 100L)
        } else {
            main.postDelayed(sleepTickTask, minOf(SLEEP_TICK_MS, remaining - SLEEP_FADE_MS))
        }
    }

    private fun finishSleepTimer() {
        main.removeCallbacks(sleepTickTask)
        sleepDeadlineMs = 0L
        sleepEndOfTrack = false
        sleepFadingSinceMs = 0L
        crossfader?.abort()
        player?.pause()
        crossfader?.setSleepGain(1f)
    }

    /** Scrambles the queue around the playing song, or puts it back, without a gap in playback. */
    private fun setShuffle(enabled: Boolean) {
        if (shuffleOn == enabled) return
        shuffleOn = enabled
        crossfader?.abort()
        val exo = player ?: return
        val current = currentUids(exo)
        if (enabled) {
            originalOrder = current
            applyOrder(exo, QueueOrder.shuffled(current.size, exo.currentMediaItemIndex, Random.Default))
        } else {
            val order = QueueOrder.restored(originalOrder, current)
            originalOrder = LongArray(0)
            applyOrder(exo, order)
        }
    }

    private fun currentUids(exo: ExoPlayer): LongArray {
        return LongArray(exo.mediaItemCount) { index ->
            QueueCommands.uidOf(exo.getMediaItemAt(index)).takeIf { it != 0L } ?: -(index.toLong() + 1L)
        }
    }

    /**
     * Rearranges the queue so that seat i holds the song now at order[i]. The playing song
     * itself is never replaced, only moved, so playback carries on.
     */
    private fun applyOrder(exo: ExoPlayer, order: IntArray) {
        val count = exo.mediaItemCount
        if (count <= 1) return
        if (!QueueOrder.isPermutation(order, count) || QueueOrder.isIdentity(order)) return
        val activeIndex = exo.currentMediaItemIndex.coerceIn(0, count - 1)
        val seat = order.indexOf(activeIndex)
        if (seat < 0) return
        val items = List(count) { exo.getMediaItemAt(it) }
        val before = List(seat) { items[order[it]] }
        val after = List(count - seat - 1) { items[order[seat + 1 + it]] }
        try {
            if (activeIndex > 0) exo.moveMediaItem(activeIndex, 0)
            if (after.isEmpty()) exo.removeMediaItems(1, count) else exo.replaceMediaItems(1, count, after)
            if (before.isNotEmpty()) exo.addMediaItems(0, before)
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot reorder the queue", e)
        }
    }

    // ------------------------------------------------------------------ memory

    private fun scheduleQueueSave() {
        queueDirty = true
        main.removeCallbacks(saveQueueTask)
        main.postDelayed(saveQueueTask, QUEUE_SAVE_DELAY_MS)
    }

    /** Hands the queue to the background writer; an emptied queue forgets the old one. */
    private fun saveQueueNow() {
        main.removeCallbacks(saveQueueTask)
        if (!queueDirty) return
        val exo = player ?: return
        val store = memory ?: return
        queueDirty = false
        val count = minOf(exo.mediaItemCount, QueueCodec.MAX_ITEMS)
        val items = ArrayList<SavedItem>(count)
        for (index in 0 until count) {
            toSaved(exo.getMediaItemAt(index))?.let { items.add(it) }
        }
        if (items.isEmpty()) {
            store.clear()
            return
        }
        val order = if (shuffleOn) originalOrder.toList() else emptyList()
        store.saveQueue(SavedQueue(items, shuffleOn, order))
        savePositionNow()
    }

    private fun savePositionNow() {
        val exo = player ?: return
        val store = memory ?: return
        val item = exo.currentMediaItem ?: return
        store.savePosition(
            SavedPosition(
                index = exo.currentMediaItemIndex,
                uid = QueueCommands.uidOf(item),
                uri = item.mediaId,
                positionMs = maxOf(0L, exo.currentPosition),
                repeatMode = exo.repeatMode
            )
        )
    }

    /** While music plays, the position is saved every few seconds as well. */
    private fun updatePositionTicker() {
        main.removeCallbacks(savePositionTask)
        if (player?.isPlaying == true) main.postDelayed(savePositionTask, POSITION_SAVE_INTERVAL_MS)
    }

    // ------------------------------------------------------------------ listen tracking

    private fun onTrackTransitionForHistory(activePlayer: Player) {
        checkListenProgress()
        val item = activePlayer.currentMediaItem
        val mediaId = item?.mediaId.orEmpty()
        val uid = if (item != null) QueueCommands.uidOf(item) else 0L
        if (mediaId == trackedMediaId && uid == trackedUid) return
        trackedMediaId = mediaId
        trackedUid = uid
        trackedAccumulatedMs = 0L
        trackedCounted = false
        trackedPlayStartElapsedMs = if (activePlayer.isPlaying) SystemClock.elapsedRealtime() else 0L
        scheduleListenCheck()
    }

    private fun onPlayingChangedForHistory(activePlayer: Player) {
        val item = activePlayer.currentMediaItem
        val mediaId = item?.mediaId.orEmpty()
        val uid = if (item != null) QueueCommands.uidOf(item) else 0L
        if (mediaId != trackedMediaId || uid != trackedUid) {
            onTrackTransitionForHistory(activePlayer)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (activePlayer.isPlaying) {
            if (trackedPlayStartElapsedMs == 0L) trackedPlayStartElapsedMs = now
            checkListenProgress()
        } else {
            if (trackedPlayStartElapsedMs > 0L) {
                trackedAccumulatedMs += (now - trackedPlayStartElapsedMs).coerceAtLeast(0L)
                trackedPlayStartElapsedMs = 0L
            }
            checkListenProgress()
            main.removeCallbacks(listenCheckTask)
        }
    }

    private fun checkListenProgress() {
        main.removeCallbacks(listenCheckTask)
        val exo = player ?: return
        if (trackedCounted || !trackedMediaId.startsWith(CONTENT_PREFIX)) return
        val now = SystemClock.elapsedRealtime()
        if (exo.isPlaying && trackedPlayStartElapsedMs > 0L) {
            trackedAccumulatedMs += (now - trackedPlayStartElapsedMs).coerceAtLeast(0L)
            trackedPlayStartElapsedMs = now
        }
        val clip = exo.currentMediaItem?.clippingConfiguration
        val clipDuration = if (clip != null && clip.endPositionMs != C.TIME_END_OF_SOURCE && clip.endPositionMs > clip.startPositionMs) {
            clip.endPositionMs - clip.startPositionMs
        } else {
            0L
        }
        val playerDuration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val repoTrackDuration = LibraryRepository.get(this).findTrack(trackedMediaId)?.durationMs ?: 0L
        val effectiveDuration = when {
            clipDuration > 0L -> clipDuration
            playerDuration > 0L -> playerDuration
            else -> repoTrackDuration
        }
        val requiredMs = ListeningHistoryStore.requiredListenMs(effectiveDuration)
        if (trackedAccumulatedMs >= requiredMs) {
            trackedCounted = true
            LibraryRepository.get(this).history.recordPlay(trackedMediaId)
            return
        }
        scheduleListenCheck()
    }

    private fun scheduleListenCheck() {
        main.removeCallbacks(listenCheckTask)
        if (!trackedCounted && trackedMediaId.startsWith(CONTENT_PREFIX) && player?.isPlaying == true) {
            main.postDelayed(listenCheckTask, LISTEN_CHECK_INTERVAL_MS)
        }
    }

    /** Puts the saved queue back, paused, unless something is already queued. */
    private fun restoreLast(): ListenableFuture<SessionResult> {
        val store = memory
        val exo = player
        if (store == null || exo == null || exo.mediaItemCount > 0) {
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, restoreExtras()))
        }
        return Futures.transform<RestoredQueue?, SessionResult>(
            store.load(),
            { restored ->
                val live = player
                if (restored != null && live != null && live.mediaItemCount == 0) loadPaused(live, restored)
                SessionResult(SessionResult.RESULT_SUCCESS, restoreExtras())
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun loadPaused(exo: ExoPlayer, restored: RestoredQueue) {
        val items = restored.queue.items.map { toMediaItem(it) }
        if (items.isEmpty()) return
        adoptRestoredModes(exo, restored)
        try {
            exo.playWhenReady = false
            exo.setMediaItems(items, restored.startIndex.coerceIn(0, items.size - 1), restored.startPositionMs)
            exo.prepare()
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot restore the last queue", e)
        }
    }

    private fun adoptRestoredModes(exo: ExoPlayer, restored: RestoredQueue) {
        shuffleOn = restored.queue.shuffleOn
        originalOrder = if (shuffleOn) restored.queue.originalOrder.toLongArray() else LongArray(0)
        exo.repeatMode = restored.repeatMode
    }

    private fun restoreExtras(): Bundle {
        val extras = stateExtras()
        player?.let { extras.putLong(QueueCommands.ARG_MAX_UID, highestUid(it)) }
        return extras
    }

    private fun highestUid(exo: ExoPlayer): Long {
        val count = minOf(exo.mediaItemCount, QueueCodec.MAX_ITEMS)
        var highest = 0L
        for (index in 0 until count) {
            highest = maxOf(highest, QueueCommands.uidOf(exo.getMediaItemAt(index)))
        }
        return highest
    }

    /** @return null for anything that is not a song in the library */
    private fun toSaved(item: MediaItem): SavedItem? {
        if (!item.mediaId.startsWith(CONTENT_PREFIX)) return null
        val meta = item.mediaMetadata
        val clip = item.clippingConfiguration
        val end = if (clip.endPositionMs == C.TIME_END_OF_SOURCE) SavedItem.TO_END else clip.endPositionMs
        return SavedItem(
            uri = item.mediaId,
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            album = meta.albumTitle?.toString().orEmpty(),
            uid = QueueCommands.uidOf(item),
            clipStartMs = clip.startPositionMs,
            clipEndMs = end,
            crossfadeMs = QueueCommands.crossfadeOf(item),
            tempoRatio = QueueCommands.tempoRatioOf(item),
            gainDb = QueueCommands.gainDbOf(item)
        )
    }

    /** The same item the app builds, so mashup steps keep their part of the song and their blend. */
    private fun toMediaItem(saved: SavedItem): MediaItem {
        return QueueCommands.buildMediaItem(
            uri = saved.uri,
            title = saved.title,
            artist = saved.artist,
            album = saved.album,
            uid = saved.uid,
            clipStartMs = saved.clipStartMs,
            clipEndMs = saved.clipEndMs,
            crossfadeMs = saved.crossfadeMs,
            tempoRatio = saved.tempoRatio,
            gainDb = saved.gainDb
        )
    }

    // ------------------------------------------------------------------ setup

    private fun buildPlayer(bassSwap: BassSwapProcessor, skipSilence: Boolean, speed: Float): ExoPlayer {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(bassSwap))
                    .build()
            }
        }
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .setAudioAttributes(attributes, false)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                skipSilenceEnabled = skipSilence
                if (speed != 1.0f) playbackParameters = PlaybackParameters(speed, 1f)
            }
    }

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                PlatformAudioAttributes.Builder()
                    .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                    .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        if (manager.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            crossfader?.abort()
            player?.pause()
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
    }

    /** Tapping the notification opens the app straight on the full-screen player. */
    private fun openPlayerIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_PLAYER)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, OPEN_PLAYER_REQUEST, intent, flags)
    }

    private companion object {
        const val TAG = "PlaybackService"
        const val CONTENT_PREFIX = "content://"
        const val NOTHING_SAVED = "no saved queue to resume"
        const val QUEUE_SAVE_DELAY_MS = 1000L
        const val POSITION_SAVE_INTERVAL_MS = 5000L
        const val LISTEN_CHECK_INTERVAL_MS = 2000L
        const val SLEEP_TICK_MS = 1000L
        const val SLEEP_FADE_MS = 5000L
        const val OPEN_PLAYER_REQUEST = 1
    }
}
