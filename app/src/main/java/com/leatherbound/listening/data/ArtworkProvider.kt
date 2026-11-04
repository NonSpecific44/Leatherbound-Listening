package com.leatherbound.listening.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.leatherbound.listening.LeatherboundApp
import com.leatherbound.listening.data.db.BookEntity
import com.leatherbound.listening.data.db.CollectionEntity
import com.leatherbound.listening.data.local.LocalScanner
import com.leatherbound.listening.data.remote.Http
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves every book cover as content://…artwork/<book>/<version>. Android Auto, the widget and
 * the notification can't fetch Plex/Jellyfin URLs (or read app-private files), but they can read
 * this. Covers are cached on disk, so they also show offline.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        val segments = uri.pathSegments
        val repo = (ctx.applicationContext as LeatherboundApp).container.repository
        val file = runBlocking {
            // content://…/<bookId>/<v> for books, content://…/c/<collectionId>/<v> for collections.
            val (sourceId, url) = if (segments.firstOrNull() == "c") {
                val c = repo.dao.collection(Uri.decode(segments.getOrNull(1) ?: "")) ?: throw FileNotFoundException()
                c.sourceId to (c.coverUrl ?: throw FileNotFoundException("no cover"))
            } else {
                val book = repo.dao.book(Uri.decode(segments.firstOrNull() ?: "")) ?: throw FileNotFoundException()
                book.sourceId to (book.coverUrl ?: throw FileNotFoundException("no cover"))
            }
            val source = repo.dao.source(sourceId)
            val folder = File(ctx.filesDir, "artwork").apply { mkdirs() }
            val cached = File(folder, cacheKey(sourceId, url) + ".img")
            // Covers saved by older versions were named after the whole address.
            if (!cached.exists()) File(folder, LocalScanner.sha1(url) + ".img").takeIf { it.exists() }?.renameTo(cached)
            if (!cached.exists() || cached.length() == 0L) {
                val tmp = File(cached.path + ".tmp")
                val parsed = Uri.parse(url)
                if (parsed.scheme == "http" || parsed.scheme == "https") {
                    val token = source?.let { TokenVault.decrypt(it.token) }.orEmpty()
                    Http.client.newCall(Request.Builder().url(TokenVault.fill(url, token)).build()).execute().use { r ->
                        if (!r.isSuccessful) throw FileNotFoundException("HTTP ${r.code}")
                        tmp.outputStream().use { out -> r.body?.byteStream()?.copyTo(out) }
                    }
                } else {
                    ctx.contentResolver.openInputStream(parsed)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        ?: throw FileNotFoundException(url)
                }
                tmp.renameTo(cached)
            }
            cached
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.leatherbound.listening.artwork"

        /**
         * Names a cover by its library and its path on the server, not the server's address, so moving
         * between home and away (see LibraryRepository.refreshConnections) doesn't fetch it again.
         */
        fun cacheKey(sourceId: Long, url: String): String =
            LocalScanner.sha1("$sourceId|" + url.replace(Regex("^https?://[^/]+"), ""))

        /** The version segment changes when the cover does, so caches refresh. */
        fun uri(book: BookEntity): Uri? = book.coverUrl?.let {
            Uri.parse("content://$AUTHORITY/${Uri.encode(book.id)}/${cacheKey(book.sourceId, it).take(12)}")
        }

        fun uri(collection: CollectionEntity): Uri? = collection.coverUrl?.let {
            Uri.parse("content://$AUTHORITY/c/${Uri.encode(collection.id)}/${cacheKey(collection.sourceId, it).take(12)}")
        }
    }
}
