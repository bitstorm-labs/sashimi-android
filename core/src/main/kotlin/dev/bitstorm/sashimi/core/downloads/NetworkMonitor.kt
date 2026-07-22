package dev.bitstorm.sashimi.core.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connectivity StateFlow, the Android analogue of the Swift `NetworkMonitor`.
 * Compose-free (:core discipline). Emits whether the device currently has a
 * validated internet-capable network; the UI switches to offline mode off this.
 */
class NetworkMonitor(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isOnline.value = currentlyOnline()
            }

            override fun onLost(network: Network) {
                _isOnline.value = currentlyOnline()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) {
                _isOnline.value = currentlyOnline()
            }

            /**
             * App-standby / battery restrictions can BLOCK this uid's traffic while
             * the app is backgrounded, then unblock it on foreground. The unblock
             * arrives only through this callback (not onAvailable/onCapabilities),
             * so without handling it the app stayed stuck in offline mode after a
             * minimize→return cycle (reproduced on the Galaxy Fold).
             */
            override fun onBlockedStatusChanged(
                network: Network,
                blocked: Boolean,
            ) {
                _isOnline.value = if (blocked) false else currentlyOnline()
            }
        }

    init {
        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
    }

    /**
     * Re-evaluate connectivity on demand. Called from the Activity's onResume as a
     * belt-and-suspenders re-check so a stale offline state can never survive a
     * foreground, independent of which system callback did or didn't fire.
     */
    fun refresh() {
        _isOnline.value = currentlyOnline()
    }

    private fun currentlyOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        // Require INTERNET but NOT VALIDATED: validation is Google's "reaches the
        // public internet" probe, which lags Wi-Fi wake by up to a minute on the
        // Fold — launching while locked booted the app into offline mode through
        // that lag — and a Jellyfin client only needs the LAN. Mirrors iOS
        // NWPathMonitor .satisfied, which has no internet-validation requirement.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
