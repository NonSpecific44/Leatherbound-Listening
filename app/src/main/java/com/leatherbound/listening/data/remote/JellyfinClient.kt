package com.leatherbound.listening.data.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

@Serializable private data class JfAuthRequest(@SerialName("Username") val username: String, @SerialName("Pw") val password: String)
@Serializable private data class JfAuthResponse(@SerialName("AccessToken") val accessToken: String, @SerialName("User") val user: JfUser)
@Serializable private data class JfUser(@SerialName("Id") val id: String)
@Serializable private data class JfItems(@SerialName("Items") val items: List<JfItem> = emptyList())

@Serializable private data class JfItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("SortName") val sortName: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long = 0,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("ParentId") val parentId: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @SerialName("Chapters") val chapters: List<JfChapter> = emptyList(),
    @SerialName("People") val people: List<JfPerson> = emptyList(),
    @SerialName("UserData") val userData: JfUserData? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
)

@Serializable private data class JfNamed(@SerialName("Name") val name: String = "")

@Serializable private data class JfUserData(
    @SerialName("PlaybackPositionTicks") val positionTicks: Long = 0,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
)

@Serializable private data class JfProgressReport(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean,
    @SerialName("CanSeek") val canSeek: Boolean = true,
)

@Serializable private data class JfChapter(@SerialName("StartPositionTicks") val startTicks: Long = 0, @SerialName("Name") val name: String? = null)
@Serializable private data class JfPerson(@SerialName("Name") val name: String = "", @SerialName("Type") val type: String? = null, @SerialName("Role") val role: String? = null)

data class JellyfinLogin(val token: String, val userId: String)

/**
 * Audiobook files ("AudioBook" items in a Books library, or "Audio" in a Music library) are grouped
 * into books by album, so a folder of MP3s and a single M4B both become one book.
 */
class JellyfinClient(private val deviceId: String) {

    private fun authHeader(token: String?) = buildString {
        append("MediaBrowser Client=\"Leatherbound Listening\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"1.0.0\"")
        if (!token.isNullOrBlank()) append(", Token=\"$token\"")
    }

    private fun request(url: String, token: String?) = Request.Builder().url(url)
        .header("Accept", "application/json")
        .header("Authorization", authHeader(token))

    suspend fun login(base: String, username: String, password: String): JellyfinLogin {
        val body = Http.json.encodeToString(JfAuthRequest(username, password)).toRequestBody("application/json".toMediaType())
        val res: JfAuthResponse = Http.json.decodeFromString(Http.send(request("$base/Users/AuthenticateByName", null).post(body).build()))
        return JellyfinLogin(res.accessToken, res.user.id)
    }

    private suspend fun items(base: String, token: String, path: String): List<JfItem> =
        Http.json.decodeFromString<JfItems>(Http.send(request(base + path, token).build())).items

    suspend fun libraries(base: String, token: String, userId: String): List<RemoteLibrary> =
        items(base, token, "/Users/$userId/Views")
            .filter { it.collectionType in setOf("books", "music", null) }
            .map { RemoteLibrary(it.id, it.name) }

    suspend fun books(base: String, token: String, userId: String, libraryId: String): RemoteCatalog = coroutineScope {
        val setsJob = async { runCatching { boxSets(base, token, userId) }.getOrDefault(emptyList()) }
        val files = items(
            base, token,
            "/Users/$userId/Items?ParentId=$libraryId&Recursive=true&IncludeItemTypes=AudioBook,Audio" +
                "&Fields=Overview,DateCreated,Chapters,People,SortName,ParentId,ProductionYear,Genres&EnableImageTypes=Primary&EnableUserData=true",
        )
        val groups = files.groupBy { it.albumId ?: it.album?.let { album -> "${it.parentId}|$album" } ?: it.id }
        // A BoxSet can list a book by its album or by one of its files; map both to the book.
        val bookOf = buildMap {
            groups.forEach { (key, parts) -> put(key, key); parts.forEach { put(it.id, key) } }
        }
        val collections = setsJob.await().map { set ->
            RemoteCollection(
                set.id, set.name, set.children.mapNotNull { bookOf[it] }.distinct(),
                coverUrl = if (set.hasImage) "$base/Items/${set.id}/Images/Primary?maxHeight=600&api_key=$token" else null,
            )
        }.filter { it.bookRemoteIds.isNotEmpty() }
        val series = seriesIndex(collections)

        val books = groups
            .map { (groupKey, group) ->
                val parts = group.sortedWith(compareBy({ it.parentIndexNumber ?: 0 }, { it.indexNumber ?: 0 }, { it.sortName ?: it.name }))
                val first = parts.first()
                val tracks = parts.map {
                    RemoteTrack(it.id, it.name, "$base/Audio/${it.id}/stream?static=true&api_key=$token", it.runTimeTicks / 10_000, it.container)
                }
                var offset = 0L
                val chapters = parts.flatMap { part ->
                    val ticksOffset = offset
                    offset += part.runTimeTicks / 10_000
                    part.chapters.mapIndexed { i, c ->
                        val start = ticksOffset + c.startTicks / 10_000
                        val end = part.chapters.getOrNull(i + 1)?.let { ticksOffset + it.startTicks / 10_000 } ?: offset
                        RemoteChapter(c.name ?: "Chapter ${i + 1}", start, end)
                    }
                }
                val title = if (parts.size > 1) first.album ?: first.name else first.album?.takeIf { it.isNotBlank() } ?: first.name
                val cover = when {
                    first.albumId != null && first.albumPrimaryImageTag != null -> "$base/Items/${first.albumId}/Images/Primary?maxHeight=800&api_key=$token"
                    "Primary" in first.imageTags -> "$base/Items/${first.id}/Images/Primary?maxHeight=800&api_key=$token"
                    else -> null
                }
                val seriesEntry = series[groupKey]
                RemoteBook(
                    remoteId = groupKey,
                    title = title,
                    sortTitle = if (parts.size > 1) title else first.sortName ?: title,
                    author = first.albumArtist ?: first.people.firstOrNull { it.type == "Author" }?.name ?: first.artists.firstOrNull() ?: "Unknown author",
                    narrator = first.people.firstOrNull { it.type == "Composer" || it.role == "Narrator" }?.name,
                    year = first.year,
                    description = first.overview,
                    coverUrl = cover,
                    addedAt = first.dateCreated?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0,
                    tracks = tracks,
                    chapters = chapters,
                    seriesName = seriesEntry?.first,
                    seriesIndex = seriesEntry?.second,
                    serverProgress = bookProgress(parts.map { part ->
                        val data = part.userData
                        TrackProgress(
                            durationMs = part.runTimeTicks / 10_000,
                            positionMs = data?.positionTicks?.div(10_000),
                            played = data?.played == true,
                            lastPlayedAt = data?.lastPlayedDate?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0,
                        )
                    }),
                    genres = first.genres,
                )
            }
        RemoteCatalog(books, collections)
    }

    private data class BoxSet(val id: String, val name: String, val children: List<String>, val hasImage: Boolean)

    /** Every BoxSet with its child item ids in order. */
    private suspend fun boxSets(base: String, token: String, userId: String): List<BoxSet> = coroutineScope {
        items(base, token, "/Users/$userId/Items?IncludeItemTypes=BoxSet&Recursive=true").map { set ->
            async {
                val children = items(base, token, "/Users/$userId/Items?ParentId=${set.id}&SortBy=ProductionYear,SortName").map { it.id }
                BoxSet(set.id, set.name, children, "Primary" in set.imageTags)
            }
        }.awaitAll()
    }

    /** Clears played state and position for one file. */
    suspend fun markUnplayed(base: String, token: String, userId: String, itemId: String) {
        Http.send(request("$base/Users/$userId/PlayedItems/$itemId", token).delete().build())
        reportProgress(base, token, itemId, 0, playing = false)
    }

    /** True if the server answers at all. */
    suspend fun ping(base: String): Boolean = runCatching {
        Http.send(request("$base/System/Info/Public", null).build(), Http.probeClient); true
    }.getOrDefault(false)

    /** Reports your place in one file. Pausing sends "stopped", which Jellyfin always persists. */
    suspend fun reportProgress(base: String, token: String, itemId: String, positionMs: Long, playing: Boolean) {
        val body = Http.json.encodeToString(JfProgressReport(itemId, positionMs * 10_000, isPaused = !playing))
            .toRequestBody("application/json".toMediaType())
        val path = if (playing) "/Sessions/Playing/Progress" else "/Sessions/Playing/Stopped"
        Http.send(request(base + path, token).post(body).build())
    }

    suspend fun userName(base: String, token: String, userId: String): String? {
        val body = Http.send(request("$base/Users/$userId", token).build())
        return Http.json.decodeFromString<JfNamed>(body).name.takeIf { it.isNotBlank() }?.let(::firstName)
    }

    suspend fun markPlayed(base: String, token: String, userId: String, itemId: String) {
        Http.send(request("$base/Users/$userId/PlayedItems/$itemId", token).post(ByteArray(0).toRequestBody()).build())
    }
}
