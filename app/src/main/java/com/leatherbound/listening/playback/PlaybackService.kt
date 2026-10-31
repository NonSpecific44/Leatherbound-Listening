package com.leatherbound.listening.playback

import android.app.PendingIntent
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.leatherbound.listening.MainActivity
import com.leatherbound.listening.R
import com.leatherbound.listening.container
import com.leatherbound.listening.data.db.BookEntity
import com.leatherbound.listening.data.db.ChapterEntity
import com.leatherbound.listening.widget.NowPlayingWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.sqrt

/**
 * Background playback. Serves the in-app player, the notification / lock screen, Bluetooth buttons,
 * the home-screen widget and Android Auto through one Media3 library session.
 */
class PlaybackService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appScope by lazy { container.scope }
    private val repository by lazy { container.repository }
    private val settings by lazy { container.settings }
    private val dao get() = repository.dao

    private lateinit var exo: ExoPlayer
    private lateinit var player: AudiobookPlayer
    private lateinit var session: MediaLibrarySession

    private var unsavedListeningMs = 0L
    private var lastBookId: String? = null

    // Chapters of the current book, kept fresh from the database (Plex chapters can arrive late).
    private var chapters: List<ChapterEntity> = emptyList()
    private var chaptersJob: Job? = null
    private var shownChapter = -1

    private var enhancer: LoudnessEnhancer? = null
    private var voiceBoost = false

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SKIP_MS)
            .setSeekForwardIncrementMs(SKIP_MS)
            .build()
        player = AudiobookPlayer(exo).apply { skipMs = SKIP_MS }
        player.addListener(PlayerListener())

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .setCustomLayout(customLayout)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_book_small) }
        )

        scope.launch {
            settings.settings.collectLatest { s ->
                player.resumeRewindMs = s.resumeRewindSeconds * 1000L
                player.smartRewind = s.smartRewind
                exo.skipSilenceEnabled = s.skipSilence
                if (voiceBoost != s.voiceBoost) {
                    voiceBoost = s.voiceBoost
                    applyVoiceBoost()
                }
                applySpeed()
            }
        }
        scope.launch { tick() }
        // When a Plex server moves to another address (you left home or came back), switch the
        // book that's streaming over to it without losing your place.
        scope.launch {
            dao.observeSources().map { list -> list.associate { it.id to it.baseUrl } }
                .distinctUntilChanged().drop(1).collect { restream(force = false) }
        }
    }

    /**
     * Reloads the current book's streams from its server's current address, keeping the position
     * and whether it was playing. Without [force], only does so if the address has changed.
     * Returns false if there was nothing to reload (a downloaded or local book).
     */
    private suspend fun restream(force: Boolean): Boolean {
        val bookId = player.currentBookId ?: return false
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return false
        if (uri.scheme != "http" && uri.scheme != "https") return false
        val book = dao.book(bookId) ?: return false
        val source = dao.source(book.sourceId) ?: return false
        if (!force && uri.toString().startsWith(source.baseUrl)) return true
        val tracks = dao.tracks(bookId)
        val (index, offset) = tracks.locate(player.bookPositionMs)
        val resume = player.playWhenReady
        player.skipNextRewind = true
        player.setMediaItems(trackItems(book, tracks, repository.tokenFor(book.sourceId)), index, offset)
        player.prepare()
        if (resume) player.play()
        return true
    }

    private var lastRetryAt = 0L

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    /** Closing the app pauses the book, so the next launch opens paused at the saved place. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        player.pause()
        runBlocking { saveProgress(forcePush = true) }
        stopSelf()
    }

    override fun onDestroy() {
        // Last chance to remember where we were.
        runBlocking { saveProgress(forcePush = true) }
        stopShakeDetection()
        enhancer?.release()
        refreshWidget(playing = false)
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    // ---------------- Progress, stats and the notification's chapter title ----------------

    private suspend fun tick() {
        var seconds = 0
        while (scope.isActive) {
            delay(1000)
            if (player.isPlaying) {
                unsavedListeningMs += 1000
                if (++seconds % 10 == 0) {
                    saveProgress(forcePush = false)
                    maybeDownloadNext()
                }
                showChapterTitle()
            }
        }
    }

    private suspend fun saveProgress(forcePush: Boolean) {
        val bookId = player.currentBookId ?: return
        val position = player.bookPositionMs
        val playing = player.isPlaying
        val listened = unsavedListeningMs.also { unsavedListeningMs = 0 }
        if (player.playbackState != Player.STATE_ENDED) repository.savePosition(bookId, position)
        repository.recordListening(bookId, listened)
        // Network calls run in the app scope so they finish even as the service shuts down.
        appScope.launch { repository.pushProgress(bookId, position, playing, forcePush) }
    }

    private fun chapterAt(positionMs: Long): ChapterEntity? = chapters.lastOrNull { it.startMs <= positionMs }

    /** Puts the current chapter's name in the notification, lock screen and Android Auto. */
    private fun showChapterTitle() {
        if (chapters.size < 2) return
        val chapter = chapterAt(player.bookPositionMs) ?: return
        if (chapter.index == shownChapter) return
        shownChapter = chapter.index
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val meta = item.mediaMetadata
        val updated = meta.buildUpon()
            .setTitle(chapter.title)
            .setArtist(listOfNotNull(meta.albumTitle, meta.albumArtist).joinToString(" · "))
            .setSubtitle("Chapter ${chapter.index + 1} of ${chapters.size}")
            .build()
        // Same URI, so ExoPlayer swaps the metadata without interrupting playback.
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(updated).build())
    }

    private fun onBookChanged(bookId: String?) {
        shownChapter = -1
        chapters = emptyList()
        chaptersJob?.cancel()
        if (bookId == null) return
        chaptersJob = scope.launch {
            dao.observeChapters(bookId).collectLatest {
                chapters = it
                shownChapter = -1
                showChapterTitle()
            }
        }
        applySpeed()
    }

    /** Books whose successor has already been considered for auto-download this session. */
    private val nextDownloadChecked = mutableSetOf<String>()

    /** At 80% through a book, fetch the next one (just that one) so it's ready offline. */
    private fun maybeDownloadNext() {
        val bookId = player.currentBookId ?: return
        val duration = player.bookDurationMs
        if (duration <= 0 || bookId in nextDownloadChecked) return
        if (player.bookPositionMs < duration * NEXT_DOWNLOAD_AT) return
        nextDownloadChecked += bookId
        appScope.launch { dao.book(bookId)?.let { repository.downloadNextIfWanted(it) } }
    }

    /** The book's own speed if it has one, otherwise the default. */
    private fun applySpeed() {
        val bookId = player.currentBookId
        scope.launch {
            val speed = bookId?.let { dao.book(it)?.playbackSpeed } ?: settings.current().playbackSpeed
            if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
        }
    }

    private fun applyVoiceBoost() {
        enhancer?.release()
        enhancer = null
        if (!voiceBoost) return
        enhancer = runCatching {
            LoudnessEnhancer(exo.audioSessionId).apply {
                setTargetGain(VOICE_BOOST_MB)
                enabled = true
            }
        }.getOrNull()
    }

    private fun refreshWidget(playing: Boolean = player.isPlaying) {
        val bookId = player.currentBookId ?: lastBookId
        val app = applicationContext
        appScope.launch { NowPlayingWidget.update(app, bookId, playing) }
    }

    private inner class PlayerListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) scope.launch { saveProgress(forcePush = true) }
            refreshWidget(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val bookId = player.currentBookId
            if (bookId != lastBookId) {
                // Switching books: flush listening time to the book that was playing.
                val previous = lastBookId
                val listened = unsavedListeningMs.also { unsavedListeningMs = 0 }
                if (previous != null) scope.launch { repository.recordListening(previous, listened) }
                lastBookId = bookId
                onBookChanged(bookId)
                refreshWidget()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) scope.launch { onBookEnded() }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = applyVoiceBoost()

        override fun onPlayerError(error: PlaybackException) {
            val offline = !container.network.online.value
            // A stream that drops (usually the old address after you leave home): find the server's
            // current address and carry on from the same spot. Once only, so a dead server can't loop.
            val now = SystemClock.elapsedRealtime()
            if (!offline && now - lastRetryAt > 30_000) {
                lastRetryAt = now
                scope.launch {
                    runCatching { repository.refreshConnections() }
                    if (!restream(force = true)) showError(error, offline)
                }
                return
            }
            showError(error, offline)
        }

        private fun showError(error: PlaybackException, offline: Boolean) {
            Toast.makeText(
                this@PlaybackService,
                if (offline) "You're offline, and this book isn't downloaded" else "Couldn't play: ${error.errorCodeName}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private suspend fun onBookEnded() {
        val bookId = player.currentBookId ?: return
        repository.recordListening(bookId, unsavedListeningMs.also { unsavedListeningMs = 0 })
        val book = dao.book(bookId) ?: return
        val s = settings.current()
        if (s.autoMarkFinished) repository.setFinished(bookId, true)
        else repository.savePosition(bookId, book.durationMs)
        if (sleepAtChapterEnd) {
            // The book ending counts as the end of the chapter.
            clearSleepTimer()
            return
        }
        if (s.autoQueueSeries) {
            val next = repository.nextAfter(book) ?: return
            val resolved = resolve(next, startMs = null, restore = false)
            player.setMediaItems(resolved.mediaItems, resolved.startIndex, resolved.startPositionMs)
            player.prepare()
            player.skipNextRewind = true
            player.play()
        }
    }

    // ---------------- Sleep timer ----------------

    /** Counts down only while audio is playing. */
    private var sleepRemainingMs: Long? = null
    private var sleepAtChapterEnd = false
    private var sleepExpiredAt = 0L
    private var sleepJob: Job? = null

    /** [minutes] > 0 starts a timer, [SLEEP_END_OF_CHAPTER] stops at the chapter's end, 0 cancels. */
    private fun setSleepTimer(minutes: Int) {
        clearSleepTimer()
        when {
            minutes > 0 -> sleepRemainingMs = minutes * 60_000L
            minutes == SLEEP_END_OF_CHAPTER -> sleepAtChapterEnd = true
            else -> return
        }
        startShakeDetection()
        sleepJob = scope.launch { runSleepTimer() }
        publishSleep()
    }

    private fun clearSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepRemainingMs = null
        sleepAtChapterEnd = false
        player.volume = 1f
        publishSleep()
    }

    private fun sleepLeftMs(): Long? {
        sleepRemainingMs?.let { return it }
        if (!sleepAtChapterEnd) return null
        val position = player.bookPositionMs
        val end = chapterAt(position)?.endMs ?: player.bookDurationMs
        return ((end - position) / player.playbackParameters.speed).toLong()
    }

    private suspend fun runSleepTimer() {
        var last = SystemClock.elapsedRealtime()
        var lastPublished = 0L
        while (true) {
            delay(250)
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - last
            last = now
            if (!player.isPlaying) continue
            sleepRemainingMs = sleepRemainingMs?.minus(elapsed)
            val left = sleepLeftMs() ?: return
            if (left <= 0) {
                player.pause()
                sleepExpiredAt = now
                sleepRemainingMs = null
                sleepAtChapterEnd = false
                player.volume = 1f
                publishSleep()
                // Keep listening for a shake for a minute, in case you weren't asleep after all.
                delay(SHAKE_GRACE_MS)
                stopShakeDetection()
                return
            }
            // Fade out over the last few seconds rather than cutting off mid-sentence.
            player.volume = if (left < FADE_MS) (left.toFloat() / FADE_MS).coerceIn(0.05f, 1f) else 1f
            if (now - lastPublished >= 1000) {
                publishSleep()
                lastPublished = now
            }
        }
    }

    private fun publishSleep() {
        session.setSessionExtras(
            bundleOf(
                EXTRA_SLEEP_LEFT_MS to (sleepLeftMs() ?: -1L),
                EXTRA_SLEEP_CHAPTER_END to sleepAtChapterEnd,
            )
        )
    }

    private var sensorManager: SensorManager? = null
    private var lastShakeAt = 0L
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = event.values
            val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
            val now = SystemClock.elapsedRealtime()
            if (g < SHAKE_G || now - lastShakeAt < 1500) return
            lastShakeAt = now
            onShake()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun startShakeDetection() {
        if (sensorManager != null) return
        val sm = getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(shakeListener, sensor, SensorManager.SENSOR_DELAY_UI)
        sensorManager = sm
    }

    private fun stopShakeDetection() {
        sensorManager?.unregisterListener(shakeListener)
        sensorManager = null
    }

    /** Shaking the phone adds ten minutes, or restarts the timer if it just ran out. */
    private fun onShake() {
        val justExpired = sleepJob?.isActive == true && sleepRemainingMs == null && !sleepAtChapterEnd &&
            SystemClock.elapsedRealtime() - sleepExpiredAt < SHAKE_GRACE_MS
        when {
            justExpired -> {
                setSleepTimer(SHAKE_EXTEND_MIN)
                player.skipNextRewind = true
                player.play()
            }
            sleepRemainingMs != null || sleepAtChapterEnd -> {
                val left = sleepLeftMs() ?: 0
                sleepAtChapterEnd = false
                sleepRemainingMs = left + SHAKE_EXTEND_MIN * 60_000L
                player.volume = 1f
                publishSleep()
            }
            else -> return
        }
        Toast.makeText(this, "Sleep timer: +$SHAKE_EXTEND_MIN minutes", Toast.LENGTH_SHORT).show()
    }

    // ---------------- Resolving books into playable files ----------------

    private suspend fun resolve(book: BookEntity, startMs: Long?, restore: Boolean): MediaSession.MediaItemsWithStartPosition {
        // Chapters only affect the UI, so fetch them alongside playback rather than before it.
        if (!book.detailsLoaded) scope.launch { repository.ensureDetails(book.id) }
        val tracks = dao.tracks(book.id)
        val token = repository.tokenFor(book.sourceId)
        val saved = if (book.isFinished) 0L else book.positionMs
        val pausedFor = if (book.lastPlayedAt > 0) System.currentTimeMillis() - book.lastPlayedAt else -1
        var start = startMs ?: saved
        if (restore) start -= player.rewindFor(pausedFor)
        val (index, offset) = tracks.locate(start.coerceAtLeast(0))
        player.pausedAtMs = book.lastPlayedAt
        player.skipNextRewind = restore || startMs != null
        return MediaSession.MediaItemsWithStartPosition(trackItems(book, tracks, token), index, offset)
    }

    /** Resolves a "book:" id, a voice search, or an empty request (resume the last book). */
    private suspend fun findBook(item: MediaItem): BookEntity? {
        MediaIds.bookIdOf(item.mediaId)?.let { return dao.book(it) }
        val query = item.requestMetadata.searchQuery
        if (!query.isNullOrBlank()) return dao.search(query).firstOrNull()
        return if (item.mediaId.isBlank()) dao.lastPlayed() else null
    }

    private suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        MediaIds.ROOT -> listOf(
            folderItem(MediaIds.CONTINUE, "Jump back in"),
            folderItem(MediaIds.LIST, "Listening list"),
            folderItem(MediaIds.LIBRARY, "Library"),
            folderItem(MediaIds.DOWNLOADED, "Downloaded"),
        )
        MediaIds.CONTINUE -> dao.inProgress(20).map { it.toBrowsableItem() }
        MediaIds.LIST -> dao.listeningList().map { it.toBrowsableItem() }
        MediaIds.LIBRARY -> dao.recentlyAdded(500).sortedBy { it.sortTitle.lowercase() }.map { it.toBrowsableItem() }
        MediaIds.DOWNLOADED -> dao.downloaded().map { it.toBrowsableItem() }
        else -> emptyList()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(CMD_BACK).add(CMD_FORWARD).add(CMD_BOOKMARK).add(CMD_SLEEP).build()
            // The notification controller also decides what the lock screen and Android Auto show:
            // drop previous/next so the ±15 s buttons take those slots.
            val systemUi = session.isMediaNotificationController(controller) ||
                session.isAutoCompanionController(controller) ||
                session.isAutomotiveController(controller)
            val playerCommands = if (systemUi) {
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .removeAll(
                        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    ).build()
            } else MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(customLayout)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) = publishSleep()

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_BACK -> player.seekBack()
                ACTION_FORWARD -> player.seekForward()
                ACTION_SLEEP -> setSleepTimer(args.getInt(ARG_MINUTES, 0))
                ACTION_BOOKMARK -> {
                    val bookId = player.currentBookId
                        ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                    val position = player.bookPositionMs
                    val note = args.getString(ARG_NOTE).orEmpty()
                    scope.launch { repository.addBookmark(bookId, position, note) }
                    Toast.makeText(this@PlaybackService, "Bookmark added", Toast.LENGTH_SHORT).show()
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Ask Android Auto to show books as a grid of covers.
            val rootParams = LibraryParams.Builder().setExtras(
                bundleOf(
                    "android.media.browse.CONTENT_STYLE_SUPPORTED" to true,
                    "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT" to 2,
                    "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT" to 1,
                )
            ).build()
            return Futures.immediateFuture(LibraryResult.ofItem(folderItem(MediaIds.ROOT, "Leatherbound"), rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = children(parentId)
            val paged = if (pageSize in 1 until Int.MAX_VALUE) all.drop(page * pageSize).take(pageSize) else all
            LibraryResult.ofItemList(paged, params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val book = MediaIds.bookIdOf(mediaId)?.let { dao.book(it) }
            if (book != null) LibraryResult.ofItem(book.toBrowsableItem(), null)
            else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val request = mediaItems.firstOrNull()
            val book = request?.let { findBook(it) }
            if (book == null) {
                player.skipNextRewind = false
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            } else {
                val extras = request.requestMetadata.extras
                val startMs = extras?.getLong(MediaIds.EXTRA_START_MS, -1L)?.takeIf { it >= 0 }
                resolve(book, startMs, restore = extras?.getBoolean(MediaIds.EXTRA_RESTORE) == true)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.flatMap { item ->
                if (item.localConfiguration != null) listOf(item)
                else findBook(item)?.let { trackItems(it, dao.tracks(it.id), repository.tokenFor(it.sourceId)) }.orEmpty()
            }.toMutableList()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val book = dao.lastPlayed() ?: throw IllegalStateException("Nothing to resume")
            resolve(book, startMs = null, restore = false)
        }
    }

    companion object {
        const val SKIP_MS = 15_000L
        private const val NEXT_DOWNLOAD_AT = 0.8
        const val SLEEP_END_OF_CHAPTER = -1
        const val EXTRA_SLEEP_LEFT_MS = "sleepLeftMs"
        const val EXTRA_SLEEP_CHAPTER_END = "sleepChapterEnd"
        const val ARG_MINUTES = "minutes"
        const val ARG_NOTE = "note"
        const val ACTION_SLEEP = "leatherbound.SLEEP"
        const val ACTION_BOOKMARK = "leatherbound.BOOKMARK"
        private const val ACTION_BACK = "leatherbound.BACK_15"
        private const val ACTION_FORWARD = "leatherbound.FORWARD_15"
        private const val FADE_MS = 10_000L
        private const val SHAKE_G = 2.4f
        private const val SHAKE_EXTEND_MIN = 10
        private const val SHAKE_GRACE_MS = 60_000L
        /** Loudness gain for "voice boost", in millibels. */
        private const val VOICE_BOOST_MB = 800

        private val CMD_BACK = SessionCommand(ACTION_BACK, Bundle.EMPTY)
        private val CMD_FORWARD = SessionCommand(ACTION_FORWARD, Bundle.EMPTY)
        val CMD_BOOKMARK = SessionCommand(ACTION_BOOKMARK, Bundle.EMPTY)
        val CMD_SLEEP = SessionCommand(ACTION_SLEEP, Bundle.EMPTY)

        @Suppress("DEPRECATION")
        private val customLayout = ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName("Back 15 seconds")
                .setIconResId(R.drawable.ic_replay_15)
                .setSessionCommand(CMD_BACK)
                .build(),
            CommandButton.Builder()
                .setDisplayName("Forward 15 seconds")
                .setIconResId(R.drawable.ic_forward_15)
                .setSessionCommand(CMD_FORWARD)
                .build(),
            CommandButton.Builder()
                .setDisplayName("Add bookmark")
                .setIconResId(R.drawable.ic_bookmark_add)
                .setSessionCommand(CMD_BOOKMARK)
                .build(),
        )
    }
}
