package dev.jdtech.jellyfin.utils

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import javax.inject.Inject

/**
 * Connectivity check backed by [ConnectivityManager]. Reports online when there is an active
 * network that advertises internet capability — which reliably covers the "no network at all"
 * case (Wi-Fi off). A network that is connected but has no working internet is a rarer edge case
 * the startup check does not try to catch.
 */
class NetworkConnectivityImpl
@Inject
constructor(private val application: Application) : NetworkConnectivity {
    override fun isOnline(): Boolean {
        val connectivityManager =
            application.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
