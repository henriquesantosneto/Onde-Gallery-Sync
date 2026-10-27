package com.onegallery.sync.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class NetworkMonitor(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return networkCapabilitiesAreOnline(
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }

    fun isUnmetered(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

/**
 * A marca VALIDATED é a sonda da Google. Em vários Wi‑Fi e operadoras ela fica
 * ausente com a internet funcionando, e o sync não saía do modo local.
 * Rede com internet, sem portal cativo, em Wi‑Fi, dados, cabo ou VPN, conta.
 */
internal fun networkCapabilitiesAreOnline(
    hasInternet: Boolean,
    validated: Boolean,
    captivePortal: Boolean,
    wifi: Boolean,
    cellular: Boolean,
    ethernet: Boolean,
    vpn: Boolean,
): Boolean {
    if (!hasInternet || captivePortal) return false
    if (validated) return true
    return wifi || cellular || ethernet || vpn
}
