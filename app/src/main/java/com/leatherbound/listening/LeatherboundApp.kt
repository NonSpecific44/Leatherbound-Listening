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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val database = AppDatabase.create(context)
    val repository = LibraryRepository(context, database, settings)
    val network = NetworkMonitor(context)
    val player = PlayerConnection(context, repository, settings, scope)

    init {
        // Leaving or coming home changes the network: re-pick each Plex server's address, so
        // you stream over the home network when you're on it and over the internet when you're not.
        // The short wait lets a new network finish connecting before it is probed. Anything saved
        // while offline (positions, finished books) is sent once there's a connection again.
        scope.launch {
            network.current.drop(1).filterNotNull().debounce(2_000).collect {
                runCatching { repository.refreshConnections() }
                runCatching { repository.flushPending() }
            }
        }
    }
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
