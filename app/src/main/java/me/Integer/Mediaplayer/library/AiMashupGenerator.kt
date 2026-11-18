package me.Integer.Mediaplayer.library

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Generates mashups with the server from the settings: checks which songs the server
 * already knows, sends only the songs it has no audio for, has it analyse the rest, then
 * asks for routes.
 *
 * The server's analysis job id is remembered, so if Android kills the app mid-job the next
 * launch follows the same job ([resumeIfPending]). While a job runs, [MashupJobService]
 * keeps the process in the foreground.
 *
 * The public members are main thread only and [Listener] is called on the main thread.
 * The network work runs on one worker thread.
 */
class AiMashupGenerator private constructor(context: Context) {

    enum class Phase { IDLE, CONNECTING, UPLOADING, ANALYSING, ROUTING, COMPLETED, FAILED }

    enum class Failure { NONE, NO_SERVER, TOO_FEW_SONGS, SERVER, NO_ROUTES }

    /** Parameters for a mashup generation run. */
    data class Options(
        val playlistCount: Int = DEFAULT_PLAYLIST_COUNT,
        val tracksPerPlaylist: Int = DEFAULT_TRACKS_PER_PLAYLIST,
        val seedTrackUri: String = ""
    )

    /** One generated mashup; [route] is its compact text form. */
    data class Mix(val name: String, val score: Double, val segments: List<MixSegment>, val route: String)

    /**
     * [done] of [total] counts the songs being analysed, [skipped] the songs the server
     * could not use, and [detail] carries the server's own words for a [Failure.SERVER].
     */
    data class State(
        val phase: Phase = Phase.IDLE,
        val done: Int = 0,
        val total: Int = 0,
        val currentTitle: String = "",
        val skipped: Int = 0,
        val mixes: List<Mix> = emptyList(),
        val failure: Failure = Failure.NONE,
        val detail: String = "",
        /** Where the server analyses songs, such as cuda:0 NVIDIA GeForce RTX 5060 Ti. */
        val device: String = ""
    ) {
        val isRunning: Boolean
            get() = phase == Phase.CONNECTING || phase == Phase.UPLOADING ||
                phase == Phase.ANALYSING || phase == Phase.ROUTING
    }

    fun interface Listener {
        fun onMashupStateChanged(state: State)
    }

    private val appContext = context.applicationContext
    private val repository = LibraryRepository.get(appContext)
    private val jobStore = MashupJobStore(appContext)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Mediaplayer-AiMashup").apply { isDaemon = true }
    }

    /** Answers from an older run carry an older number and are dropped. Read by the worker. */
    @Volatile
    private var generation = 0L
    private var cancelFlag: AtomicBoolean? = null

    private var activeOptions = Options()

    var state: State = State()
        private set

    fun addListener(listener: Listener) {
        if (listener !in listeners) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // ------------------------------------------------------------------ commands

    /**
     * Starts generating mashups from [source]. A large source is cut down to a random
     * selection of [MAX_SOURCE_TRACKS] songs.
     *
     * @return false when a run is already active or it cannot start; the reason is in [state]
     */
    fun start(source: List<Track>, options: Options = Options()): Boolean {
        if (state.isRunning) return false
        val url = repository.settings.aiServerUrl
        if (url.isEmpty()) {
            publish(State(Phase.FAILED, failure = Failure.NO_SERVER))
            return false
        }
        activeOptions = options.copy(
            playlistCount = options.playlistCount.coerceIn(1, MAX_MIXES),
            tracksPerPlaylist = options.tracksPerPlaylist.coerceIn(MIN_TRACKS, MAX_SOURCE_TRACKS)
        )
        val items = pick(source, activeOptions.seedTrackUri).map { remoteOf(it) }
        if (items.size < MIN_TRACKS) {
            publish(State(Phase.FAILED, failure = Failure.TOO_FEW_SONGS))
            return false
        }
        jobStore.clear()
        launch(url) { run, cancelled, client -> generate(run, cancelled, client, items, url, activeOptions) }
        return true
    }

    /**
     * Picks up a server job that was running when the app was last closed or killed. Needs the
     * library to be loaded, so it is called again once the library arrives.
     *
     * @return true when a job was resumed
     */
    fun resumeIfPending(): Boolean {
        if (state.phase != Phase.IDLE || !repository.isLoaded) return false
        val pending = jobStore.load() ?: return false
        val url = repository.settings.aiServerUrl
        val items = repository.resolveTracks(pending.trackUris).map { remoteOf(it) }
        if (url.isEmpty() || url != pending.serverUrl || items.size < MIN_TRACKS) {
            jobStore.clear()
            return false
        }
        launch(url) { run, cancelled, client -> resume(run, cancelled, client, items, url, pending.jobId) }
        return true
    }

    /** Stops an active run, or clears a finished one. Nothing is saved. */
    fun cancel() {
        generation++
        cancelFlag?.set(true)
        cancelFlag = null
        jobStore.clear()
        publish(State())
    }

    /**
     * Stores [mix] as a mashup playlist; a blank [rawName] keeps the generated name.
     *
     * @return null when the playlist could not be created
     */
    fun save(mix: Mix, rawName: String?): Playlist? {
        val name = Playlist.cleanName(rawName).ifEmpty { mix.name }
        return repository.playlists.createMix(name, mix.segments)
    }

    private fun launch(url: String, job: (Long, AtomicBoolean, AiMashupClient) -> Unit) {
        val run = ++generation
        val cancelled = AtomicBoolean(false)
        cancelFlag = cancelled
        publish(State(Phase.CONNECTING))
        MashupJobService.start(appContext)
        val client = AiMashupClient(appContext, url, repository.settings.aiServerToken)
        worker.execute { runSafely(run, cancelled) { job(run, cancelled, client) } }
    }

    // ------------------------------------------------------------------ worker thread

    /** Turns any failure into a FAILED state, and forgets the job once the run has ended. */
    private fun runSafely(run: Long, cancelled: AtomicBoolean, body: () -> Unit) {
        try {
            body()
        } catch (e: IOException) {
            if (!cancelled.get()) {
                Log.w(TAG, "mashup generation failed: ${e.message}")
                post(run, State(Phase.FAILED, failure = Failure.SERVER, detail = e.message.orEmpty()))
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "mashup generation crashed", e)
            post(run, State(Phase.FAILED, failure = Failure.SERVER, detail = e.message.orEmpty()))
        } finally {
            // Runs after the final state; a cancelled run was already forgotten by cancel().
            main.post { if (run == generation && !state.isRunning) jobStore.clear() }
        }
    }

    /** A full run: cache check, uploads, analysis, routes. */
    private fun generate(
        run: Long,
        cancelled: AtomicBoolean,
        client: AiMashupClient,
        items: List<AiMashupClient.RemoteTrack>,
        url: String,
        options: Options = activeOptions
    ) {
        val device = client.ping().device
        val report = client.checkCache(items)
        val toSend = items.filter { it.id in report.missing && it.id !in report.stored }
        val notSent = upload(run, cancelled, client, toSend, device) ?: return
        if (cancelled.get()) return
        val toAnalyse = toSend.filter { it.id !in notSent } + items.filter { it.id in report.stored }
        if (toAnalyse.isNotEmpty()) {
            val jobId = client.startAnalysis(toAnalyse.map { it.id })
            jobStore.save(MashupJobStore.Pending(jobId, url, items.map { it.track.uri }))
            followJob(run, cancelled, client, jobId, toAnalyse.size, device, notSent.size)
        }
        if (cancelled.get()) return
        route(run, client, items, device, recheck = report.missing.isNotEmpty(), options = options)
    }

    /** Follows a remembered job; a job the server no longer knows starts a fresh run. */
    private fun resume(
        run: Long,
        cancelled: AtomicBoolean,
        client: AiMashupClient,
        items: List<AiMashupClient.RemoteTrack>,
        url: String,
        jobId: String
    ) {
        val device = client.ping().device
        val followed = try {
            followJob(run, cancelled, client, jobId, 0, device, 0)
            true
        } catch (e: AiMashupClient.ServerException) {
            Log.w(TAG, "cannot resume job $jobId, starting over: ${e.message}")
            false
        }
        if (cancelled.get()) return
        if (followed) {
            route(run, client, items, device, recheck = true)
        } else {
            generate(run, cancelled, client, items, url)
        }
    }

    /** Asks for routes over the usable songs; [recheck] asks the server which ones those are. */
    private fun route(
        run: Long,
        client: AiMashupClient,
        items: List<AiMashupClient.RemoteTrack>,
        device: String,
        recheck: Boolean,
        options: Options = activeOptions
    ) {
        val unusable = if (recheck) client.checkCache(items).missing else emptySet()
        val ready = items.filter { it.id !in unusable }
        val skipped = items.size - ready.size
        if (ready.size < MIN_TRACKS) {
            post(run, State(Phase.FAILED, skipped = skipped, failure = Failure.TOO_FEW_SONGS))
            return
        }
        post(run, State(Phase.ROUTING, skipped = skipped, device = device))
        val seedId = if (options.seedTrackUri.isNotEmpty()) idOf(options.seedTrackUri) else ""
        val remote = client.route(
            ready.map { it.id },
            options.playlistCount,
            options.tracksPerPlaylist,
            seedId
        )
        post(run, finished(remote, ready, skipped))
    }

    /**
     * Sends [toSend] a few songs at a time. The server only stores them, so this is limited
     * by the network. A song the server refuses is skipped; a network failure ends the run.
     *
     * @return the ids of the songs that were not sent, or null when the user cancelled
     */
    private fun upload(
        run: Long,
        cancelled: AtomicBoolean,
        client: AiMashupClient,
        toSend: List<AiMashupClient.RemoteTrack>,
        device: String
    ): Set<String>? {
        if (toSend.isEmpty()) return emptySet()
        val stop = AtomicBoolean(false)
        val failed = HashSet<String>()
        val pool = Executors.newFixedThreadPool(minOf(PARALLEL_UPLOADS, toSend.size)) { task ->
            Thread(task, "Mediaplayer-AiUpload").apply { isDaemon = true }
        }
        val completion = ExecutorCompletionService<Pair<AiMashupClient.RemoteTrack, Boolean>>(pool)
        try {
            for (item in toSend) completion.submit(Callable { item to uploadOne(client, item, stop) })
            post(run, State(Phase.UPLOADING, total = toSend.size, device = device))
            for (done in 1..toSend.size) {
                val (item, sent) = nextUpload(completion, cancelled) ?: return null
                if (!sent) failed.add(item.id)
                post(run, State(Phase.UPLOADING, done, toSend.size, item.track.title, failed.size, device = device))
            }
        } finally {
            stop.set(true)
            pool.shutdownNow()
        }
        return failed
    }

    /** The next finished upload, or null when the user cancelled while waiting. */
    private fun <T> nextUpload(completion: ExecutorCompletionService<T>, cancelled: AtomicBoolean): T? {
        for (wait in 0 until MAX_UPLOAD_WAITS) {
            if (cancelled.get()) return null
            val future = pollOnce(completion) ?: continue
            return uploadResult(future)
        }
        throw AiMashupClient.ServerException("an upload took too long")
    }

    private fun <T> pollOnce(completion: ExecutorCompletionService<T>): Future<T>? {
        return try {
            completion.poll(UPLOAD_POLL_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("interrupted while uploading")
        }
    }

    /** Unwraps the upload's own exception so a network failure ends the run as usual. */
    private fun <T> uploadResult(future: Future<T>): T {
        try {
            return future.get()
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is IOException -> throw cause
                is RuntimeException -> throw cause
                else -> throw IOException("upload failed: ${cause?.message}")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("interrupted while uploading")
        }
    }

    /**
     * Polls the analysis job [jobId] until it ends. [total] is the expected song count, or 0
     * when it is not known yet (a resumed job).
     */
    private fun followJob(
        run: Long,
        cancelled: AtomicBoolean,
        client: AiMashupClient,
        jobId: String,
        total: Int,
        device: String,
        uploadFailures: Int
    ) {
        post(run, State(Phase.ANALYSING, total = total, skipped = uploadFailures, device = device))
        for (poll in 0 until MAX_JOB_POLLS) {
            if (cancelled.get()) return
            pause(JOB_POLL_MS)
            val status = client.jobStatus(jobId)
            val skipped = uploadFailures + status.failed
            post(run, State(Phase.ANALYSING, status.done, status.total, skipped = skipped, device = device))
            if (status.finished) {
                if (!status.succeeded) {
                    throw AiMashupClient.ServerException(status.error.ifEmpty { "the analysis failed" })
                }
                return
            }
        }
        throw AiMashupClient.ServerException("the analysis did not finish in time")
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("interrupted while waiting for the server")
        }
    }

    /** @return false when the server could not use this one song */
    private fun uploadOne(
        client: AiMashupClient,
        item: AiMashupClient.RemoteTrack,
        cancelled: AtomicBoolean
    ): Boolean {
        return try {
            client.upload(item, cancelled)
            true
        } catch (e: AiMashupClient.ServerException) {
            Log.w(TAG, "skipping ${item.track.fileName}: ${e.message}")
            false
        }
    }

    private fun finished(
        remote: List<AiMashupClient.RemoteMix>,
        ready: List<AiMashupClient.RemoteTrack>,
        skipped: Int
    ): State {
        val byId = ready.associate { it.id to it.track }
        val byUri = ready.associate { it.track.uri to it.track }
        val mixes = ArrayList<Mix>()
        for (item in remote.take(MAX_MIXES)) {
            val segments = MashupRouteParser.segmentsOfSteps(item.steps) { byId[it] }
            if (segments == null) {
                Log.w(TAG, "dropping the unusable route ${item.name}")
                continue
            }
            val name = item.name.ifEmpty { DEFAULT_NAME }
            mixes.add(Mix(name, item.score, segments, MashupRouteParser.format(segments) { byUri[it] }))
        }
        if (mixes.isEmpty()) return State(Phase.FAILED, skipped = skipped, failure = Failure.NO_ROUTES)
        return State(Phase.COMPLETED, skipped = skipped, mixes = mixes)
    }

    private fun post(run: Long, next: State) {
        main.post { if (run == generation) publish(next) }
    }

    // ------------------------------------------------------------------ helpers

    private fun publish(next: State) {
        state = next
        for (listener in ArrayList(listeners)) listener.onMashupStateChanged(next)
    }

    /** Each song once, without songs too short to analyse, keeping [seedUri] if chosen, up to [MAX_SOURCE_TRACKS]. */
    private fun pick(source: List<Track>, seedUri: String = ""): List<Track> {
        val usable = source.asSequence()
            .filter { it.uri.isNotEmpty() }
            .filter { it.durationMs == 0L || it.durationMs >= MIN_DURATION_MS }
            .distinctBy { it.uri }
            .toList()
        if (usable.size <= MAX_SOURCE_TRACKS) return usable
        val seed = usable.firstOrNull { it.uri == seedUri }
        val rest = usable.filter { it.uri != seedUri }.shuffled()
        return if (seed != null) listOf(seed) + rest.take(MAX_SOURCE_TRACKS - 1) else rest.take(MAX_SOURCE_TRACKS)
    }

    private fun remoteOf(track: Track): AiMashupClient.RemoteTrack {
        return AiMashupClient.RemoteTrack(idOf(track.uri), fingerprintOf(track), track, repository.displayArtist(track))
    }

    /** Changes when the file does, so the server analyses an edited song again. */
    private fun fingerprintOf(track: Track): String {
        return "${track.sizeBytes}-${track.modifiedMs}"
    }

    /** A short id made of letters and digits only, safe as a header value and a file name. */
    private fun idOf(uri: String): String {
        val digest = try {
            MessageDigest.getInstance(HASH).digest(uri.toByteArray(Charsets.UTF_8))
        } catch (e: NoSuchAlgorithmException) {
            Log.w(TAG, "no $HASH on this device, using a weaker song id")
            return "u" + Integer.toHexString(uri.hashCode()) + "x" + uri.length
        }
        val count = minOf(ID_BYTES, digest.size)
        val hex = StringBuilder(count * 2)
        for (index in 0 until count) {
            val value = digest[index].toInt() and 0xFF
            hex.append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
        return hex.toString()
    }

    companion object {
        private const val TAG = "AiMashupGenerator"
        private const val HASH = "SHA-256"
        private const val HEX = "0123456789abcdef"
        private const val ID_BYTES = 16
        private const val DEFAULT_NAME = "AI Mashup"
        private const val MIN_TRACKS = 2

        /** The server does not analyse songs shorter than 18 seconds. */
        private const val MIN_DURATION_MS = 20000L
        const val DEFAULT_PLAYLIST_COUNT = 3
        const val DEFAULT_TRACKS_PER_PLAYLIST = 5
        private const val MAX_MIXES = 20

        /** Songs sent at the same time; more rarely helps on Wi-Fi. */
        private const val PARALLEL_UPLOADS = 3
        private const val UPLOAD_POLL_MS = 500L

        /** 20 minutes of waiting for one upload to finish. */
        private const val MAX_UPLOAD_WAITS = 2400
        private const val JOB_POLL_MS = 1500L

        /** 30 minutes of waiting for the server's analysis. */
        private const val MAX_JOB_POLLS = 1200

        /** Routing time grows quickly with the number of songs, so one run is bounded. */
        const val MAX_SOURCE_TRACKS = 40

        @Volatile
        private var instance: AiMashupGenerator? = null

        fun get(context: Context): AiMashupGenerator {
            return instance ?: synchronized(this) {
                instance ?: AiMashupGenerator(context.applicationContext).also { instance = it }
            }
        }
    }
}
