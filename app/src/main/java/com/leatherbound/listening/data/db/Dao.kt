package com.leatherbound.listening.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    // Sources
    @Query("SELECT * FROM sources ORDER BY name") fun observeSources(): Flow<List<SourceEntity>>
    @Query("SELECT * FROM sources") suspend fun sources(): List<SourceEntity>
    @Query("SELECT * FROM sources WHERE id = :id") suspend fun source(id: Long): SourceEntity?
    @Insert suspend fun insertSource(source: SourceEntity): Long
    @Query("UPDATE sources SET lastSyncedAt = :at WHERE id = :id") suspend fun markSynced(id: Long, at: Long)
    @Query("DELETE FROM sources WHERE id = :id") suspend fun deleteSource(id: Long)
    @Query("UPDATE sources SET token = :token WHERE id = :id") suspend fun updateToken(id: Long, token: String)
    @Query("UPDATE sources SET baseUrl = :baseUrl, connections = :connections WHERE id = :id")
    suspend fun updateConnection(id: Long, baseUrl: String, connections: String)
    @Query("UPDATE tracks SET url = REPLACE(url, :oldBase, :newBase) WHERE bookId IN (SELECT id FROM books WHERE sourceId = :sourceId)")
    suspend fun rebaseTracks(sourceId: Long, oldBase: String, newBase: String)
    @Query("UPDATE books SET coverUrl = REPLACE(coverUrl, :oldBase, :newBase) WHERE sourceId = :sourceId")
    suspend fun rebaseCovers(sourceId: Long, oldBase: String, newBase: String)

    @Transaction
    suspend fun moveSource(sourceId: Long, oldBase: String, newBase: String, connections: String) {
        updateConnection(sourceId, newBase, connections)
        rebaseTracks(sourceId, oldBase, newBase)
        rebaseCovers(sourceId, oldBase, newBase)
    }

    // Books
    @Query("SELECT * FROM books") fun observeBooks(): Flow<List<BookEntity>>
    @Query("SELECT * FROM books WHERE id = :id") fun observeBook(id: String): Flow<BookEntity?>
    @Query("SELECT * FROM books WHERE id = :id") suspend fun book(id: String): BookEntity?
    @Query("SELECT * FROM books WHERE sourceId = :sourceId") suspend fun booksForSource(sourceId: Long): List<BookEntity>
    @Query("SELECT * FROM books WHERE positionMs > 0 AND isFinished = 0 ORDER BY lastPlayedAt DESC")
    fun observeInProgress(): Flow<List<BookEntity>>
    @Query("SELECT * FROM books WHERE positionMs > 0 AND isFinished = 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    suspend fun inProgress(limit: Int): List<BookEntity>
    @Query("SELECT * FROM books WHERE listPosition IS NOT NULL ORDER BY listPosition")
    fun observeListeningList(): Flow<List<BookEntity>>
    @Query("SELECT * FROM books WHERE listPosition IS NOT NULL ORDER BY listPosition")
    suspend fun listeningList(): List<BookEntity>
    @Query("SELECT * FROM books WHERE downloadState = 'DONE' ORDER BY title")
    suspend fun downloaded(): List<BookEntity>
    @Query("SELECT * FROM books ORDER BY addedAt DESC LIMIT :limit") suspend fun recentlyAdded(limit: Int): List<BookEntity>
    @Query("SELECT * FROM books WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun lastPlayed(): BookEntity?
    @Query("SELECT * FROM books WHERE seriesName = :series ORDER BY seriesIndex, sortTitle")
    suspend fun series(series: String): List<BookEntity>
    @Query("SELECT * FROM books WHERE title LIKE '%' || :q || '%' OR author LIKE '%' || :q || '%' LIMIT 50")
    suspend fun search(q: String): List<BookEntity>
    @Upsert suspend fun upsertBooks(books: List<BookEntity>)
    @Query("DELETE FROM books WHERE id IN (:ids)") suspend fun deleteBooks(ids: List<String>)
    @Query("DELETE FROM books WHERE sourceId = :sourceId") suspend fun deleteBooksForSource(sourceId: Long)

    @Query("UPDATE books SET positionMs = :positionMs, lastPlayedAt = :at WHERE id = :id")
    suspend fun updatePosition(id: String, positionMs: Long, at: Long)
    @Query("UPDATE books SET isFinished = :finished, finishedAt = :at WHERE id = :id")
    suspend fun setFinished(id: String, finished: Boolean, at: Long)
    @Query("UPDATE books SET listPosition = :position WHERE id = :id")
    suspend fun setListPosition(id: String, position: Int?)
    @Query("SELECT MAX(listPosition) FROM books") suspend fun maxListPosition(): Int?
    @Query("UPDATE books SET downloadState = :state, downloadProgress = :progress WHERE id = :id")
    suspend fun setDownload(id: String, state: DownloadState, progress: Float)
    @Query("UPDATE books SET detailsLoaded = 1 WHERE id = :id") suspend fun markDetailsLoaded(id: String)
    @Query("UPDATE books SET playbackSpeed = :speed WHERE id = :id") suspend fun setBookSpeed(id: String, speed: Float?)
    @Query("SELECT * FROM books WHERE downloadState != 'NONE' ORDER BY title") fun observeDownloads(): Flow<List<BookEntity>>

    // Collections
    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE") fun observeCollections(): Flow<List<CollectionEntity>>
    @Query("SELECT * FROM collections WHERE id = :id") suspend fun collection(id: String): CollectionEntity?
    @Query("SELECT * FROM collection_books ORDER BY position") fun observeCollectionBooks(): Flow<List<CollectionBookEntity>>
    @Query("DELETE FROM collections WHERE sourceId = :sourceId") suspend fun deleteCollectionsForSource(sourceId: Long)
    @Query("DELETE FROM collection_books WHERE collectionId IN (SELECT id FROM collections WHERE sourceId = :sourceId)")
    suspend fun deleteCollectionBooksForSource(sourceId: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCollections(collections: List<CollectionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCollectionBooks(links: List<CollectionBookEntity>)

    @Transaction
    suspend fun replaceCollections(sourceId: Long, collections: List<CollectionEntity>, links: List<CollectionBookEntity>) {
        deleteCollectionBooksForSource(sourceId)
        deleteCollectionsForSource(sourceId)
        insertCollections(collections)
        insertCollectionBooks(links)
    }

    @Query("UPDATE books SET positionMs = 0, lastPlayedAt = 0, isFinished = 0, finishedAt = 0 WHERE id = :id")
    suspend fun resetProgress(id: String)

    // Bookmarks
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY positionMs") fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>>
    @Insert suspend fun insertBookmark(bookmark: BookmarkEntity): Long
    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun deleteBookmark(id: Long)
    @Query("DELETE FROM bookmarks WHERE bookId IN (SELECT id FROM books WHERE sourceId = :sourceId)") suspend fun deleteBookmarksForSource(sourceId: Long)

    // Tracks and chapters
    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY `index`") suspend fun tracks(bookId: String): List<TrackEntity>
    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY `index`") fun observeTracks(bookId: String): Flow<List<TrackEntity>>
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index`") suspend fun chapters(bookId: String): List<ChapterEntity>
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index`") fun observeChapters(bookId: String): Flow<List<ChapterEntity>>
    @Query("DELETE FROM tracks WHERE bookId = :bookId") suspend fun deleteTracks(bookId: String)
    @Query("DELETE FROM chapters WHERE bookId = :bookId") suspend fun deleteChapters(bookId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTracks(tracks: List<TrackEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertChapters(chapters: List<ChapterEntity>)
    @Query("UPDATE tracks SET localPath = :path WHERE bookId = :bookId AND `index` = :index")
    suspend fun setTrackLocalPath(bookId: String, index: Int, path: String?)

    @Transaction
    suspend fun replaceChapters(bookId: String, chapters: List<ChapterEntity>) {
        deleteChapters(bookId)
        insertChapters(chapters)
    }

    @Transaction
    suspend fun replaceTracks(bookId: String, tracks: List<TrackEntity>) {
        // Keep downloaded files attached when the same remote file comes back from a sync.
        val previous = tracks(bookId).associateBy { it.remoteId }
        deleteTracks(bookId)
        insertTracks(tracks.map { t -> t.copy(localPath = previous[t.remoteId]?.localPath ?: t.localPath) })
    }

    // Stats
    @Query("SELECT * FROM listening_days") fun observeListeningDays(): Flow<List<ListeningDayEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDay(day: ListeningDayEntity)
    @Query("UPDATE listening_days SET listenedMs = listenedMs + :ms WHERE epochDay = :day AND bookId = :bookId")
    suspend fun addToDay(day: Long, bookId: String, ms: Long)

    @Transaction
    suspend fun addListening(day: Long, bookId: String, ms: Long) {
        insertDay(ListeningDayEntity(day, bookId, 0))
        addToDay(day, bookId, ms)
    }
}
