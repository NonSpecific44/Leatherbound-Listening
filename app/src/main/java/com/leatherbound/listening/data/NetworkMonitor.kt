package com.leatherbound.listening.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the phone currently has a working internet connection. */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(isOnline(cm.activeNetwork))
    val online: StateFlow<Boolean> = _online

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            override fun onLost(network: Network) {
                _online.value = false
            }
        })
    }

    private fun isOnline(network: Network?) =
        cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
