package com.comfypilot.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.comfypilot.core.net.NetworkInfo
import com.comfypilot.core.net.NetworkTransport

/**
 * 只上报"平台看到的网络类别"，不做任何重建决策。
 * 是否递增 generation / 是否重建连接，由 commonMain 的 SessionReducer 统一判断——
 * 这样 Android 与鸿蒙不会各写一套相似但不同的逻辑。
 */
class AndroidNetworkMonitor(
    context: Context,
    private val onChanged: (NetworkInfo) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            onChanged(capabilities.toNetworkInfo(network))
        }

        override fun onLost(network: Network) {
            onChanged(NetworkInfo(NetworkTransport.NONE, validated = false, handle = network.toString()))
        }
    }

    fun start() {
        if (registered) return
        registered = true
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }
}

fun NetworkCapabilities.toNetworkInfo(network: Network): NetworkInfo {
    val transport = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
        else -> NetworkTransport.OTHER
    }
    return NetworkInfo(
        transport = transport,
        validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        handle = network.toString(),
    )
}
