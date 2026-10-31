package com.leatherbound.listening.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.leatherbound.listening.data.remote.RemoteBook
import com.leatherbound.listening.data.remote.RemoteChapter
import com.leatherbound.listening.data.remote.RemoteTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Turns a folder the user picked into books. Every folder that holds audio files is one book;
 * loose files in the top folder are a book each. Folder layout Author/Series/Book is understood.
 *
 * Folders are listed with one DocumentsContract query each (DocumentFile costs a query per file
 * property), and books whose files are unchanged since the last scan are not opened again.
 */
class LocalScanner(private val context: Context) {

    private val audioExtensions = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "mka", "wma")
    private val coverNames = listOf("cover", "folder", "front", "albumart")

    private data class Doc(val uri: Uri, val id: String, val name: String, val mime: String, val size: Long, val modified: Long) {
        val isDir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
        val isImage get() = mime.startsWith("image/")
    }

    private fun Doc.isAudio(extensions: Set<String>) =
        !isDir && (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in extensions)

    /** [known] maps a book's remoteId to its signature from the previous scan. */
    suspend fun scan(treeUri: Uri, known: Map<String, String?>): List<RemoteBook> = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val books = mutableListOf<RemoteBook>()
        val top = children(treeUri, rootId)
        top.filter { it.isAudio(audioExtensions) }.forEach { file ->
            books += book(file.uri.toString(), listOf(file), null, emptyList(), known)
        }
        top.filter { it.isDir }.forEach { walk(treeUri, it, emptyList(), books, known) }
        books
    }

    private fun children(treeUri: Uri, parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val docs = mutableListOf<Doc>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                docs += Doc(
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id), id,
                    c.getString(1).orEmpty(), c.getString(2).orEmpty(),
                    if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        }
        return docs
    }

    private fun walk(treeUri: Uri, dir: Doc, ancestors: List<String>, out: MutableList<RemoteBook>, known: Map<String, String?>) {
        val children = children(treeUri, dir.id)
        val audio = children.filter { it.isAudio(audioExtensions) }.sortedWith(compareBy(NaturalOrder) { it.name })
        if (audio.isNotEmpty()) {
            val cover = children.firstOrNull { f -> f.isImage && coverNames.any { f.name.lowercase().startsWith(it) } }
                ?: children.firstOrNull { it.isImage }
            out += book(dir.uri.toString(), audio, cover, ancestors + dir.name, known)
        }
        children.filter { it.isDir }.forEach { walk(treeUri, it, ancestors + dir.name, out, known) }
    }

    private fun book(id: String, files: List<Doc>, cover: Doc?, folders: List<String>, known: Map<String, String?>): RemoteBook {
        val signature = sha1((files + listOfNotNull(cover)).joinToString("|") { "${it.name}:${it.size}:${it.modified}" })
        if (known[id] == signature) {
            return RemoteBook(id, "", "", "", tracks = emptyList(), chapters = null, signature = signature, unchanged = true)
        }
        return readBook(id, files, cover?.uri?.toString(), folders).copy(signature = signature)
    }

    private fun readBook(id: String, files: List<Doc>, folderCover: String?, folders: List<String>): RemoteBook {
        val retriever = MediaMetadataRetriever()
        try {
            val tracks = files.mapIndexed { i, f ->
                retriever.setDataSource(context, f.uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { files.size > 1 && it.isNotBlank() } ?: f.name.substringBeforeLast('.')
                RemoteTrack(f.uri.toString(), title.ifBlank { "Part ${i + 1}" }, f.uri.toString(), duration, f.name.substringAfterLast('.'))
            }
            retriever.setDataSource(context, files.first().uri)
            fun meta(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

            val folderName = folders.lastOrNull()
            val title = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: folderName
                ?: files.first().name.substringBeforeLast('.')
            val author = meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: meta(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                ?: folders.firstOrNull()?.takeIf { folders.size > 1 }
                ?: "Unknown author"
            val series = folders.getOrNull(folders.size - 2)?.takeIf { folders.size >= 3 }
            val cover = folderCover ?: retriever.embeddedPicture?.let { saveCover(id, it) }

            val first = files.first().name.lowercase()
            val chapters: List<RemoteChapter>? = if (files.size == 1 && (first.endsWith(".m4b") || first.endsWith(".m4a") || first.endsWith(".mp4"))) {
                val marks = Mp4Chapters.read(context, files.first().uri)
                val total = tracks.first().durationMs
                marks.mapIndexed { i, (name, start) ->
                    RemoteChapter(name.ifBlank { "Chapter ${i + 1}" }, start, marks.getOrNull(i + 1)?.second ?: total)
                }.takeIf { it.size > 1 }
            } else null

            return RemoteBook(
                remoteId = id,
                title = title,
                sortTitle = title.removePrefix("The ").removePrefix("A "),
                author = author,
                narrator = meta(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                year = meta(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull(),
                coverUrl = cover,
                addedAt = files.maxOf { it.modified },
                tracks = tracks,
                chapters = chapters,
                seriesName = series,
                seriesIndex = series?.let { leadingNumber(folderName.orEmpty()) },
            )
        } finally {
            retriever.release()
        }
    }

    private fun saveCover(id: String, bytes: ByteArray): String {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, sha1(id) + ".jpg")
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    companion object {
        fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** "01 - Title", "Book 2", "#3", "Vol. 4" -> series position. */
        fun leadingNumber(name: String): Float? =
            Regex("""(?:^|book\s*|#|vol(?:ume)?\.?\s*)(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toFloatOrNull()
    }
}

/** Sorts "Part 2" before "Part 10". */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("""\d+|\D+""")
    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) p.toBigInteger().compareTo(q.toBigInteger()) else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}
