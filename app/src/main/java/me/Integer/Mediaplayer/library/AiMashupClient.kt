package me.Integer.Mediaplayer.library

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Talks to the mashup server (ai_router/server.py). Every call blocks, so this is for a
 * worker thread only. Every failure is an [IOException]; a [ServerException] means the
 * server answered but refused the request or sent something unusable.
 */
class AiMashupClient(context: Context, baseUrl: String, token: String = "") {

    class ServerException(message: String) : IOException(message)

    /** A library song as the server knows it: a short stable [id] and a change [fingerprint]. */
    data class RemoteTrack(val id: String, val fingerprint: String, val track: Track, val artist: String)

    data class RemoteMix(val name: String, val score: Double, val steps: List<MashupRouteParser.Step>)

    /** What the server reports about itself; [device] is where it analyses songs. */
    data class Health(val cachedTracks: Int, val device: String)

    /**
     * [missing] are songs the server cannot use yet; [stored] is the part of them whose audio
     * the server already holds, so they need analysing but no upload.
     */
    data class CacheReport(val missing: Set<String>, val stored: Set<String>)

    /** Progress of a server-side analysis job; [failed] counts songs it could not use. */
    data class JobStatus(val phase: String, val done: Int, val total: Int, val failed: Int, val error: String) {
        val finished: Boolean
            get() = phase == "done" || phase == "failed"

        val succeeded: Boolean
            get() = phase == "done"
    }

    private val resolver = context.applicationContext.contentResolver
    private val base = PlayerSettings.sanitiseServerUrl(baseUrl)
    private val authToken = PlayerSettings.sanitiseToken(token)

    // ------------------------------------------------------------------ requests

    /** Checks that this is a mashup server and reports its cache size and device. */
    fun ping(): Health {
        val answer = getJson(PATH_HEALTH, QUICK_TIMEOUT_MS)
        if (answer.optString("status") != "ok") throw ServerException("this is not a mashup server")
        return Health(answer.optInt("cached_tracks", 0), answer.optString("device").ifEmpty { "cpu" })
    }

    /** Which of [tracks] the server has not analysed yet (or whose file changed since). */
    fun checkCache(tracks: List<RemoteTrack>): CacheReport {
        val body = encoding {
            val array = JSONArray()
            for (item in tracks) {
                array.put(JSONObject().put("id", item.id).put("fingerprint", item.fingerprint))
            }
            JSONObject().put("tracks", array)
        }
        val answer = postJson(PATH_CHECK, body, QUICK_TIMEOUT_MS)
        val missingArray = answer.optJSONArray("missing_ids") ?: throw ServerException("the cache answer is incomplete")
        val missing = idsOf(missingArray, tracks.size)
        // Servers from before stored_ids leave it out; then every missing song is uploaded.
        val stored = idsOf(answer.optJSONArray("stored_ids"), tracks.size).filterTo(HashSet()) { it in missing }
        return CacheReport(missing, stored)
    }

    private fun idsOf(array: JSONArray?, limit: Int): Set<String> {
        if (array == null) return emptySet()
        val ids = HashSet<String>()
        for (i in 0 until minOf(array.length(), limit)) {
            val id = array.optString(i)
            if (id.isNotEmpty()) ids.add(id)
        }
        return ids
    }

    /**
     * Sends one song. The server only stores it, so this is limited by the network;
     * [startAnalysis] analyses the stored songs afterwards.
     *
     * @throws ServerException when the server cannot use this song
     * @throws InterruptedIOException when [cancelled] was set during the upload
     */
    fun upload(item: RemoteTrack, cancelled: AtomicBoolean) {
        val uri = Uri.parse(item.track.uri)
        val length = lengthOf(uri, item.track)
        if (length <= 0L || length > MAX_UPLOAD_BYTES) {
            throw ServerException("the file size $length cannot be sent")
        }
        val connection = open(PATH_UPLOAD, "POST", UPLOAD_TIMEOUT_MS)
        try {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(length)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("X-Track-Id", item.id)
            connection.setRequestProperty("X-Track-Fingerprint", item.fingerprint)
            connection.setRequestProperty("X-Track-Title", headerValue(item.track.title))
            connection.setRequestProperty("X-Track-Artist", headerValue(item.artist))
            connection.setRequestProperty("X-Track-Filename", headerValue(item.track.fileName))
            openSong(uri).use { source ->
                connection.outputStream.use { sink -> copy(source, sink, length, cancelled) }
            }
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    /** Starts analysing the uploaded songs [ids] on the server; returns the job id. */
    fun startAnalysis(ids: List<String>): String {
        val body = encoding { JSONObject().put("track_ids", JSONArray(ids)) }
        val answer = postJson(PATH_ANALYZE, body, QUICK_TIMEOUT_MS)
        val jobId = answer.optString("job_id")
        val usable = jobId.isNotEmpty() && jobId.length <= MAX_JOB_ID_CHARS &&
            jobId.all { it in '0'..'9' || it in 'a'..'z' }
        if (!usable) throw ServerException("the server sent no usable job id")
        return jobId
    }

    /** The progress of the analysis job [jobId] from [startAnalysis]. */
    fun jobStatus(jobId: String): JobStatus {
        val answer = getJson(PATH_JOBS + jobId, QUICK_TIMEOUT_MS)
        return JobStatus(
            phase = answer.optString("phase"),
            done = answer.optInt("done", 0),
            total = answer.optInt("total", 0),
            failed = answer.optInt("failed", 0),
            error = answer.optString("error")
        )
    }

    /** Asks for mashups made from the analysed songs [ids]. */
    fun route(
        ids: List<String>,
        playlistCount: Int,
        tracksPerPlaylist: Int,
        seedTrackId: String = ""
    ): List<RemoteMix> {
        val body = encoding {
            JSONObject()
                .put("track_ids", JSONArray(ids))
                .put("playlist_count", playlistCount)
                .put("tracks_per_playlist", tracksPerPlaylist)
                .put("seed_track_id", seedTrackId)
        }
        val answer = postJson(PATH_ROUTE, body, ROUTE_TIMEOUT_MS)
        val array = answer.optJSONArray("playlists") ?: throw ServerException("the route answer is incomplete")
        val count = minOf(array.length(), MAX_MIXES)
        val mixes = ArrayList<RemoteMix>(count)
        for (i in 0 until count) {
            val item = array.optJSONObject(i) ?: continue
            mixes.add(toMix(item))
        }
        return mixes
    }

    private fun toMix(item: JSONObject): RemoteMix {
        val array = item.optJSONArray("segments") ?: JSONArray()
        val count = minOf(array.length(), MashupRouteParser.MAX_STEPS)
        val steps = ArrayList<MashupRouteParser.Step>(count)
        for (i in 0 until count) {
            val step = array.optJSONObject(i) ?: continue
            steps.add(
                MashupRouteParser.Step(
                    trackId = step.optString("track_id"),
                    startMs = step.optLong("start_ms", 0L),
                    endMs = step.optLong("end_ms", MixSegment.END_OF_TRACK),
                    crossfadeMs = step.optLong("crossfade_ms", MixSegment.DEFAULT_CROSSFADE_MS),
                    tempoRatio = step.optDouble("tempo_ratio", 1.0).toFloat(),
                    gainDb = step.optDouble("gain_db", 0.0).toFloat()
                )
            )
        }
        return RemoteMix(item.optString("name"), item.optDouble("score", 0.0), steps)
    }

    // ------------------------------------------------------------------ transport

    private fun open(path: String, method: String, readTimeoutMs: Int): HttpURLConnection {
        if (base.isEmpty()) throw ServerException("no server address is set")
        val connection = URL(base + path).openConnection() as? HttpURLConnection
            ?: throw ServerException("the server address is not an http address")
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        if (authToken.isNotEmpty()) connection.setRequestProperty(TOKEN_HEADER, authToken)
        return connection
    }

    private fun getJson(path: String, readTimeoutMs: Int): JSONObject {
        val connection = open(path, "GET", readTimeoutMs)
        try {
            return readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(path: String, body: JSONObject, readTimeoutMs: Int): JSONObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val connection = open(path, "POST", readTimeoutMs)
        try {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(bytes) }
            return readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    /** The JSON answer of a successful request; an error status becomes a [ServerException]. */
    private fun readJson(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val ok = code in 200..299
        val stream = if (ok) connection.inputStream else connection.errorStream
        val text = stream?.use { readBounded(it) }.orEmpty()
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            null
        }
        if (!ok) throw ServerException(json?.optString("error").orEmpty().ifEmpty { "HTTP $code" })
        return json ?: throw ServerException("the server sent an unreadable answer")
    }

    private fun readBounded(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        for (reads in 0 until MAX_RESPONSE_READS) {
            val count = input.read(buffer)
            if (count < 0) return String(out.toByteArray(), Charsets.UTF_8)
            if (out.size() + count > MAX_RESPONSE_BYTES) throw ServerException("the server answer is too large")
            out.write(buffer, 0, count)
        }
        throw ServerException("the server answer did not end")
    }

    private inline fun <T> encoding(block: () -> T): T {
        return try {
            block()
        } catch (e: JSONException) {
            throw ServerException("cannot encode the request: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ song files

    private fun openSong(uri: Uri): InputStream {
        val stream = try {
            resolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw ServerException("no access to the song file: ${e.message}")
        }
        return stream ?: throw ServerException("the song file cannot be opened")
    }

    /** The real size of the file, or the size from the last scan when it cannot be measured. */
    private fun lengthOf(uri: Uri, track: Track): Long {
        val measured = try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        } catch (e: IOException) {
            Log.w(TAG, "cannot measure ${track.fileName}: ${e.message}")
            0L
        } catch (e: RuntimeException) {
            // SecurityException or a failing content provider.
            Log.w(TAG, "cannot measure ${track.fileName}: ${e.message}")
            0L
        }
        return if (measured > 0L) measured else track.sizeBytes
    }

    /** Sends at most [length] bytes; a shorter file makes the connection fail on close. */
    private fun copy(source: InputStream, sink: OutputStream, length: Long, cancelled: AtomicBoolean) {
        val buffer = ByteArray(BUFFER_BYTES)
        var sent = 0L
        for (reads in 0 until MAX_COPY_READS) {
            if (cancelled.get()) throw InterruptedIOException("cancelled")
            if (sent >= length) return
            val want = minOf(buffer.size.toLong(), length - sent).toInt()
            val count = source.read(buffer, 0, want)
            if (count < 0) return
            sink.write(buffer, 0, count)
            sent += count
        }
        throw ServerException("the song took too many reads to send")
    }

    /** Header values must be plain ASCII, so text is URL-encoded; the server decodes it. */
    private fun headerValue(text: String): String {
        return URLEncoder.encode(text.take(MAX_HEADER_CHARS), "UTF-8")
    }

    private companion object {
        const val TAG = "AiMashupClient"
        const val TOKEN_HEADER = "X-Auth-Token"
        const val PATH_HEALTH = "/api/v1/health"
        const val PATH_CHECK = "/api/v1/check-cache"
        const val PATH_UPLOAD = "/api/v1/upload-track"
        const val PATH_ROUTE = "/api/v1/route"
        const val PATH_ANALYZE = "/api/v1/analyze"
        const val PATH_JOBS = "/api/v1/jobs/"
        const val MAX_JOB_ID_CHARS = 64
        const val CONNECT_TIMEOUT_MS = 8000
        const val QUICK_TIMEOUT_MS = 15000

        /** Time to wait for the server's answer once a song is sent; it only stores the file. */
        const val UPLOAD_TIMEOUT_MS = 2 * 60 * 1000
        const val ROUTE_TIMEOUT_MS = 10 * 60 * 1000

        /** The same limit as MAX_UPLOAD_BYTES in server.py. */
        const val MAX_UPLOAD_BYTES = 80L * 1024L * 1024L
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_RESPONSE_READS = 100000
        const val MAX_COPY_READS = 1000000
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_HEADER_CHARS = 200
        const val MAX_MIXES = 20
    }
}
