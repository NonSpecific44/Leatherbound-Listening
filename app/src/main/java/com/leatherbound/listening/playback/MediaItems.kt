package com.leatherbound.listening.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.leatherbound.listening.data.ArtworkProvider
import com.leatherbound.listening.data.TokenVault
import com.leatherbound.listening.data.db.BookEntity
import com.leatherbound.listening.data.db.TrackEntity
import java.io.File

/** Media IDs shared by the app UI, the notification and Android Auto. */
object MediaIds {
    const val ROOT = "root"
    const val CONTINUE = "folder:continue"
    const val LIST = "folder:list"
    const val LIBRARY = "folder:library"
    const val DOWNLOADED = "folder:downloaded"

    const val EXTRA_BOOK_ID = "bookId"
    const val EXTRA_OFFSET = "offset"
    const val EXTRA_BOOK_DURATION = "bookDuration"
    const val EXTRA_TRACK_TITLE = "trackTitle"
    /** Request extra: start at this whole-book position instead of the saved one. */
    const val EXTRA_START_MS = "startMs"
    /** Request extra: this is the app restoring the last book on launch. */
    const val EXTRA_RESTORE = "restore"

    fun book(id: String) = "book:$id"
    fun bookIdOf(mediaId: String): String? = mediaId.takeIf { it.startsWith("book:") }?.removePrefix("book:")
}

fun BookEntity.toBrowsableItem(): MediaItem = MediaItem.Builder()
    .setMediaId(MediaIds.book(id))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(author)
            .setSubtitle(author)
            .setArtworkUri(ArtworkProvider.uri(this))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setExtras(bundleOf(MediaIds.EXTRA_BOOK_ID to id))
            .build()
    )
    .build()

fun folderItem(id: String, title: String): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setIsPlayable(false)
            .setIsBrowsable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
            .build()
    )
    .build()

fun trackItems(book: BookEntity, tracks: List<TrackEntity>, token: String): List<MediaItem> = tracks.map { t ->
    val uri = t.localPath?.let { path -> File(path).takeIf { it.exists() }?.let(Uri::fromFile) } ?: Uri.parse(TokenVault.fill(t.url, token))
    MediaItem.Builder()
        .setMediaId("track:${book.id}#${t.index}")
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(book.title)
                .setArtist(book.author)
                .setAlbumTitle(book.title)
                .setAlbumArtist(book.author)
                .setSubtitle(if (tracks.size > 1) t.title else book.author)
                .setArtworkUri(ArtworkProvider.uri(book))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                .setExtras(
                    bundleOf(
                        MediaIds.EXTRA_BOOK_ID to book.id,
                        MediaIds.EXTRA_OFFSET to t.startOffsetMs,
                        MediaIds.EXTRA_BOOK_DURATION to book.durationMs,
                        MediaIds.EXTRA_TRACK_TITLE to t.title,
                    )
                )
                .build()
        )
        .build()
}

private fun Player.extrasAt(index: Int): Bundle? =
    if (index in 0 until mediaItemCount) getMediaItemAt(index).mediaMetadata.extras else null

/** Book currently loaded in this player, if any. */
val Player.currentBookId: String? get() = extrasAt(currentMediaItemIndex)?.getString(MediaIds.EXTRA_BOOK_ID)

val Player.bookDurationMs: Long get() = extrasAt(currentMediaItemIndex)?.getLong(MediaIds.EXTRA_BOOK_DURATION) ?: 0L

/** Position across the whole book, not just the current file. */
val Player.bookPositionMs: Long
    get() = (extrasAt(currentMediaItemIndex)?.getLong(MediaIds.EXTRA_OFFSET) ?: 0L) + currentPosition.coerceAtLeast(0)

/** Seeks to a whole-book position, crossing file boundaries as needed. */
fun Player.seekToBookPosition(positionMs: Long) {
    if (mediaItemCount == 0) return
    val target = positionMs.coerceIn(0, bookDurationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    var index = 0
    for (i in 0 until mediaItemCount) {
        val offset = extrasAt(i)?.getLong(MediaIds.EXTRA_OFFSET) ?: 0L
        if (offset <= target) index = i else break
    }
    val offset = extrasAt(index)?.getLong(MediaIds.EXTRA_OFFSET) ?: 0L
    seekTo(index, target - offset)
}

/** Maps a whole-book position to (file index, position in file). */
fun List<TrackEntity>.locate(positionMs: Long): Pair<Int, Long> {
    val track = lastOrNull { it.startOffsetMs <= positionMs } ?: firstOrNull() ?: return 0 to 0L
    return indexOf(track) to (positionMs - track.startOffsetMs).coerceAtLeast(0)
}
