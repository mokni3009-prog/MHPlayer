package com.mhplayer.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connectivity awareness for the player (report §5.9).
 *
 * Wraps [ConnectivityManager.NetworkCallback] and exposes the connection state
 * as a [StateFlow]. [onNetworkRegained] is invoked (on the main thread) only
 * on a genuine false → true transition, never for the initial state.
 *
 * Lifecycle: call [start] when the player starts being used and [stop] when it
 * is released. The callback is delivered by the system on the main thread;
 * [isConnected] itself is safe to read from any thread.
 */
class NetworkMonitor(
    context: Context,
    private val onNetworkRegained: (() -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isConnected = MutableStateFlow(isCurrentlyConnected())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateConnected(true)
        }

        override fun onLost(network: Network) {
            updateConnected(false)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateConnected(
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            )
        }
    }

    private fun isCurrentlyConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun updateConnected(connected: Boolean) {
        val wasConnected = _isConnected.value
        if (wasConnected == connected) return
        _isConnected.value = connected
        if (connected && !wasConnected) {
            mainHandler.post { onNetworkRegained?.invoke() }
        }
    }

    /** Registers the [ConnectivityManager.NetworkCallback]. Safe to call twice. */
    fun start() {
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // Already registered, or no permission — state flow keeps last known value.
        }
    }

    /** Unregisters the callback. Safe to call twice. */
    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // Was not registered.
        }
    }
}
