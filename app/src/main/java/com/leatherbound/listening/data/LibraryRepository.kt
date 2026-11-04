package com.leatherbound.listening.data

import android.content.Context
import android.net.Uri
import com.leatherbound.listening.data.db.AppDatabase
import com.leatherbound.listening.data.db.BookEntity
import com.leatherbound.listening.data.db.BookmarkEntity
import com.leatherbound.listening.data.db.ChapterEntity
import com.leatherbound.listening.data.db.CollectionBookEntity
import com.leatherbound.listening.data.db.CollectionEntity
import com.leatherbound.listening.data.db.DownloadState
import com.leatherbound.listening.data.db.PendingKind
import com.leatherbound.listening.data.db.PendingSyncEntity
import com.leatherbound.listening.data.db.SourceEntity
import com.leatherbound.listening.data.db.SourceType
import com.leatherbound.listening.data.db.TrackEntity
import com.leatherbound.listening.data.local.LocalScanner
import com.leatherbound.listening.data.remote.AudiobookshelfClient
import com.leatherbound.listening.data.remote.JellyfinClient
import com.leatherbound.listening.data.remote.PlexClient
import com.leatherbound.listening.data.remote.RemoteBook
import com.leatherbound.listening.data.remote.RemoteCatalog
import com.leatherbound.listening.data.remote.RemoteCollection
import com.leatherbound.listening.data.remote.fallbackChapters
import com.leatherbound.listening.playback.locate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

enum class ServerStatus { CHECKING, ONLINE, OFFLINE, ON_DEVICE }

class LibraryRepository(
    private val context: Context,
    db: AppDatabase,
    private val settings: SettingsStore,
) {
    val settingsStore get() = settings
    val dao = db.dao()
    private val scanner = LocalScanner(context)

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _serverStatus = MutableStateFlow<Map<Long, ServerStatus>>(emptyMap())
    /** Whether each library's server answers, for the home screen. */
    val serverStatus: StateFlow<Map<Long, ServerStatus>> = _serverStatus

    suspend fun plex() = PlexClient(settings.deviceId())
    suspend fun jellyfin() = JellyfinClient(settings.deviceId())
    fun abs() = AudiobookshelfClient()

    /** The source's access token, decrypted. Empty if it can't be (sign in again). */
    fun SourceEntity.secret(): String = TokenVault.decrypt(token)

    suspend fun tokenFor(sourceId: Long): String = dao.source(sourceId)?.secret().orEmpty()

    // ---------------- Sources ----------------

    suspend fun addSource(source: SourceEntity): Long {
        val id = dao.insertSource(source.copy(token = TokenVault.encrypt(source.token), refreshToken = TokenVault.encrypt(source.refreshToken)))
        dao.source(id)?.let { runCatching { sync(it) }.onFailure { _lastError.value = it.message } }
        return id
    }

    suspend fun addLocalFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = treeUri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "Local folder"
        addSource(SourceEntity(type = SourceType.LOCAL, name = "Device · $name", baseUrl = treeUri.toString()))
    }

    suspend fun removeSource(source: SourceEntity) {
        dao.booksForSource(source.id).forEach { Downloads.deleteFiles(context, it.id) }
        dao.deleteBookmarksForSource(source.id)
        dao.replaceCollections(source.id, emptyList(), emptyList())
        dao.deleteBooksForSource(source.id)
        dao.deleteSource(source.id)
        if (source.type == SourceType.LOCAL) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(source.baseUrl), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** Encrypts tokens saved by older versions of the app. */
    private suspend fun protectTokens() {
        dao.sources().filter { it.token.isNotEmpty() && !TokenVault.isEncrypted(it.token) }.forEach {
            dao.updateToken(it.id, TokenVault.encrypt(it.token))
        }
    }

    // ---------------- Sync ----------------

    /**
     * Re-picks the fastest address for each Plex server, since the best route changes when you
     * leave or come home. Stored stream and cover URLs are moved over to the new address.
     */
    suspend fun refreshConnections() = coroutineScope {
        refreshTokens()
        dao.sources().filter { it.type == SourceType.PLEX }.map { source ->
            async {
                runCatching {
                    val plex = plex()
                    val token = source.secret()
                    val candidates = source.connections.lines().filter { it.isNotBlank() }
                        .ifEmpty { plex.connectionsFor(token, source.baseUrl) }
                        .ifEmpty { listOf(source.baseUrl) }
                    val best = plex.fastest(candidates, token) ?: return@runCatching
                    val joined = candidates.joinToString("\n")
                    if (best != source.baseUrl) dao.moveSource(source.id, source.baseUrl, best, joined)
                    else if (joined != source.connections) dao.updateConnection(source.id, best, joined)
                }
            }
        }.awaitAll()
    }

    /**
     * Audiobookshelf 2.26+ access tokens expire after a while; swap the refresh token for new ones.
     * Older servers' tokens don't expire and have no refresh token, so they're left alone.
     */
    private suspend fun refreshTokens() {
        dao.sources().filter { it.type == SourceType.AUDIOBOOKSHELF && it.refreshToken.isNotEmpty() }.forEach { source ->
            runCatching {
                val fresh = abs().refresh(source.baseUrl, TokenVault.decrypt(source.refreshToken))
                dao.updateTokens(source.id, TokenVault.encrypt(fresh.token), TokenVault.encrypt(fresh.refreshToken))
            }
        }
    }

    /**
     * Refreshes every source; without [force], only those not synced in the last few minutes.
     * Runs on every launch so progress made on other devices shows up.
     */
    suspend fun syncAll(force: Boolean = false) {
        if (_syncing.value) return
        _syncing.value = true
        _lastError.value = null
        try {
            protectTokens()
            refreshConnections()
            // Send what was saved while offline before reading the servers back.
            flushPending()
            refreshAccountName()
            val now = System.currentTimeMillis()
            coroutineScope {
                dao.sources().filter { force || now - it.lastSyncedAt > 5 * 60_000 }.map { source ->
                    async { runCatching { sync(source) }.onFailure { _lastError.value = "${source.name}: ${it.message}" } }
                }.awaitAll()
            }
        } finally {
            _syncing.value = false
        }
        cacheCollectionCovers()
    }

    /** Saves collection covers ahead of time, so they show at once and offline. Already-saved ones cost nothing. */
    private suspend fun cacheCollectionCovers() = withContext(Dispatchers.IO) {
        val limit = Semaphore(4)
        dao.collectionsWithCovers().mapNotNull { ArtworkProvider.uri(it) }.map { uri ->
            async { limit.withPermit { runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.close() } } }
        }.awaitAll()
    }

    /** Looks up your name for the home-screen greeting: Plex first, then Jellyfin. */
    suspend fun refreshAccountName() {
        val sources = dao.sources()
        val name = sources.filter { it.type == SourceType.PLEX }.firstNotNullOfOrNull { s ->
            runCatching { plex().accountName(s.secret()) }.getOrNull()
        } ?: sources.filter { it.type == SourceType.JELLYFIN }.firstNotNullOfOrNull { s ->
            runCatching { jellyfin().userName(s.baseUrl, s.secret(), s.userId) }.getOrNull()
        } ?: sources.filter { it.type == SourceType.AUDIOBOOKSHELF }.firstNotNullOfOrNull { s ->
            runCatching { abs().userName(s.baseUrl, s.secret()) }.getOrNull()
        }
        if (name != null) settings.setAccountName(name)
    }

    private suspend fun sync(source: SourceEntity) {
        val token = source.secret()
        if (source.type != SourceType.LOCAL && token.isEmpty()) {
            throw IllegalStateException("Sign-in has expired. Remove this library and add it again.")
        }
        val existing = dao.booksForSource(source.id).associateBy { it.id }
        val catalog: RemoteCatalog = when (source.type) {
            SourceType.PLEX -> plex().books(source.baseUrl, token, source.libraryId)
            SourceType.JELLYFIN -> jellyfin().books(source.baseUrl, token, source.userId, source.libraryId)
            SourceType.AUDIOBOOKSHELF -> abs().books(source.baseUrl, token, source.libraryId, existing.values.associate { it.remoteId to it.scanSignature })
            SourceType.LOCAL -> RemoteCatalog(scanner.scan(Uri.parse(source.baseUrl), existing.values.associate { it.remoteId to it.scanSignature }))
        }
        val remote = catalog.books
        val seen = mutableSetOf<String>()
        val changed = remote.mapNotNull { r ->
            val id = bookId(source, r.remoteId)
            seen += id
            val old = existing[id]
            if (r.unchanged && old != null) return@mapNotNull null
            val book = BookEntity(
                id = id, sourceId = source.id, remoteId = r.remoteId,
                title = r.title, sortTitle = r.sortTitle, author = r.author, narrator = r.narrator,
                year = r.year, description = r.description, coverUrl = r.coverUrl?.let { TokenVault.strip(it, token) },
                durationMs = r.tracks.sumOf { it.durationMs },
                addedAt = r.addedAt.takeIf { it > 0 } ?: old?.addedAt ?: System.currentTimeMillis(),
                seriesName = r.seriesName, seriesIndex = r.seriesIndex,
                detailsLoaded = r.chapters != null || old?.detailsLoaded == true,
                scanSignature = r.signature,
                genres = r.genres.distinct().joinToString(", ").ifEmpty { null },
            )
            Triple(mergeState(book, old, r), r, old)
        }
        dao.upsertBooks(changed.map { it.first })
        for ((book, r, old) in changed) {
            var offset = 0L
            dao.replaceTracks(book.id, r.tracks.mapIndexed { i, t ->
                TrackEntity(book.id, i, t.title, t.remoteId, TokenVault.strip(t.url, token), t.durationMs, offset, t.container)
                    .also { offset += t.durationMs }
            })
            // Keep lazily fetched chapters unless the server sent fresh ones.
            if (r.chapters != null || old == null || !old.detailsLoaded) {
                val chapters = r.chapters?.takeIf { it.isNotEmpty() } ?: r.fallbackChapters()
                dao.replaceChapters(book.id, chapters.mapIndexed { i, c -> ChapterEntity(book.id, i, c.title, c.startMs, c.endMs) })
            }
        }
        val gone = existing.keys - seen
        gone.forEach { Downloads.deleteFiles(context, it) }
        if (gone.isNotEmpty()) dao.deleteBooks(gone.toList())
        saveCollections(source, catalog)
        dao.markSynced(source.id, System.currentTimeMillis())
    }

    /** Server collections as they are; for folders, each series folder becomes a collection. */
    private suspend fun saveCollections(source: SourceEntity, catalog: RemoteCatalog) {
        val books = dao.booksForSource(source.id)
        val known = books.map { it.id }.toSet()
        val token = source.secret()
        val groups: List<RemoteCollection> = if (source.type == SourceType.LOCAL) {
            books.filter { it.seriesName != null }.groupBy { it.seriesName!! }.map { (name, list) ->
                RemoteCollection(LocalScanner.sha1(name), name, list.sortedWith(compareBy({ it.seriesIndex ?: 0f }, { it.sortTitle })).map { it.id })
            }
        } else {
            catalog.collections.map { c -> c.copy(bookRemoteIds = c.bookRemoteIds.map { bookId(source, it) }) }
        }
        val collections = groups.map { c ->
            CollectionEntity("${source.id}:${c.remoteId}", source.id, c.remoteId, c.name, c.coverUrl?.let { TokenVault.strip(it, token) })
        }
        val links = groups.flatMap { c ->
            c.bookRemoteIds.filter { it in known }.distinct().mapIndexed { i, id -> CollectionBookEntity("${source.id}:${c.remoteId}", id, i) }
        }
        val nonEmpty = links.map { it.collectionId }.toSet()
        dao.replaceCollections(source.id, collections.filter { it.id in nonEmpty }, links)
    }

    /** Pings every library's server in parallel. */
    suspend fun checkServers() = coroutineScope {
        val sources = dao.sources()
        _serverStatus.value = sources.associate { it.id to if (it.type == SourceType.LOCAL) ServerStatus.ON_DEVICE else _serverStatus.value[it.id] ?: ServerStatus.CHECKING }
        sources.filter { it.type != SourceType.LOCAL }.map { source ->
            async {
                val up = when (source.type) {
                    SourceType.PLEX -> plex().ping(source.baseUrl, source.secret())
                    SourceType.AUDIOBOOKSHELF -> abs().ping(source.baseUrl)
                    else -> jellyfin().ping(source.baseUrl)
                }
                _serverStatus.value = _serverStatus.value + (source.id to if (up) ServerStatus.ONLINE else ServerStatus.OFFLINE)
            }
        }.awaitAll()
    }

    /**
     * Keeps listener state across syncs. Where the server has a newer position (you listened on
     * another device), that wins; otherwise the local position does.
     */
    private fun mergeState(book: BookEntity, old: BookEntity?, r: RemoteBook): BookEntity {
        val local = old?.let {
            book.copy(
                positionMs = it.positionMs, lastPlayedAt = it.lastPlayedAt, isFinished = it.isFinished,
                finishedAt = it.finishedAt, listPosition = it.listPosition,
                downloadState = it.downloadState, downloadProgress = it.downloadProgress,
                playbackSpeed = it.playbackSpeed,
            )
        } ?: book
        val server = r.serverProgress
        val serverIsNewer = server.updatedAt > local.lastPlayedAt + 60_000
        return when {
            server.finished && !local.isFinished && serverIsNewer ->
                local.copy(isFinished = true, finishedAt = server.updatedAt, positionMs = 0, lastPlayedAt = server.updatedAt)
            serverIsNewer && server.positionMs != null ->
                local.copy(positionMs = server.positionMs, lastPlayedAt = server.updatedAt, isFinished = false)
            else -> local
        }
    }

    private fun bookId(source: SourceEntity, remoteId: String) =
        if (source.type == SourceType.LOCAL) "${source.id}:${LocalScanner.sha1(remoteId)}" else "${source.id}:$remoteId"

    /** Plex only reveals embedded chapters per track, so fetch them the first time a book is opened. */
    suspend fun ensureDetails(bookId: String) {
        val book = dao.book(bookId) ?: return
        if (book.detailsLoaded) return
        val source = dao.source(book.sourceId) ?: return
        if (source.type != SourceType.PLEX) return
        val tracks = dao.tracks(bookId)
        runCatching {
            if (tracks.size <= 5) {
                val chapters = plex().chapters(source.baseUrl, source.secret(), tracks.map { it.remoteId to it.startOffsetMs })
                if (chapters.size > tracks.size) {
                    dao.replaceChapters(bookId, chapters.mapIndexed { i, c -> ChapterEntity(bookId, i, c.title, c.startMs, c.endMs) })
                }
            }
            dao.markDetailsLoaded(bookId)
        }
    }

    // ---------------- Listener state ----------------

    suspend fun savePosition(bookId: String, positionMs: Long) =
        dao.updatePosition(bookId, positionMs, System.currentTimeMillis())

    private val lastPush = ConcurrentHashMap<String, Long>()

    /**
     * Sends your place to the server. While playing it's sent at most once a minute;
     * pauses ([force]) are always sent. If it can't be sent (offline), it's queued for later.
     */
    suspend fun pushProgress(bookId: String, positionMs: Long, playing: Boolean, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - (lastPush[bookId] ?: 0) < 60_000) return
        lastPush[bookId] = now
        val book = dao.book(bookId) ?: return
        val source = dao.source(book.sourceId) ?: return
        if (source.type == SourceType.LOCAL) return
        if (sendProgress(book, source, positionMs, playing)) dao.clearPending(bookId, now, PendingKind.PROGRESS)
        else queue(bookId, PendingKind.PROGRESS)
    }

    private suspend fun sendProgress(book: BookEntity, source: SourceEntity, positionMs: Long, playing: Boolean): Boolean = runCatching {
        when (source.type) {
            SourceType.PLEX, SourceType.JELLYFIN -> {
                val tracks = dao.tracks(book.id)
                val (index, offset) = tracks.locate(positionMs)
                val track = tracks.getOrNull(index) ?: return true
                if (source.type == SourceType.PLEX) plex().reportProgress(source.baseUrl, source.secret(), track.remoteId, offset, track.durationMs, playing)
                else jellyfin().reportProgress(source.baseUrl, source.secret(), track.remoteId, offset, playing)
            }
            SourceType.AUDIOBOOKSHELF -> abs().reportProgress(source.baseUrl, source.secret(), book.remoteId, positionMs, book.durationMs)
            SourceType.LOCAL -> Unit
        }
    }.isSuccess

    private suspend fun sendFinished(book: BookEntity, source: SourceEntity): Boolean = runCatching {
        when (source.type) {
            SourceType.PLEX -> plex().markPlayed(source.baseUrl, source.secret(), book.remoteId)
            SourceType.JELLYFIN -> dao.tracks(book.id).forEach {
                jellyfin().markPlayed(source.baseUrl, source.secret(), source.userId, it.remoteId)
            }
            SourceType.AUDIOBOOKSHELF -> abs().markPlayed(source.baseUrl, source.secret(), book.remoteId, book.durationMs)
            SourceType.LOCAL -> Unit
        }
    }.isSuccess

    private suspend fun sendUnread(book: BookEntity, source: SourceEntity): Boolean = runCatching {
        when (source.type) {
            SourceType.PLEX -> plex().markUnplayed(source.baseUrl, source.secret(), book.remoteId)
            SourceType.JELLYFIN -> dao.tracks(book.id).forEach {
                jellyfin().markUnplayed(source.baseUrl, source.secret(), source.userId, it.remoteId)
            }
            SourceType.AUDIOBOOKSHELF -> abs().markUnplayed(source.baseUrl, source.secret(), book.remoteId, book.durationMs)
            SourceType.LOCAL -> Unit
        }
    }.isSuccess

    /** Remembers a change to send later. A position doesn't replace a waiting "finished" or "unread". */
    private suspend fun queue(bookId: String, kind: PendingKind) {
        val waiting = dao.pendingFor(bookId)
        if (kind == PendingKind.PROGRESS && waiting != null && waiting.kind != PendingKind.PROGRESS) return
        dao.upsertPending(PendingSyncEntity(bookId, kind, System.currentTimeMillis()))
    }

    /** Changes waiting to reach a server, for Settings. */
    val pendingCount = dao.observePendingCount()

    private val flushing = kotlinx.coroutines.sync.Mutex()

    /**
     * Sends everything saved while offline: positions, finished and unread. Runs when the phone
     * gets a connection and before every sync. Whatever still fails stays queued.
     */
    suspend fun flushPending() {
        if (!flushing.tryLock()) return
        try {
            for (p in dao.pending()) {
                val book = dao.book(p.bookId)
                val source = book?.let { dao.source(it.sourceId) }
                if (book == null || source == null || source.type == SourceType.LOCAL) { dao.clearPending(p.bookId, p.createdAt); continue }
                val sent = when (p.kind) {
                    PendingKind.PROGRESS -> sendProgress(book, source, book.positionMs, playing = false)
                    PendingKind.FINISHED -> sendFinished(book, source)
                    PendingKind.UNREAD -> sendUnread(book, source)
                }
                if (sent) dao.clearPending(p.bookId, p.createdAt)
            }
        } finally {
            flushing.unlock()
        }
    }

    suspend fun recordListening(bookId: String, ms: Long) {
        if (ms > 0) dao.addListening(LocalDate.now().toEpochDay(), bookId, ms)
    }

    suspend fun setFinished(bookId: String, finished: Boolean) {
        val book = dao.book(bookId) ?: return
        dao.setFinished(bookId, finished, if (finished) System.currentTimeMillis() else 0)
        if (finished) {
            dao.updatePosition(bookId, 0, System.currentTimeMillis())
            val s = settings.current()
            if (s.removeFinishedFromList) dao.setListPosition(bookId, null)
            if (s.deleteDownloadWhenFinished && book.downloadState != DownloadState.NONE) Downloads.remove(context, bookId)
            val source = dao.source(book.sourceId) ?: return
            if (source.type != SourceType.LOCAL && !sendFinished(book, source)) queue(bookId, PendingKind.FINISHED)
        }
    }

    /** Back to "not started": clears position, finished state and history, here and on the server. */
    suspend fun markUnread(bookId: String) {
        val book = dao.book(bookId) ?: return
        dao.resetProgress(bookId)
        lastPush.remove(bookId)
        val source = dao.source(book.sourceId) ?: return
        if (source.type != SourceType.LOCAL && !sendUnread(book, source)) queue(bookId, PendingKind.UNREAD)
    }

    suspend fun setBookSpeed(bookId: String, speed: Float) = dao.setBookSpeed(bookId, speed)

    suspend fun addBookmark(bookId: String, positionMs: Long, note: String) =
        dao.insertBookmark(BookmarkEntity(bookId = bookId, positionMs = positionMs, note = note.trim(), createdAt = System.currentTimeMillis()))

    suspend fun deleteBookmark(id: Long) = dao.deleteBookmark(id)

    suspend fun toggleListeningList(bookId: String) {
        val book = dao.book(bookId) ?: return
        if (book.listPosition != null) dao.setListPosition(bookId, null)
        else dao.setListPosition(bookId, (dao.maxListPosition() ?: 0) + 1)
    }

    suspend fun moveInList(bookId: String, up: Boolean) {
        val list = dao.listeningList().toMutableList()
        val i = list.indexOfFirst { it.id == bookId }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        list.forEachIndexed { index, b -> dao.setListPosition(b.id, index + 1) }
    }

    /** The next book in the same series, else (only if the series has none left) the top of Up Next. */
    suspend fun nextAfter(book: BookEntity): BookEntity? =
        chooseNextBook(book, book.seriesName?.let { dao.series(it) }.orEmpty(), dao.listeningList())

    /** Saves a new Up Next order (from drag and drop). */
    suspend fun reorderList(ids: List<String>) {
        ids.forEachIndexed { i, id -> dao.setListPosition(id, i + 1) }
    }

    /**
     * Once you're 80% through a book, download the one book that comes next: the next in its series,
     * or the top of Up Next only when the series has nothing left. If that book is already
     * downloaded (or downloading), nothing else is fetched in its place.
     */
    suspend fun downloadNextIfWanted(book: BookEntity) {
        val s = settings.current()
        if (!s.autoDownloadNext) return
        val next = nextAfter(book) ?: return
        if (next.downloadState != DownloadState.NONE) return
        if (dao.source(next.sourceId)?.type == SourceType.LOCAL) return
        Downloads.enqueue(context, next.id, s.downloadOnWifiOnly)
    }
}
