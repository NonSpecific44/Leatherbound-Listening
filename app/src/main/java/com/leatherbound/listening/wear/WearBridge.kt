package com.leatherbound.listening.wear

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.leatherbound.listening.data.ArtworkProvider
import com.leatherbound.listening.data.db.BookEntity
import com.leatherbound.listening.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The watch app and the phone talk over the Wear OS Data Layer (Google Play services). The phone
 * publishes what's playing as a data item; the watch sends commands as messages. Both apps share
 * the package name and signing key, which the Data Layer requires.
 */
object WearProtocol {
    const val NOW_PLAYING = "/leatherbound/now_playing"
    const val COMMAND = "/leatherbound/command/"
    const val TOGGLE = "toggle"
    const val BACK = "back"
    const val FORWARD = "forward"
}

/** Publishes the current book, chapter and play state for the watch. Fails quietly on phones without Play services. */
class WearBridge(private val context: Context) {
    private var coverFor: String? = null
    private var cover: Asset? = null

    suspend fun publish(
        book: BookEntity?, chapter: String?, chapterStartMs: Long, chapterEndMs: Long,
        positionMs: Long, playing: Boolean, speed: Float,
    ) {
        runCatching {
            if (book != null && coverFor != book.id) {
                cover = coverAsset(book)
                coverFor = book.id
            }
            val request = PutDataMapRequest.create(WearProtocol.NOW_PLAYING).apply {
                dataMap.putString("bookId", book?.id.orEmpty())
                dataMap.putString("title", book?.title.orEmpty())
                dataMap.putString("author", book?.author.orEmpty())
                dataMap.putString("chapter", chapter.orEmpty())
                dataMap.putLong("chapterStartMs", chapterStartMs)
                dataMap.putLong("chapterEndMs", chapterEndMs)
                dataMap.putLong("positionMs", positionMs)
                dataMap.putLong("durationMs", book?.durationMs ?: 0)
                dataMap.putBoolean("playing", playing)
                dataMap.putFloat("speed", speed)
                // Always changes, so the watch hears about every update, and lets it run the clock on.
                dataMap.putLong("updatedAt", System.currentTimeMillis())
                cover?.let { dataMap.putAsset("cover", it) } ?: dataMap.remove("cover")
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
        }
    }

    /** A small JPEG of the cover; watches don't need more than ~300 px. */
    private suspend fun coverAsset(book: BookEntity): Asset? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = ArtworkProvider.uri(book) ?: return@runCatching null
            val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
            val scaled = Bitmap.createScaledBitmap(bitmap, 300, (300f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1), true)
            val bytes = ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
            Asset.createFromBytes(bytes)
        }.getOrNull()
    }
}

/** Receives play/pause and skip commands from the watch and passes them to the player. */
class WearCommandService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (!event.path.startsWith(WearProtocol.COMMAND)) return
        val command = event.path.removePrefix(WearProtocol.COMMAND)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
            when (command) {
                // With nothing loaded, play() resumes the last book (the session's playback resumption).
                WearProtocol.TOGGLE -> if (controller.isPlaying) controller.pause() else controller.play()
                WearProtocol.BACK -> controller.seekBack()
                WearProtocol.FORWARD -> controller.seekForward()
            }
            controller.release()
        }, ContextCompat.getMainExecutor(this))
    }
}
