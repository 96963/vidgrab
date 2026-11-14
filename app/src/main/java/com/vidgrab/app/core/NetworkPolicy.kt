package com.vidgrab.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** 仅 Wi-Fi / 网络可用性判断；不申请额外权限，只读当前网络能力。 */
object NetworkPolicy {

    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Wi-Fi / 以太网视为不计流量；蜂窝网络返回 false。 */
    fun isWifiLike(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** 监听网络变化；回到 Wi-Fi 时回调 true。 */
    fun observe(context: Context, onChange: (wifiLike: Boolean) -> Unit): ConnectivityManager.NetworkCallback? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onChange(isWifiLike(context))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                onChange(
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
            }
        }
        return runCatching { manager.registerDefaultNetworkCallback(callback); callback }.getOrNull()
    }
}
