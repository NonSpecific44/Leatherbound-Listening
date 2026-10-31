package com.leatherbound.listening.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Server-neutral description of a book, produced by each client during a sync. */
data class RemoteBook(
    val remoteId: String,
    val title: String,
    val sortTitle: String,
    val author: String,
    val narrator: String? = null,
    val year: Int? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val addedAt: Long = 0,
    val tracks: List<RemoteTrack>,
    /** Whole-book chapter marks; null means "not fetched yet". */
    val chapters: List<RemoteChapter>?,
    val seriesName: String? = null,
    val seriesIndex: Float? = null,
    /** Where the server says you are, and when that was recorded (0 = never played there). */
    val serverProgress: ServerProgress = ServerProgress(),
    /** Local folders: fingerprint of the files; [unchanged] means "same as last scan, keep what you have". */
    val signature: String? = null,
    val unchanged: Boolean = false,
)

data class ServerProgress(val positionMs: Long? = null, val updatedAt: Long = 0, val finished: Boolean = false)

/** One file's play state as a server reports it. */
data class TrackProgress(val durationMs: Long, val positionMs: Long?, val played: Boolean, val lastPlayedAt: Long)

/**
 * Collapses per-file play state into a whole-book position: the most recently played file wins,
 * at its saved offset (or its end, if the server only knows it was played through).
 */
fun bookProgress(parts: List<TrackProgress>): ServerProgress {
    if (parts.isEmpty()) return ServerProgress()
    var offset = 0L
    val starts = parts.map { p -> offset.also { offset += p.durationMs } }
    val latest = parts.indices.filter { parts[it].lastPlayedAt > 0 }.maxByOrNull { parts[it].lastPlayedAt }
    val finished = parts.all { it.played }
    if (latest == null) return ServerProgress(finished = finished)
    val p = parts[latest]
    val within = p.positionMs?.takeIf { it > 0 } ?: if (p.played) p.durationMs else 0L
    return ServerProgress(starts[latest] + within, p.lastPlayedAt, finished)
}

data class RemoteTrack(
    val remoteId: String,
    val title: String,
    val url: String,
    val durationMs: Long,
    val container: String? = null,
)

data class RemoteChapter(val title: String, val startMs: Long, val endMs: Long)

data class RemoteLibrary(val id: String, val name: String)

object Http {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** For reachability probes: give up quickly so a dead address never holds anything up. */
    val probeClient: OkHttpClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    /** Cancelling the coroutine cancels the HTTP call, so losing probes stop immediately. */
    suspend fun send(request: Request, http: OkHttpClient = client): String = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) cont.resumeWithException(IOException("HTTP ${it.code} from ${request.url.encodedPath}"))
                    else runCatching { it.body?.string().orEmpty() }.fold(cont::resume, cont::resumeWithException)
                }
            }
        })
    }
}

/** "Fergus Eaton" -> "Fergus"; usernames like "fergus44" are left as they are. */
fun firstName(name: String): String = name.trim().split(Regex("\\s+")).first()

/** Turns per-track durations into whole-book offsets and fills in one chapter per track when none exist. */
fun RemoteBook.fallbackChapters(): List<RemoteChapter> {
    var offset = 0L
    return tracks.map { t ->
        RemoteChapter(t.title, offset, offset + t.durationMs).also { offset += t.durationMs }
    }
}
