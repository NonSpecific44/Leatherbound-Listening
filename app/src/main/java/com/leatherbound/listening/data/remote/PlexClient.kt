package com.leatherbound.listening.data.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

@Serializable private data class PlexEnvelope(@SerialName("MediaContainer") val container: PlexContainer = PlexContainer())

@Serializable private data class PlexContainer(
    @SerialName("Metadata") val metadata: List<PlexMetadata> = emptyList(),
    @SerialName("Directory") val directories: List<PlexDirectory> = emptyList(),
)

@Serializable private data class PlexDirectory(val key: String = "", val title: String = "", val type: String = "")

@Serializable private data class PlexMetadata(
    val ratingKey: String = "",
    val title: String = "",
    val titleSort: String? = null,
    val parentTitle: String? = null,
    val parentRatingKey: String? = null,
    val grandparentTitle: String? = null,
    val originalTitle: String? = null,
    val year: Int? = null,
    val summary: String? = null,
    val thumb: String? = null,
    val parentThumb: String? = null,
    val addedAt: Long = 0,
    val duration: Long = 0,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val viewOffset: Long? = null,
    val viewCount: Int? = null,
    val lastViewedAt: Long? = null,
    @SerialName("Media") val media: List<PlexMedia> = emptyList(),
    @SerialName("Chapter") val chapters: List<PlexChapter> = emptyList(),
)

@Serializable private data class PlexMedia(val container: String? = null, val duration: Long = 0, @SerialName("Part") val parts: List<PlexPart> = emptyList())
@Serializable private data class PlexPart(val key: String = "", val duration: Long = 0)
@Serializable private data class PlexChapter(val tag: String? = null, val index: Int = 0, val startTimeOffset: Long = 0, val endTimeOffset: Long = 0)

@Serializable data class PlexPin(val id: Long, val code: String, val authToken: String? = null)

@Serializable data class PlexResource(
    val name: String = "",
    val clientIdentifier: String = "",
    val provides: String = "",
    val accessToken: String? = null,
    val connections: List<PlexConnection> = emptyList(),
)

@Serializable data class PlexConnection(
    val uri: String,
    val local: Boolean = false,
    val relay: Boolean = false,
    val address: String = "",
    val port: Int = 32400,
    @SerialName("IPv6") val ipv6: Boolean = false,
)

/** [connections] are every address the server can be reached on, best first; [baseUrl] is the one that answered. */
@Serializable data class PlexUser(val username: String = "", val title: String = "", val friendlyName: String = "")

data class PlexServer(val name: String, val baseUrl: String, val token: String, val connections: List<String>)

/**
 * Plex keeps audiobooks in music libraries: artist = author, album = book, tracks = files.
 * Collections in that library are treated as series.
 */
class PlexClient(private val clientId: String) {

    private fun request(url: String, token: String?) = Request.Builder().url(url)
        .header("Accept", "application/json")
        .header("X-Plex-Product", PRODUCT)
        .header("X-Plex-Client-Identifier", clientId)
        .header("X-Plex-Device-Name", "Android")
        .apply { if (!token.isNullOrBlank()) header("X-Plex-Token", token) }

    // ---- Account sign-in with a PIN (the user approves it on plex.tv) ----

    suspend fun createPin(): PlexPin = Http.json.decodeFromString(
        Http.send(request("https://plex.tv/api/v2/pins?strong=true", null).post(ByteArray(0).toRequestBody()).build())
    )

    fun authUrl(pin: PlexPin): String {
        val product = URLEncoder.encode(PRODUCT, "UTF-8")
        return "https://app.plex.tv/auth#?clientID=$clientId&code=${pin.code}&context%5Bdevice%5D%5Bproduct%5D=$product"
    }

    /** Polls until the PIN is claimed. Returns the account token, or null after [timeoutMs]. */
    suspend fun awaitToken(pin: PlexPin, timeoutMs: Long = 5 * 60_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val p: PlexPin = Http.json.decodeFromString(Http.send(request("https://plex.tv/api/v2/pins/${pin.id}", null).build()))
            if (!p.authToken.isNullOrBlank()) return p.authToken
            delay(2000)
        }
        return null
    }

    /** Servers on the account, each connected through its best reachable address. */
    suspend fun servers(accountToken: String): List<PlexServer> = coroutineScope {
        val body = Http.send(request("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", accountToken).build())
        val resources: List<PlexResource> = Http.json.decodeFromString(body)
        resources.filter { "server" in it.provides }.map { res ->
            async {
                val token = res.accessToken ?: accountToken
                val candidates = candidates(res.connections)
                val best = fastest(candidates, token) ?: return@async null
                PlexServer(res.name, best, token, candidates)
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Preference order: local secure, local plain HTTP (works when a router blocks plex.direct DNS),
     * remote direct, and only then Plex's bandwidth-capped relay.
     */
    internal fun candidates(connections: List<PlexConnection>): List<String> {
        val local = connections.filter { it.local && !it.relay }
        val plainLocal = local.filter { !it.ipv6 && it.address.isNotBlank() }.map { "http://${it.address}:${it.port}" }
        val remote = connections.filter { !it.local && !it.relay }.sortedBy { it.ipv6 }
        val relay = connections.filter { it.relay }
        return (local.map { it.uri } + plainLocal + remote.map { it.uri } + relay.map { it.uri })
            .map { it.trimEnd('/') }.distinct()
    }

    /**
     * Probes every address at once and returns the most preferred one that answers. Total wait is
     * bounded by one probe timeout, not one per address.
     */
    suspend fun fastest(candidates: List<String>, token: String): String? = coroutineScope {
        val probes = candidates.map { uri -> async { uri.takeIf { reachable(it, token) } } }
        var winner: String? = null
        for (probe in probes) {
            winner = probe.await()
            if (winner != null) break
        }
        probes.forEach { it.cancel() }
        winner
    }

    private suspend fun reachable(uri: String, token: String): Boolean = runCatching {
        Http.send(request("$uri/identity", token).build(), Http.probeClient); true
    }.getOrDefault(false)

    /** Finds the account resource for an already-added server, to recover its address list. */
    suspend fun connectionsFor(token: String, currentBase: String): List<String> {
        val body = Http.send(request("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", token).build())
        val resources: List<PlexResource> = Http.json.decodeFromString(body)
        val server = resources.filter { "server" in it.provides }.firstOrNull { res ->
            res.accessToken == token || res.connections.any { it.uri.trimEnd('/') == currentBase }
        } ?: return emptyList()
        return candidates(server.connections)
    }

    // ---- Library ----

    private suspend fun get(base: String, path: String, token: String): PlexContainer =
        Http.json.decodeFromString<PlexEnvelope>(Http.send(request(base + path, token).build())).container

    suspend fun libraries(base: String, token: String): List<RemoteLibrary> =
        get(base, "/library/sections", token).directories
            .filter { it.type == "artist" }
            .map { RemoteLibrary(it.key, it.title) }

    suspend fun books(base: String, token: String, libraryId: String): List<RemoteBook> = coroutineScope {
        val albumsJob = async { get(base, "/library/sections/$libraryId/all?type=9", token).metadata }
        val tracksJob = async { get(base, "/library/sections/$libraryId/all?type=10", token).metadata }
        val seriesJob = async { runCatching { series(base, token, libraryId) }.getOrDefault(emptyMap()) }
        val tracksByAlbum = tracksJob.await().groupBy { it.parentRatingKey }
        val series = seriesJob.await()

        albumsJob.await().mapNotNull { album ->
            val files = tracksByAlbum[album.ratingKey].orEmpty()
                .sortedWith(compareBy({ it.parentIndex ?: 0 }, { it.index ?: 0 }))
                .mapNotNull { t ->
                    val part = t.media.firstOrNull()?.parts?.firstOrNull() ?: return@mapNotNull null
                    t to RemoteTrack(
                        remoteId = t.ratingKey,
                        title = t.title,
                        url = "$base${part.key}?X-Plex-Token=$token",
                        durationMs = t.duration.takeIf { it > 0 } ?: part.duration,
                        container = t.media.firstOrNull()?.container,
                    )
                }
            if (files.isEmpty()) return@mapNotNull null
            val tracks = files.map { it.second }
            val progress = bookProgress(files.map { (meta, track) ->
                TrackProgress(track.durationMs, meta.viewOffset, (meta.viewCount ?: 0) > 0, (meta.lastViewedAt ?: 0) * 1000)
            })
            val (seriesName, seriesIndex) = series[album.ratingKey] ?: (null to null)
            RemoteBook(
                remoteId = album.ratingKey,
                title = album.title,
                sortTitle = album.titleSort ?: album.title,
                author = album.parentTitle ?: "Unknown author",
                year = album.year,
                description = album.summary,
                coverUrl = album.thumb?.let { "$base/photo/:/transcode?width=600&height=600&minSize=1&url=${URLEncoder.encode(it, "UTF-8")}&X-Plex-Token=$token" },
                addedAt = album.addedAt * 1000,
                tracks = tracks,
                // Single-file books may carry embedded chapters; those are fetched when the book is opened.
                chapters = null,
                seriesName = seriesName,
                seriesIndex = seriesIndex,
                serverProgress = progress,
            )
        }
    }

    /** album ratingKey -> (collection title, 1-based position). */
    private suspend fun series(base: String, token: String, libraryId: String): Map<String, Pair<String, Float>> = coroutineScope {
        get(base, "/library/sections/$libraryId/collections", token).metadata.map { collection ->
            async {
                get(base, "/library/collections/${collection.ratingKey}/children", token).metadata
                    .mapIndexed { i, album -> album.ratingKey to (collection.title to (i + 1).toFloat()) }
            }
        }.awaitAll().flatten().toMap()
    }

    /** Embedded chapters for each track, converted to whole-book time. */
    suspend fun chapters(base: String, token: String, tracks: List<Pair<String, Long>>): List<RemoteChapter> {
        val result = mutableListOf<RemoteChapter>()
        for ((ratingKey, offset) in tracks) {
            val meta = get(base, "/library/metadata/$ratingKey?includeChapters=1", token).metadata.firstOrNull() ?: continue
            meta.chapters.sortedBy { it.startTimeOffset }.forEachIndexed { i, c ->
                result += RemoteChapter(c.tag ?: "Chapter ${i + 1}", offset + c.startTimeOffset, offset + c.endTimeOffset)
            }
        }
        return result
    }

    /** The account holder's display name: Plex's friendly name, else the username. */
    suspend fun accountName(token: String): String? {
        val user: PlexUser = Http.json.decodeFromString(Http.send(request("https://plex.tv/api/v2/user", token).build()))
        return listOf(user.friendlyName, user.title, user.username).firstOrNull { it.isNotBlank() }?.let(::firstName)
    }

    suspend fun markPlayed(base: String, token: String, ratingKey: String) {
        Http.send(request("$base/:/scrobble?identifier=com.plexapp.plugins.library&key=$ratingKey", token).build())
    }

    /** Saves your place in one file so Plex's own apps (and this app on other devices) can pick it up. */
    suspend fun reportProgress(base: String, token: String, trackKey: String, positionMs: Long, durationMs: Long, playing: Boolean) {
        val state = if (playing) "playing" else "paused"
        Http.send(
            request(
                "$base/:/timeline?ratingKey=$trackKey&key=%2Flibrary%2Fmetadata%2F$trackKey&state=$state&time=$positionMs&duration=$durationMs",
                token,
            ).build()
        )
        // /:/timeline only sticks once Plex knows the client; /:/progress always records viewOffset.
        Http.send(request("$base/:/progress?key=$trackKey&identifier=com.plexapp.plugins.library&time=$positionMs&state=$state", token).build())
    }

    companion object { const val PRODUCT = "Leatherbound Listening" }
}
