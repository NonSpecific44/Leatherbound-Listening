package com.leatherbound.listening.data

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.leatherbound.listening.LeatherboundApp
import com.leatherbound.listening.R
import com.leatherbound.listening.data.db.DownloadState
import com.leatherbound.listening.data.remote.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException

object Downloads {
    const val CHANNEL = "downloads"
    private const val KEY_BOOK = "book_id"

    fun dir(context: Context, bookId: String): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "books/" + bookId.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    suspend fun enqueue(context: Context, bookId: String, wifiOnly: Boolean) {
        val app = context.applicationContext as LeatherboundApp
        app.container.repository.dao.setDownload(bookId, DownloadState.QUEUED, 0f)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(KEY_BOOK to bookId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("download-$bookId", ExistingWorkPolicy.KEEP, request)
    }

    suspend fun remove(context: Context, bookId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("download-$bookId")
        val dao = (context.applicationContext as LeatherboundApp).container.repository.dao
        dao.tracks(bookId).forEach { dao.setTrackLocalPath(bookId, it.index, null) }
        dao.setDownload(bookId, DownloadState.NONE, 0f)
        deleteFiles(context, bookId)
    }

    /** Live numbers for downloads in progress, for the Downloads page. */
    data class Transfer(
        val bytesDone: Long,
        /** Null when the server didn't say how big the files are. */
        val bytesTotal: Long?,
        val bytesPerSecond: Long,
        val file: Int,
        val fileCount: Int,
    )

    private val transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    val activeTransfers: StateFlow<Map<String, Transfer>> = transfers

    /** Counts bytes and publishes speed (smoothed) at most twice a second. */
    private class Meter(private val bookId: String, private val total: Long?, private val fileCount: Int) {
        var file = 1
        private var done = 0L
        private var speed = 0.0
        private var windowStart = SystemClock.elapsedRealtime()
        private var windowBytes = 0L

        fun add(bytes: Long) {
            done += bytes
            windowBytes += bytes
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - windowStart
            if (elapsed < 500) return
            val instant = windowBytes * 1000.0 / elapsed
            speed = if (speed == 0.0) instant else speed * 0.7 + instant * 0.3
            windowStart = now
            windowBytes = 0
            transfers.update { it + (bookId to Transfer(done, total, speed.toLong(), file, fileCount)) }
        }
    }

    fun deleteFiles(context: Context, bookId: String) {
        runCatching { dir(context, bookId).deleteRecursively() }
    }

    class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        private val dao = (context.applicationContext as LeatherboundApp).container.repository.dao

        override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Preparing download", 0)

        private fun foreground(title: String, percent: Int): ForegroundInfo {
            val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_book_small)
                .setContentTitle(title)
                .setProgress(100, percent, percent == 0)
                .setOngoing(true)
                .setSilent(true)
                .build()
            val id = (inputData.getString(KEY_BOOK) ?: "").hashCode()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else ForegroundInfo(id, notification)
        }

        override suspend fun doWork(): Result {
            val bookId = inputData.getString(KEY_BOOK) ?: return Result.failure()
            val book = dao.book(bookId) ?: return Result.failure()
            runCatching { setForeground(foreground(book.title, 0)) }
            val repo = (applicationContext as LeatherboundApp).container.repository
            runCatching { repo.refreshConnections() }
            val token = repo.tokenFor(book.sourceId)
            dao.setDownload(bookId, DownloadState.DOWNLOADING, 0f)
            // Re-read: refreshConnections may have moved the server to a faster address.
            val tracks = dao.tracks(bookId)
            val totalMs = tracks.sumOf { it.durationMs }.coerceAtLeast(1)
            val folder = dir(applicationContext, bookId).apply { mkdirs() }

            // Work out the total size up front (already-saved files + HEAD for the rest) for "120 MB of 450 MB".
            val done = tracks.map { t -> t.localPath?.let(::File)?.takeIf { it.exists() } }
            val sizes = coroutineScope {
                tracks.mapIndexed { i, t ->
                    async { done[i]?.length() ?: contentLength(TokenVault.fill(t.url, token)) }
                }.awaitAll()
            }
            val totalBytes = if (sizes.all { it != null }) sizes.sumOf { it!! } else null
            val meter = Meter(bookId, totalBytes, tracks.size)
            meter.add(done.sumOf { it?.length() ?: 0L })

            var doneMs = 0L
            return try {
                for ((i, track) in tracks.withIndex()) {
                    meter.file = i + 1
                    if (done[i] == null) {
                        val ext = track.container?.takeIf { it.length in 2..4 } ?: "audio"
                        val target = File(folder, "%03d.%s".format(track.index, ext))
                        var lastPercent = -1
                        download(TokenVault.fill(track.url, token), target, meter) { fraction ->
                            val overall = (doneMs + track.durationMs * fraction) / totalMs
                            val percent = (overall * 100).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                dao.setDownload(bookId, DownloadState.DOWNLOADING, overall.toFloat())
                                runCatching { setForeground(foreground(book.title, percent)) }
                            }
                        }
                        dao.setTrackLocalPath(bookId, track.index, target.absolutePath)
                    }
                    doneMs += track.durationMs
                }
                dao.setDownload(bookId, DownloadState.DONE, 1f)
                Result.success()
            } catch (e: Exception) {
                if (runAttemptCount < 3) {
                    dao.setDownload(bookId, DownloadState.QUEUED, doneMs.toFloat() / totalMs)
                    Result.retry()
                } else {
                    dao.setDownload(bookId, DownloadState.FAILED, 0f)
                    Result.failure()
                }
            } finally {
                transfers.update { it - bookId }
            }
        }

        private suspend fun contentLength(url: String): Long? = runCatching {
            withContext(Dispatchers.IO) {
                Http.client.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                    r.header("Content-Length")?.toLongOrNull()?.takeIf { r.isSuccessful && it > 0 }
                }
            }
        }.getOrNull()

        private suspend fun download(url: String, target: File, meter: Meter, onProgress: suspend (Double) -> Unit) {
            val partial = File(target.path + ".part")
            withContext(Dispatchers.IO) {
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty response")
                    val length = body.contentLength()
                    var read = 0L
                    partial.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (isStopped) throw IOException("Cancelled")
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                read += n
                                meter.add(n.toLong())
                                if (length > 0) onProgress(read.toDouble() / length)
                            }
                        }
                    }
                }
            }
            if (!partial.renameTo(target)) throw IOException("Could not save ${target.name}")
        }
    }
}
