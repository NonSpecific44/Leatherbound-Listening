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
            var doneMs = 0L
            return try {
                for (track in tracks) {
                    val existing = track.localPath?.let(::File)
                    if (existing == null || !existing.exists()) {
                        val ext = track.container?.takeIf { it.length in 2..4 } ?: "audio"
                        val target = File(folder, "%03d.%s".format(track.index, ext))
                        download(TokenVault.fill(track.url, token), target) { fraction ->
                            val overall = (doneMs + track.durationMs * fraction) / totalMs
                            dao.setDownload(bookId, DownloadState.DOWNLOADING, overall.toFloat())
                            runCatching { setForeground(foreground(book.title, (overall * 100).toInt())) }
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
            }
        }

        private suspend fun download(url: String, target: File, onProgress: suspend (Double) -> Unit) {
            val partial = File(target.path + ".part")
            withContext(Dispatchers.IO) {
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty response")
                    val length = body.contentLength()
                    var read = 0L
                    var lastReported = 0L
                    partial.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (isStopped) throw IOException("Cancelled")
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                read += n
                                if (length > 0 && read - lastReported > length / 100) {
                                    lastReported = read
                                    onProgress(read.toDouble() / length)
                                }
                            }
                        }
                    }
                }
            }
            if (!partial.renameTo(target)) throw IOException("Could not save ${target.name}")
        }
    }
}
