package com.leatherbound.listening.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leatherbound.listening.container
import com.leatherbound.listening.data.AppSettings
import com.leatherbound.listening.data.Downloads
import com.leatherbound.listening.data.LibrarySort
import com.leatherbound.listening.data.ThemeMode
import com.leatherbound.listening.data.authorSortKey
import com.leatherbound.listening.data.groupByGenre
import com.leatherbound.listening.data.db.BookEntity
import com.leatherbound.listening.data.db.ChapterEntity
import com.leatherbound.listening.data.db.CollectionEntity
import com.leatherbound.listening.data.db.ListeningDayEntity
import com.leatherbound.listening.data.db.SourceEntity
import com.leatherbound.listening.data.db.SourceType
import com.leatherbound.listening.data.remote.PlexServer
import com.leatherbound.listening.data.remote.RemoteLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class LibraryFilter(val label: String) { ALL("All"), UNREAD("Not started"), IN_PROGRESS("In progress"), FINISHED("Finished"), DOWNLOADED("Downloaded") }

/** Steps of the "connect a server" dialogs. */
sealed interface ConnectState {
    data object Idle : ConnectState
    data class Working(val message: String) : ConnectState
    data class PickPlexServer(val servers: List<PlexServer>) : ConnectState
    data class PickLibrary(val libraries: List<RemoteLibrary>, val pending: SourceEntity) : ConnectState
    data class Failed(val message: String) : ConnectState
}

data class Stats(
    val totalMs: Long = 0,
    val weekMs: Long = 0,
    val todayMs: Long = 0,
    val dailyAverageMs: Long = 0,
    val streakDays: Int = 0,
    val finishedTotal: Int = 0,
    val finishedThisYear: Int = 0,
    val last14Days: List<Pair<LocalDate, Long>> = emptyList(),
    val topBooks: List<Pair<BookEntity, Long>> = emptyList(),
    val topAuthors: List<Pair<String, Long>> = emptyList(),
    val finishedThisYearBooks: List<BookEntity> = emptyList(),
)

/** A collection with its books in order. */
data class CollectionView(val collection: CollectionEntity, val books: List<BookEntity>)

enum class LibraryView { BOOKS, COLLECTIONS }

/** A downloaded book and the space it takes. */
data class DownloadedBook(val book: BookEntity, val bytes: Long)

/** Whether a book can play right now: online, downloaded, or on this phone. */
data class Availability(val online: Boolean = true, val localSources: Set<Long> = emptySet()) {
    fun canPlay(book: BookEntity) =
        online || book.downloadState == com.leatherbound.listening.data.db.DownloadState.DONE || book.sourceId in localSources
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val repo = c.repository
    private val dao = repo.dao
    val player = c.player

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val settings: StateFlow<AppSettings> = c.settings.settings.state(AppSettings())
    val books = dao.observeBooks().state(emptyList())
    val inProgress = dao.observeInProgress().state(emptyList())
    val listeningList = dao.observeListeningList().state(emptyList())
    val sources = dao.observeSources().state(emptyList())
    val syncing = repo.syncing
    val syncError = repo.lastError
    val playerState = player.state
    val availability: StateFlow<Availability> = combine(c.network.online, sources) { online, srcs ->
        Availability(online, srcs.filter { it.type == SourceType.LOCAL }.map { it.id }.toSet())
    }.state(Availability())

    val downloads: StateFlow<List<DownloadedBook>> = dao.observeDownloads().map { list ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            list.map { b -> DownloadedBook(b, Downloads.dir(getApplication(), b.id).walkTopDown().filter { it.isFile }.sumOf { it.length() }) }
        }
    }.state(emptyList())

    val query = MutableStateFlow("")
    val filter = MutableStateFlow(LibraryFilter.ALL)
    val libraryView = MutableStateFlow(LibraryView.BOOKS)

    val collections: StateFlow<List<CollectionView>> =
        combine(dao.observeCollections(), dao.observeCollectionBooks(), books) { cols, links, all ->
            val byId = all.associateBy { it.id }
            val members = links.groupBy { it.collectionId }
            cols.mapNotNull { c ->
                members[c.id].orEmpty().sortedBy { it.position }.mapNotNull { byId[it.bookId] }
                    .takeIf { it.isNotEmpty() }?.let { CollectionView(c, it) }
            }
        }.state(emptyList())

    /** Collections whose name matches the library search. */
    val matchingCollections: StateFlow<List<CollectionView>> = combine(collections, query) { cols, q ->
        if (q.isBlank()) emptyList() else cols.filter { it.collection.name.contains(q.trim(), true) }
    }.state(emptyList())

    fun collection(id: String): Flow<CollectionView?> = collections.map { list -> list.firstOrNull { it.collection.id == id } }

    val serverStatus = repo.serverStatus
    fun checkServers() = viewModelScope.launch { repo.checkServers() }

    /** Live speed and size for downloads in progress. */
    val transfers = Downloads.activeTransfers

    val library: StateFlow<List<BookEntity>> = combine(books, settings, query, filter) { all, s, q, f ->
        val filtered = all.filter { b ->
            (q.isBlank() || b.title.contains(q, true) || b.author.contains(q, true) || b.seriesName?.contains(q, true) == true ||
                b.genres?.contains(q, true) == true) &&
                when (f) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.UNREAD -> b.positionMs == 0L && !b.isFinished
                    LibraryFilter.IN_PROGRESS -> b.inProgress
                    LibraryFilter.FINISHED -> b.isFinished
                    LibraryFilter.DOWNLOADED -> b.downloadState == com.leatherbound.listening.data.db.DownloadState.DONE
                }
        }
        val sorted = when (s.librarySort) {
            LibrarySort.RECENTLY_ADDED -> filtered.sortedByDescending { it.addedAt }
            LibrarySort.RECENTLY_PLAYED -> filtered.sortedByDescending { it.lastPlayedAt }
            LibrarySort.TITLE -> filtered.sortedBy { it.sortTitle.lowercase() }
            LibrarySort.AUTHOR -> filtered.sortedWith(compareBy({ authorSortKey(it.author) }, { it.seriesName.orEmpty() }, { it.seriesIndex ?: 0f }, { it.sortTitle.lowercase() }))
            LibrarySort.YEAR -> filtered.sortedWith(compareByDescending<BookEntity> { it.year ?: 0 }.thenBy { it.sortTitle.lowercase() })
            LibrarySort.SERIES -> filtered.sortedWith(compareBy({ it.seriesName == null }, { it.seriesName.orEmpty().lowercase() }, { it.seriesIndex ?: 0f }, { it.sortTitle.lowercase() }))
            LibrarySort.LENGTH -> filtered.sortedByDescending { it.durationMs }
            // Books with no genre go last.
            LibrarySort.GENRE -> filtered.sortedWith(compareBy({ it.primaryGenre == null }, { it.primaryGenre?.lowercase() }, { authorSortKey(it.author) }, { it.sortTitle.lowercase() }))
        }
        if (s.sortDescending) sorted.reversed() else sorted
    }.state(emptyList())

    /** Every genre in the library, for the genre filter. */
    val allGenres: StateFlow<List<String>> = books.map { all -> groupByGenre(all).map { it.first } }.state(emptyList())

    /** The filtered/searched library grouped by genre, minus genres switched off in the filter. */
    val genreGroups: StateFlow<List<Pair<String, List<BookEntity>>>> = combine(library, settings) { lib, s ->
        groupByGenre(lib).filter { it.first !in s.hiddenGenres }
    }.state(emptyList())

    fun genre(name: String): Flow<List<BookEntity>> = books.map { all -> groupByGenre(all).firstOrNull { it.first == name }?.second.orEmpty() }

    val stats: StateFlow<Stats> = combine(dao.observeListeningDays(), books) { days, all -> computeStats(days, all) }.state(Stats())

    private fun computeStats(days: List<ListeningDayEntity>, all: List<BookEntity>): Stats {
        val today = LocalDate.now().toEpochDay()
        val byDay = days.groupBy { it.epochDay }.mapValues { (_, v) -> v.sumOf { it.listenedMs } }
        var streak = 0
        var d = if ((byDay[today] ?: 0L) > 0) today else today - 1
        while ((byDay[d] ?: 0L) > 60_000) { streak++; d-- }
        val bookIndex = all.associateBy { it.id }
        val monthStart = today - 30
        val recent = days.filter { it.epochDay > monthStart }
        val topBooks = recent.groupBy { it.bookId }.mapNotNull { (id, v) -> bookIndex[id]?.let { it to v.sumOf { x -> x.listenedMs } } }
            .sortedByDescending { it.second }.take(5)
        val topAuthors = days.groupBy { bookIndex[it.bookId]?.author ?: "Unknown" }.mapValues { (_, v) -> v.sumOf { it.listenedMs } }
            .toList().sortedByDescending { it.second }.take(5)
        val year = LocalDate.now().year
        return Stats(
            totalMs = days.sumOf { it.listenedMs },
            weekMs = (today - 6..today).sumOf { byDay[it] ?: 0L },
            todayMs = byDay[today] ?: 0L,
            dailyAverageMs = (today - 29..today).sumOf { byDay[it] ?: 0L } / 30,
            streakDays = streak,
            finishedTotal = all.count { it.isFinished },
            finishedThisYear = all.count { it.isFinished && it.finishedAt > 0 && java.time.Instant.ofEpochMilli(it.finishedAt).atZone(java.time.ZoneId.systemDefault()).year == year },
            last14Days = (today - 13..today).map { LocalDate.ofEpochDay(it) to (byDay[it] ?: 0L) },
            topBooks = topBooks,
            topAuthors = topAuthors,
            finishedThisYearBooks = all.filter {
                it.isFinished && it.finishedAt > 0 && java.time.Instant.ofEpochMilli(it.finishedAt).atZone(java.time.ZoneId.systemDefault()).year == year
            }.sortedByDescending { it.finishedAt },
        )
    }

    fun book(id: String): Flow<BookEntity?> = dao.observeBook(id)
    fun bookmarks(id: String) = dao.observeBookmarks(id)
    fun byAuthor(author: String): Flow<List<BookEntity>> = books.map { all ->
        all.filter { it.author == author }.sortedWith(compareBy({ it.seriesName == null }, { it.seriesName.orEmpty() }, { it.seriesIndex ?: 0f }, { it.year ?: 0 }, { it.sortTitle }))
    }
    fun bySeries(series: String): Flow<List<BookEntity>> = books.map { all ->
        all.filter { it.seriesName == series }.sortedWith(compareBy({ it.seriesIndex ?: 0f }, { it.sortTitle }))
    }
    fun chapters(id: String): Flow<List<ChapterEntity>> = dao.observeChapters(id)
    fun series(book: BookEntity): Flow<List<BookEntity>> = books.map { all ->
        all.filter { it.seriesName != null && it.seriesName == book.seriesName }.sortedWith(compareBy({ it.seriesIndex ?: 0f }, { it.sortTitle }))
    }

    // ---------------- Actions ----------------

    fun sync() = viewModelScope.launch { repo.syncAll(force = true) }
    fun openBook(id: String) = viewModelScope.launch { repo.ensureDetails(id) }
    fun play(id: String, startMs: Long? = null) = player.play(id, startMs)
    fun toggleList(id: String) = viewModelScope.launch { repo.toggleListeningList(id) }
    fun moveInList(id: String, up: Boolean) = viewModelScope.launch { repo.moveInList(id, up) }
    fun reorderList(ids: List<String>) = viewModelScope.launch { repo.reorderList(ids) }
    fun setFinished(id: String, finished: Boolean) = viewModelScope.launch {
        if (finished) player.stopIfPlaying(id)
        repo.setFinished(id, finished)
    }
    fun download(id: String) = viewModelScope.launch { Downloads.enqueue(getApplication<Application>(), id, settings.value.downloadOnWifiOnly) }
    fun removeDownload(id: String) = viewModelScope.launch { Downloads.remove(getApplication<Application>(), id) }

    fun setSort(sort: LibrarySort) = viewModelScope.launch {
        if (settings.value.librarySort == sort) c.settings.setSortDescending(!settings.value.sortDescending)
        else { c.settings.setLibrarySort(sort); c.settings.setSortDescending(false) }
    }
    fun setGrid(grid: Boolean) = viewModelScope.launch { c.settings.setLibraryGrid(grid) }
    fun setTheme(mode: ThemeMode) = viewModelScope.launch { c.settings.setTheme(mode) }
    fun setResumeRewind(s: Int) = viewModelScope.launch { c.settings.setResumeRewind(s) }
    fun setAutoMarkFinished(v: Boolean) = viewModelScope.launch { c.settings.setAutoMarkFinished(v) }
    fun setAutoQueueSeries(v: Boolean) = viewModelScope.launch { c.settings.setAutoQueueSeries(v) }
    fun setRemoveFinishedFromList(v: Boolean) = viewModelScope.launch { c.settings.setRemoveFinishedFromList(v) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { c.settings.setDownloadOnWifiOnly(v) }
    fun setSmartRewind(v: Boolean) = viewModelScope.launch { c.settings.setSmartRewind(v) }
    fun setSkipSilence(v: Boolean) = viewModelScope.launch { c.settings.setSkipSilence(v) }
    fun setVoiceBoost(v: Boolean) = viewModelScope.launch { c.settings.setVoiceBoost(v) }
    fun setAutoDownloadNext(v: Boolean) = viewModelScope.launch { c.settings.setAutoDownloadNext(v) }
    fun setDeleteWhenFinished(v: Boolean) = viewModelScope.launch { c.settings.setDeleteDownloadWhenFinished(v) }
    fun setYearlyGoal(v: Int) = viewModelScope.launch { c.settings.setYearlyGoal(v.coerceIn(1, 365)) }
    fun setCustomName(v: String) = viewModelScope.launch { c.settings.setCustomName(v) }
    fun setHomeLayout(v: com.leatherbound.listening.data.HomeLayout) = viewModelScope.launch { c.settings.setHomeLayout(v) }
    fun setHiddenGenres(v: Set<String>) = viewModelScope.launch { c.settings.setHiddenGenres(v) }
    fun setDefaultSpeed(v: Float) = viewModelScope.launch { c.settings.setPlaybackSpeed(v) }

    fun addBookmark(note: String) = player.addBookmark(note)
    fun markUnread(id: String) = viewModelScope.launch {
        player.stopIfPlaying(id)
        repo.markUnread(id)
    }
    fun deleteBookmark(id: Long) = viewModelScope.launch { repo.deleteBookmark(id) }
    fun setSleepTimer(minutes: Int) = player.setSleepTimer(minutes)
    fun removeFinishedDownloads() = viewModelScope.launch {
        downloads.value.filter { it.book.isFinished }.forEach { Downloads.remove(getApplication<Application>(), it.book.id) }
    }

    // ---------------- Connecting sources ----------------

    val connect = MutableStateFlow<ConnectState>(ConnectState.Idle)
    private var connectJob: Job? = null

    private fun connecting(block: suspend () -> Unit) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            try { block() } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) connect.value = ConnectState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelConnect() { connectJob?.cancel(); connect.value = ConnectState.Idle }

    /** Plex sign-in: opens plex.tv in the browser and waits for the user to approve this app. */
    fun signInToPlex(openUrl: (String) -> Unit) = connecting {
        val plex = repo.plex()
        connect.value = ConnectState.Working("Opening Plex…")
        val pin = plex.createPin()
        openUrl(plex.authUrl(pin))
        connect.value = ConnectState.Working("Approve Leatherbound Listening in your browser, then return here.")
        val token = plex.awaitToken(pin) ?: throw IllegalStateException("Plex sign-in timed out")
        runCatching { plex.accountName(token) }.getOrNull()?.let { c.settings.setAccountName(it) }
        connect.value = ConnectState.Working("Finding your servers…")
        val servers = plex.servers(token)
        connect.value = if (servers.isEmpty()) ConnectState.Failed("No Plex servers found on this account") else ConnectState.PickPlexServer(servers)
    }

    fun connectPlexManually(url: String, token: String) =
        url.trim().trimEnd('/').let { base -> choosePlexServer(PlexServer("Plex", base, token.trim(), listOf(base))) }

    fun choosePlexServer(server: PlexServer) = connecting {
        connect.value = ConnectState.Working("Reading libraries on ${server.name}…")
        val libs = repo.plex().libraries(server.baseUrl, server.token)
        val pending = SourceEntity(
            type = SourceType.PLEX, name = server.name, baseUrl = server.baseUrl, token = server.token,
            connections = server.connections.joinToString("\n"),
        )
        connect.value = if (libs.isEmpty()) ConnectState.Failed("No music/audiobook libraries on ${server.name}") else ConnectState.PickLibrary(libs, pending)
    }

    fun connectJellyfin(url: String, user: String, password: String) = connecting {
        val base = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        connect.value = ConnectState.Working("Signing in…")
        val jf = repo.jellyfin()
        val login = jf.login(base, user.trim(), password)
        val libs = jf.libraries(base, login.token, login.userId)
        val pending = SourceEntity(type = SourceType.JELLYFIN, name = "Jellyfin", baseUrl = base, token = login.token, userId = login.userId)
        connect.value = if (libs.isEmpty()) ConnectState.Failed("No libraries found") else ConnectState.PickLibrary(libs, pending)
    }

    fun chooseLibrary(pending: SourceEntity, library: RemoteLibrary) = connecting {
        connect.value = ConnectState.Working("Cataloguing ${library.name}…")
        repo.addSource(pending.copy(name = "${pending.name} · ${library.name}", libraryId = library.id))
        connect.value = ConnectState.Idle
    }

    fun addLocalFolder(uri: Uri) = connecting {
        connect.value = ConnectState.Working("Scanning folder…")
        repo.addLocalFolder(uri)
        connect.value = ConnectState.Idle
    }

    fun removeSource(source: SourceEntity) = viewModelScope.launch { repo.removeSource(source) }

    companion object {
        fun browserIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
