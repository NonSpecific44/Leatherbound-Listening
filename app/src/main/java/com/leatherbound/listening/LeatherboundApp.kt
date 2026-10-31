package com.leatherbound.listening

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.leatherbound.listening.data.Downloads
import com.leatherbound.listening.data.LibraryRepository
import com.leatherbound.listening.data.NetworkMonitor
import com.leatherbound.listening.data.SettingsStore
import com.leatherbound.listening.data.SyncWorker
import com.leatherbound.listening.data.db.AppDatabase
import com.leatherbound.listening.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val database = AppDatabase.create(context)
    val repository = LibraryRepository(context, database, settings)
    val network = NetworkMonitor(context)
    val player = PlayerConnection(context, repository, settings, scope)
}

class LeatherboundApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncWorker.schedule(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Downloads.CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }
}

val Context.container: AppContainer get() = (applicationContext as LeatherboundApp).container
