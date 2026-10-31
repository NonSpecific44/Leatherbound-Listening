package com.leatherbound.listening.data.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable private data class AbsLoginRequest(val username: String, val password: String)
@Serializable private data class AbsLoginResponse(val user: AbsUser)
@Serializable private data class AbsUser(
    val id: String = "",
    val username: String = "",
    /** Tokens from before Audiobookshelf 2.26; they don't expire. */
    val token: String? = null,
    /** 2.26 and later: a short-lived access token and the refresh token that renews it. */
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val mediaProgress: List<AbsProgress> = emptyList(),
)
@Serializable private data class AbsProgress(
    val libraryItemId: String = "",
    val episodeId: String? = null,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    val lastUpdate: Long = 0,
)
@Serializable private data class AbsProgressUpdate(val currentTime: Double, val duration: Double, val progress: Double, val isFinished: Boolean)

@Serializable private data class AbsLibraries(val libraries: List<AbsLibrary> = emptyList())
@Serializable private data class AbsLibrary(val id: String, val name: String = "", val mediaType: String = "book")

@Serializable private data class AbsPage<T>(val results: List<T> = emptyList(), val total: Int = 0)

/** A library item as the items list gives it (minified): enough to tell whether it changed. */
@Serializable private data class AbsListItem(val id: String, val updatedAt: Long = 0, val mediaType: String = "book")

@Serializable private data class AbsItem(
    val id: String,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val media: AbsMedia = AbsMedia(),
)
@Serializable private data class AbsMedia(
    val metadata: AbsMetadata = AbsMetadata(),
    val audioFiles: List<AbsAudioFile> = emptyList(),
    val chapters: List<AbsChapter> = emptyList(),
    val coverPath: String? = null,
)
@Serializable private data class AbsMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val authors: List<AbsNamed> = emptyList(),
    val authorName: String? = null,
    val narrators: List<String> = emptyList(),
    val publishedYear: String? = null,
    val description: String? = null,
    /** [description] without HTML (expanded items only). */
    val descriptionPlain: String? = null,
    val genres: List<String> = emptyList(),
)
@Serializable private data class AbsNamed(val id: String = "", val name: String = "")
@Serializable private data class AbsAudioFile(
    val index: Int = 0,
    val ino: String,
    val duration: Double = 0.0,
    val exclude: Boolean = false,
    val invalid: Boolean = false,
    val metadata: AbsFileMetadata = AbsFileMetadata(),
)
@Serializable private data class AbsFileMetadata(val filename: String = "", val ext: String = "")
@Serializable private data class AbsChapter(val start: Double = 0.0, val end: Double = 0.0, val title: String = "")

/** A series or collection with its books; [sequence] may sit on the book or in its series metadata. */
@Serializable private data class AbsGroup(val id: String, val name: String = "", val books: List<AbsGroupBook> = emptyList())
@Serializable private data class AbsGroupBook(
    val id: String,
    val sequence: JsonElement? = null,
    val media: AbsGroupMedia? = null,
)
@Serializable private data class AbsGroupMedia(val metadata: AbsGroupMetadata? = null)
/** In series listings the number is only in [seriesName], e.g. "The Expanse #3, Other Series #1". */
@Serializable private data class AbsGroupMetadata(val series: AbsSeriesRef? = null, val seriesName: String? = null)
@Serializable private data class AbsSeriesRef(val sequence: JsonElement? = null)

data class AbsLogin(val token: String, val refreshToken: String, val userId: String)

/**
 * Audiobookshelf (audiobookshelf.org). Series and collections both become collections here;
 * a book's series (with its number) comes from Audiobookshelf directly.
 */
class AudiobookshelfClient {
    private fun request(url: String, token: String?) = Request.Builder().url(url)
        .header("Accept", "application/json")
        .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }

    private val jsonType = "application/json".toMediaType()

    private suspend inline fun <reified T> get(base: String, path: String, token: String): T =
        Http.json.decodeFromString(Http.send(request(base + path, token).build()))

    suspend fun login(base: String, username: String, password: String): AbsLogin {
        val body = Http.json.encodeToString(AbsLoginRequest(username, password)).toRequestBody(jsonType)
        // Asks 2.26+ to put the refresh token in the reply (it's a cookie otherwise).
        val res: AbsLoginResponse = Http.json.decodeFromString(
            Http.send(request("$base/login", null).header("x-return-tokens", "true").post(body).build())
        )
        val token = res.user.accessToken?.ifBlank { null } ?: res.user.token?.ifBlank { null } ?: throw IllegalStateException("Audiobookshelf didn't return a token")
        return AbsLogin(token, res.user.refreshToken.orEmpty(), res.user.id)
    }

    /** Swaps a refresh token for a fresh access token (and a new refresh token). */
    suspend fun refresh(base: String, refreshToken: String): AbsLogin {
        val res: AbsLoginResponse = Http.json.decodeFromString(
            Http.send(
                request("$base/auth/refresh", null).header("x-refresh-token", refreshToken).header("x-return-tokens", "true")
                    .post(ByteArray(0).toRequestBody()).build()
            )
        )
        val token = res.user.accessToken?.ifBlank { null } ?: res.user.token?.ifBlank { null } ?: throw IllegalStateException("No token in refresh reply")
        return AbsLogin(token, res.user.refreshToken ?: refreshToken, res.user.id)
    }

    suspend fun libraries(base: String, token: String): List<RemoteLibrary> =
        get<AbsLibraries>(base, "/api/libraries", token).libraries.filter { it.mediaType == "book" }.map { RemoteLibrary(it.id, it.name) }

    /**
     * Lists the library, then fetches full details only for books that changed since last time
     * ([known] maps item ids to the signature stored then): an edit on the server or new progress.
     */
    suspend fun books(base: String, token: String, libraryId: String, known: Map<String, String?>): RemoteCatalog = coroutineScope {
        val progressJob = async { get<AbsUser>(base, "/api/me", token).mediaProgress.filter { it.episodeId == null }.associateBy { it.libraryItemId } }
        val seriesJob = async { runCatching { groups(base, token, "/api/libraries/$libraryId/series") }.getOrDefault(emptyList()) }
        val collectionsJob = async { runCatching { groups(base, token, "/api/libraries/$libraryId/collections") }.getOrDefault(emptyList()) }

        val listed = mutableListOf<AbsListItem>()
        var page = 0
        while (true) {
            val res = get<AbsPage<AbsListItem>>(base, "/api/libraries/$libraryId/items?limit=500&page=$page&minified=1", token)
            listed += res.results
            if (res.results.isEmpty() || listed.size >= res.total) break
            page++
        }
        val progress = progressJob.await()
        val series = seriesJob.await()

        // A book's series is its first series in Audiobookshelf, with the number given there.
        val seriesOf = mutableMapOf<String, Pair<String, Float>>()
        series.forEach { s ->
            s.books.forEachIndexed { i, b -> seriesOf.putIfAbsent(b.id, s.name to (b.sequenceNumber(s.name) ?: (i + 1).toFloat())) }
        }

        val limit = Semaphore(6)
        val books = listed.filter { it.mediaType == "book" }.map { entry ->
            async {
                val p = progress[entry.id]
                val entrySeries = seriesOf[entry.id]
                val signature = "${entry.updatedAt}:${p?.lastUpdate ?: 0}:${entrySeries?.first}:${entrySeries?.second}"
                if (known[entry.id] == signature) {
                    return@async RemoteBook(entry.id, "", "", "", tracks = emptyList(), chapters = null, signature = signature, unchanged = true)
                }
                val item = limit.withPermit { runCatching { get<AbsItem>(base, "/api/items/${entry.id}?expanded=1", token) }.getOrNull() }
                    ?: return@async null
                toBook(base, token, item, p, entrySeries, signature)
            }
        }.awaitAll().filterNotNull()

        // Audiobookshelf already lists a series' books in order.
        val collections = series.map { s -> RemoteCollection("s:${s.id}", s.name, s.books.map { it.id }) } +
            collectionsJob.await().map { c -> RemoteCollection("c:${c.id}", c.name, c.books.map { it.id }) }
        RemoteCatalog(books, collections.filter { it.bookRemoteIds.isNotEmpty() })
    }

    private suspend fun groups(base: String, token: String, path: String): List<AbsGroup> {
        val all = mutableListOf<AbsGroup>()
        var page = 0
        while (true) {
            val res = get<AbsPage<AbsGroup>>(base, "$path?limit=200&page=$page", token)
            all += res.results
            if (res.results.isEmpty() || all.size >= res.total) break
            page++
        }
        return all
    }

    private fun AbsGroupBook.sequenceNumber(seriesName: String): Float? {
        (sequence ?: media?.metadata?.series?.sequence)?.let { (it as? JsonPrimitive)?.floatOrNull ?: (it as? JsonPrimitive)?.content?.toFloatOrNull() }?.let { return it }
        val names = media?.metadata?.seriesName ?: return null
        return names.split(", ").firstNotNullOfOrNull { part ->
            part.takeIf { it.startsWith("$seriesName #") }?.substringAfterLast('#')?.trim()?.toFloatOrNull()
        }
    }

    private fun toBook(base: String, token: String, item: AbsItem, p: AbsProgress?, series: Pair<String, Float>?, signature: String): RemoteBook {
        val m = item.media.metadata
        val files = item.media.audioFiles.filter { !it.exclude && !it.invalid }.sortedBy { it.index }
        val tracks = files.map { f ->
            RemoteTrack(
                remoteId = f.ino,
                title = f.metadata.filename.substringBeforeLast('.').ifBlank { "Part ${f.index}" },
                url = "$base/api/items/${item.id}/file/${f.ino}?token=$token",
                durationMs = (f.duration * 1000).toLong(),
                container = f.metadata.ext.trimStart('.').ifBlank { null },
            )
        }
        val title = m.title?.takeIf { it.isNotBlank() } ?: "Untitled"
        return RemoteBook(
            remoteId = item.id,
            title = title,
            sortTitle = title.replace(Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE), ""),
            author = m.authors.joinToString(", ") { it.name }.ifBlank { m.authorName.orEmpty() }.ifBlank { "Unknown author" },
            narrator = m.narrators.joinToString(", ").ifBlank { null },
            year = m.publishedYear?.take(4)?.toIntOrNull(),
            description = m.descriptionPlain ?: m.description,
            coverUrl = if (item.media.coverPath != null) "$base/api/items/${item.id}/cover?format=jpeg&width=800&token=$token" else null,
            addedAt = item.addedAt,
            tracks = tracks,
            chapters = item.media.chapters.map { RemoteChapter(it.title.ifBlank { "Chapter" }, (it.start * 1000).toLong(), (it.end * 1000).toLong()) },
            seriesName = series?.first,
            seriesIndex = series?.second,
            serverProgress = p?.let {
                ServerProgress(positionMs = (it.currentTime * 1000).toLong(), updatedAt = it.lastUpdate, finished = it.isFinished)
            } ?: ServerProgress(),
            signature = signature,
            genres = m.genres,
        )
    }

    /** Saves your place in a book (whole-book time). */
    suspend fun reportProgress(base: String, token: String, itemId: String, positionMs: Long, durationMs: Long, finished: Boolean = false) {
        val duration = durationMs / 1000.0
        val current = positionMs / 1000.0
        val body = Http.json.encodeToString(
            AbsProgressUpdate(current, duration, if (finished) 1.0 else if (duration > 0) (current / duration).coerceIn(0.0, 1.0) else 0.0, finished)
        ).toRequestBody(jsonType)
        Http.send(request("$base/api/me/progress/$itemId", token).patch(body).build())
    }

    suspend fun markPlayed(base: String, token: String, itemId: String, durationMs: Long) =
        reportProgress(base, token, itemId, durationMs, durationMs, finished = true)

    suspend fun markUnplayed(base: String, token: String, itemId: String, durationMs: Long) =
        reportProgress(base, token, itemId, 0, durationMs, finished = false)

    suspend fun ping(base: String): Boolean = runCatching {
        Http.send(request("$base/ping", null).build(), Http.probeClient); true
    }.getOrDefault(false)

    suspend fun userName(base: String, token: String): String? =
        get<AbsUser>(base, "/api/me", token).username.takeIf { it.isNotBlank() }?.let(::firstName)
}
