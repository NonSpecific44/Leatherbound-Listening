package com.leatherbound.listening.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/** How far to step back when you press play again. */
object Rewind {
    /**
     * With [smart] on, the rewind grows with the time away: a brief pause barely moves,
     * coming back the next day gives you half a minute to find the thread. [baseMs] of 0 turns it off.
     */
    fun forPause(baseMs: Long, pausedForMs: Long, smart: Boolean): Long = when {
        baseMs <= 0 -> 0
        !smart || pausedForMs < 0 -> baseMs
        pausedForMs < 60_000 -> minOf(baseMs, 2_000)
        pausedForMs < 60 * 60_000 -> baseMs
        pausedForMs < 24 * 60 * 60_000 -> maxOf(baseMs, 15_000)
        else -> maxOf(baseMs, 30_000)
    }
}

/**
 * Wraps ExoPlayer so that skip back/forward work across file boundaries, and so that resuming after
 * a pause starts a little earlier to pick the thread back up.
 */
class AudiobookPlayer(player: Player) : ForwardingPlayer(player) {

    var skipMs: Long = 15_000
    /**
     * Previous / next (steering wheel, Android Auto, headphones, notification, lock screen) step
     * back and forward through the book by these amounts instead of changing file.
     */
    var buttonBackMs: Long = 30_000
    var buttonForwardMs: Long = 30_000
    var resumeRewindMs: Long = 5_000
    var smartRewind: Boolean = true
    /** Wall-clock time playback last stopped; 0 if unknown. */
    var pausedAtMs: Long = 0
    /** Set when a book was just loaded already rewound (on app launch), so the first play doesn't rewind twice. */
    var skipNextRewind = false

    fun rewindFor(pausedForMs: Long) = Rewind.forPause(resumeRewindMs, pausedForMs, smartRewind)

    override fun seekBack() = seekToBookPosition(bookPositionMs - skipMs)
    override fun seekForward() = seekToBookPosition(bookPositionMs + skipMs)
    override fun getSeekBackIncrement(): Long = skipMs
    override fun getSeekForwardIncrement(): Long = skipMs

    override fun seekToPrevious() = seekToBookPosition(bookPositionMs - buttonBackMs)
    override fun seekToNext() = seekToBookPosition(bookPositionMs + buttonForwardMs)
    override fun seekToPreviousMediaItem() = seekToPrevious()
    override fun seekToNextMediaItem() = seekToNext()

    // A one-file book has no "next file", but previous/next still work here, so always offer them.
    private val buttonCommands = intArrayOf(
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    )

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        return if (mediaItemCount > 0) base.buildUpon().addAll(*buttonCommands).build() else base
    }

    override fun isCommandAvailable(command: Int): Boolean =
        (mediaItemCount > 0 && command in buttonCommands) || super.isCommandAvailable(command)

    override fun play() {
        rewindIfResuming()
        super.play()
    }

    override fun pause() {
        if (playWhenReady) pausedAtMs = System.currentTimeMillis()
        super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) rewindIfResuming() else if (this.playWhenReady) pausedAtMs = System.currentTimeMillis()
        super.setPlayWhenReady(playWhenReady)
    }

    private fun rewindIfResuming() {
        if (playWhenReady || mediaItemCount == 0 || playbackState == Player.STATE_ENDED) return
        if (skipNextRewind) {
            skipNextRewind = false
            return
        }
        val position = bookPositionMs
        val pausedFor = if (pausedAtMs > 0) System.currentTimeMillis() - pausedAtMs else -1
        val rewind = rewindFor(pausedFor)
        if (rewind > 0 && position > 0) seekToBookPosition(position - rewind)
    }
}
