package com.leatherbound.listening.data

import com.leatherbound.listening.data.db.BookEntity
import com.leatherbound.listening.data.db.BookmarkEntity
import com.leatherbound.listening.data.db.LibraryDao
import com.leatherbound.listening.data.db.ListeningDayEntity
import com.leatherbound.listening.data.db.SourceEntity
import com.leatherbound.listening.data.remote.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Your listening history in one file: settings, where you are in each book, finished books,
 * Up Next, bookmarks and stats. Server sign-ins aren't included (they're tied to this phone);
 * on a new phone, add your libraries, let them sync, then restore.
 *
 * Books are matched by their library and server id, or failing that by title and author, so a
 * backup still lines up after the app gives books new internal ids.
 */
@Serializable
data class BackupFile(
    val format: Int = 1,
    val createdAt: Long,
    val appVersion: String,
    val settings: Map<String, SettingValue> = emptyMap(),
    val books: List<BookState> = emptyList(),
    val bookmarks: List<BookmarkBackup> = emptyList(),
    val listening: List<DayBackup> = emptyList(),
)

@Serializable data class SettingValue(val type: String, val value: JsonElement)

@Serializable
data class BookState(
    val key: String,
    val match: String,
    val title: String,
    val positionMs: Long,
    val lastPlayedAt: Long,
    val finished: Boolean,
    val finishedAt: Long,
    val listPosition: Int? = null,
    val speed: Float? = null,
)

@Serializable data class BookmarkBackup(val key: String, val match: String, val positionMs: Long, val note: String, val createdAt: Long)

@Serializable data class DayBackup(val day: Long, val key: String, val match: String, val ms: Long)

data class RestoreResult(val books: Int, val missingBooks: Int, val bookmarks: Int, val days: Int)

object Backup {
    /** Stays the same on another phone: the library (by type and name) and the server's id for the book. */
    fun key(source: SourceEntity?, book: BookEntity) = "${source?.type}|${source?.name}|${book.remoteId}"

    fun match(title: String, author: String) =
        (title + "|" + author).lowercase().replace(Regex("[^\\p{L}\\p{N}|]+"), " ").trim()

    private fun match(book: BookEntity) = match(book.title, book.author)

    suspend fun export(dao: LibraryDao, settings: SettingsStore, appVersion: String): String {
        val sources = dao.sources().associateBy { it.id }
        val books = dao.allBooks()
        val byId = books.associateBy { it.id }
        fun keyOf(id: String) = byId[id]?.let { key(sources[it.sourceId], it) }
        fun matchOf(id: String) = byId[id]?.let(::match)

        val file = BackupFile(
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion,
            settings = settings.exportAll().mapNotNull { (name, value) -> encode(value)?.let { name to it } }.toMap(),
            books = books.filter { it.hasHistory || it.listPosition != null || it.playbackSpeed != null }.map { b ->
                BookState(key(sources[b.sourceId], b), match(b), b.title, b.positionMs, b.lastPlayedAt, b.isFinished, b.finishedAt, b.listPosition, b.playbackSpeed)
            },
            bookmarks = dao.allBookmarks().mapNotNull { m ->
                BookmarkBackup(keyOf(m.bookId) ?: return@mapNotNull null, matchOf(m.bookId).orEmpty(), m.positionMs, m.note, m.createdAt)
            },
            // Days for books no longer in the library keep their old id, so totals and streaks survive.
            listening = dao.allListeningDays().map { d -> DayBackup(d.epochDay, keyOf(d.bookId) ?: "id|${d.bookId}", matchOf(d.bookId).orEmpty(), d.listenedMs) },
        )
        return Http.json.encodeToString(file)
    }

    /**
     * Applies a backup to the books in the library now. Positions and finished state are replaced
     * for books it covers; bookmarks are added (not duplicated); listening time keeps the larger
     * figure for each book and day, so restoring twice doesn't double your stats.
     */
    suspend fun restore(json: String, dao: LibraryDao, settings: SettingsStore): RestoreResult {
        val file = Http.json.decodeFromString<BackupFile>(json)
        settings.importAll(file.settings.mapNotNull { (name, v) -> decode(v)?.let { name to it } }.toMap())

        val sources = dao.sources().associateBy { it.id }
        val books = dao.allBooks()
        val byKey = books.associateBy { key(sources[it.sourceId], it) }
        val byMatch = books.groupBy(::match).filterValues { it.size == 1 }.mapValues { it.value.first() }
        fun find(key: String, match: String): BookEntity? = byKey[key] ?: byMatch[match]

        var restored = 0
        var missing = 0
        for (s in file.books) {
            val book = find(s.key, s.match)
            if (book == null) { missing++; continue }
            dao.restoreState(book.id, s.positionMs, s.lastPlayedAt, s.finished, s.finishedAt, s.listPosition, s.speed)
            restored++
        }

        val existingMarks = dao.allBookmarks().map { it.bookId to it.positionMs }.toSet()
        var marks = 0
        for (m in file.bookmarks) {
            val book = find(m.key, m.match) ?: continue
            if ((book.id to m.positionMs) in existingMarks) continue
            dao.insertBookmark(BookmarkEntity(bookId = book.id, positionMs = m.positionMs, note = m.note, createdAt = m.createdAt))
            marks++
        }

        val existingDays = dao.allListeningDays().associateBy { it.epochDay to it.bookId }
        val days = file.listening.map { d ->
            // Books that aren't here (yet) keep a stand-in id; they still count towards totals.
            val id = find(d.key, d.match)?.id ?: if (d.key.startsWith("id|")) d.key.removePrefix("id|") else "restored|${d.key}"
            ListeningDayEntity(d.day, id, maxOf(d.ms, existingDays[d.day to id]?.listenedMs ?: 0))
        }
        // A book found this time may have had a stand-in from an earlier restore; drop that copy.
        file.listening.filter { find(it.key, it.match) != null }.map { it.key }.toSet()
            .forEach { dao.deleteListening("restored|$it") }
        dao.upsertListeningDays(days)
        return RestoreResult(restored, missing, marks, days.map { it.epochDay }.distinct().size)
    }

    private fun encode(value: Any): SettingValue? = when (value) {
        is Boolean -> SettingValue("boolean", JsonPrimitive(value))
        is Int -> SettingValue("int", JsonPrimitive(value))
        is Float -> SettingValue("float", JsonPrimitive(value))
        is String -> SettingValue("string", JsonPrimitive(value))
        is Set<*> -> SettingValue("stringSet", JsonArray(value.filterIsInstance<String>().map(::JsonPrimitive)))
        else -> null
    }

    private fun decode(v: SettingValue): Any? = runCatching {
        when (v.type) {
            "boolean" -> v.value.jsonPrimitive.boolean
            "int" -> v.value.jsonPrimitive.int
            "float" -> v.value.jsonPrimitive.float
            "string" -> v.value.jsonPrimitive.content
            "stringSet" -> v.value.jsonArray.map { it.jsonPrimitive.content }.toSet()
            else -> null
        }
    }.getOrNull()
}
