package com.leatherbound.listening.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.leatherbound.listening.data.LibraryRepository
import com.leatherbound.listening.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerState(
    val bookId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    /** Milliseconds until the sleep timer pauses playback, or null if it's off. */
    val sleepLeftMs: Long? = null,
    val sleepAtChapterEnd: Boolean = false,
)

/** The app's handle on [PlaybackService]: a MediaController plus a state flow the UI can collect. */
class PlayerConnection(
    private val context: Context,
    private val repository: LibraryRepository,
    private val settings: SettingsStore,
    private val appScope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private var pollJob: Job? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    /** Called from MainActivity.onStart. On a cold start, loads the last book a few seconds early, paused. */
    fun connect() {
        if (controller != null) return
        appScope.launch(Dispatchers.Main) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val c = MediaController.Builder(context, token)
                .setListener(object : MediaController.Listener {
                    override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                        sleepExtras = extras
                        publish()
                    }
                })
                .buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()
            })
            if (c.mediaItemCount == 0) {
                repository.dao.lastPlayed()?.takeIf { !it.isFinished }?.let { book ->
                    c.playWhenReady = false
                    c.setMediaItem(request(book.id, restore = true))
                    c.prepare()
                }
            }
            publish()
            pollJob = launch {
                while (isActive) {
                    if (c.isPlaying) publish()
                    delay(500)
                }
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        controller?.release()
        controller = null
    }

    private var sleepExtras: Bundle = Bundle.EMPTY

    private fun publish() {
        val c = controller ?: return
        val sleepLeft = sleepExtras.getLong(PlaybackService.EXTRA_SLEEP_LEFT_MS, -1L).takeIf { it >= 0 }
        _state.value = PlayerState(
            bookId = c.currentBookId,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            // In the car the session reports chapter time, and says where that chapter starts.
            positionMs = sleepExtras.getLong(PlaybackService.EXTRA_REPORTED_FROM_MS, -1L).takeIf { it >= 0 }
                ?.let { it + c.currentPosition.coerceAtLeast(0) } ?: c.bookPositionMs,
            durationMs = c.bookDurationMs,
            speed = c.playbackParameters.speed,
            sleepLeftMs = sleepLeft,
            sleepAtChapterEnd = sleepExtras.getBoolean(PlaybackService.EXTRA_SLEEP_CHAPTER_END),
        )
    }

    private fun request(bookId: String, startMs: Long? = null, restore: Boolean = false) = MediaItem.Builder()
        .setMediaId(MediaIds.book(bookId))
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setExtras(
                bundleOf(MediaIds.EXTRA_START_MS to (startMs ?: -1L), MediaIds.EXTRA_RESTORE to restore)
            ).build()
        )
        .build()

    /** Plays a book from its saved position (minus the resume rewind), or from [startMs]. */
    fun play(bookId: String, startMs: Long? = null) {
        val c = controller ?: return connect()
        if (c.currentBookId == bookId && startMs == null) {
            c.play()
            return
        }
        c.setMediaItem(request(bookId, startMs))
        c.prepare()
        c.play()
    }

    /** Jumps within the current book, or starts [bookId] at that point. */
    fun seekTo(bookId: String, positionMs: Long) {
        val c = controller ?: return
        if (c.currentBookId == bookId) c.seekToBookPosition(positionMs) else play(bookId, positionMs)
        publish()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekBack() { controller?.seekBack(); publish() }
    fun seekForward() { controller?.seekForward(); publish() }

    /** Remembered for the current book only; Settings holds the default. */
    fun setSpeed(speed: Float) {
        val c = controller ?: return
        c.playbackParameters = PlaybackParameters(speed)
        val bookId = c.currentBookId ?: return
        appScope.launch { repository.setBookSpeed(bookId, speed) }
    }

    /** Minutes, [PlaybackService.SLEEP_END_OF_CHAPTER], or 0 to cancel. */
    fun setSleepTimer(minutes: Int) {
        controller?.sendCustomCommand(PlaybackService.CMD_SLEEP, bundleOf(PlaybackService.ARG_MINUTES to minutes))
    }

    fun addBookmark(note: String) {
        controller?.sendCustomCommand(PlaybackService.CMD_BOOKMARK, bundleOf(PlaybackService.ARG_NOTE to note))
    }

    fun stopIfPlaying(bookId: String) {
        val c = controller ?: return
        if (c.currentBookId == bookId) {
            c.stop()
            c.clearMediaItems()
            publish()
        }
    }
}
