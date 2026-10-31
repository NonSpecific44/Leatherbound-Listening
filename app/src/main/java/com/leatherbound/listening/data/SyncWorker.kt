package com.leatherbound.listening.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.leatherbound.listening.container
import java.util.concurrent.TimeUnit

/** Keeps the library, covers and listening positions current while the app is closed. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        applicationContext.container.repository.syncAll(force = true)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("library-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
