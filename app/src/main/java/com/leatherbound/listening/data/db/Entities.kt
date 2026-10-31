package com.leatherbound.listening.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SourceType { PLEX, JELLYFIN, LOCAL }

enum class DownloadState { NONE, QUEUED, DOWNLOADING, DONE, FAILED }

/** One library on one server, or one local folder. */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: SourceType,
    val name: String,
    /** Server base URL, or the SAF tree URI for a local folder. */
    val baseUrl: String,
    val token: String = "",
    val userId: String = "",
    val libraryId: String = "",
    val lastSyncedAt: Long = 0,
    /** Plex: every address for the server, best first, newline-separated. [baseUrl] is the one in use. */
    val connections: String = "",
)

@Entity(tableName = "books", indices = [Index("sourceId"), Index("seriesName")])
data class BookEntity(
    @PrimaryKey val id: String,
    val sourceId: Long,
    val remoteId: String,
    val title: String,
    val sortTitle: String,
    val author: String,
    val narrator: String? = null,
    val year: Int? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long = 0,
    val addedAt: Long = 0,
    val seriesName: String? = null,
    val seriesIndex: Float? = null,
    /** False until chapters have been fetched from the server (Plex fetches them lazily). */
    val detailsLoaded: Boolean = true,
    // --- Listener state, preserved across metadata syncs ---
    val positionMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val isFinished: Boolean = false,
    val finishedAt: Long = 0,
    /** Non-null when the book is on the listening list; lower comes first. */
    val listPosition: Int? = null,
    val downloadState: DownloadState = DownloadState.NONE,
    val downloadProgress: Float = 0f,
    /** Speed chosen for this book; null means the default speed from Settings. */
    val playbackSpeed: Float? = null,
    /** Local folders: fingerprint of the files, so unchanged books are not re-read on rescan. */
    val scanSignature: String? = null,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val inProgress: Boolean get() = positionMs > 0 && !isFinished
    val onListeningList: Boolean get() = listPosition != null
}

/** A playable file. Books made of several files have several tracks laid end to end. */
@Entity(tableName = "tracks", primaryKeys = ["bookId", "index"])
data class TrackEntity(
    val bookId: String,
    val index: Int,
    val title: String,
    val remoteId: String,
    val url: String,
    val durationMs: Long,
    /** Where this track starts within the whole book. */
    val startOffsetMs: Long,
    val container: String? = null,
    val localPath: String? = null,
)

/** Chapter boundaries in whole-book time. */
@Entity(tableName = "chapters", primaryKeys = ["bookId", "index"])
data class ChapterEntity(
    val bookId: String,
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val positionMs: Long,
    val note: String,
    val createdAt: Long,
)

/** Listening time per book per day, for the Stats tab. */
@Entity(tableName = "listening_days", primaryKeys = ["epochDay", "bookId"])
data class ListeningDayEntity(
    val epochDay: Long,
    val bookId: String,
    val listenedMs: Long,
)
