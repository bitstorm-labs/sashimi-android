package dev.bitstorm.sashimi.core.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import dev.bitstorm.sashimi.core.playback.LinkKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connectivity StateFlow, the Android analogue of the Swift `NetworkMonitor`.
 * Compose-free (:core discipline). Emits whether the device currently has a
 * validated internet-capable network; the UI switches to offline mode off this.
 */
class NetworkMonitor(
    context: Context,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isMetered = MutableStateFlow(currentlyMetered())

    /**
     * Whether the current network is billed by the byte (mobile data, a metered
     * hotspot). The analogue of iOS `isExpensive`: optional background traffic,
     * like a detail screen's hero trailer, is skipped on it.
     */
    val isMetered: StateFlow<Boolean> = _isMetered.asStateFlow()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                update()
            }

            override fun onLost(network: Network) {
                update()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) {
                update()
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
                if (blocked) _isOnline.value = false else update()
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
        update()
    }

    private fun update() {
        _isOnline.value = currentlyOnline()
        _isMetered.value = currentlyMetered()
    }

    /** Unknown counts as metered, so nothing optional streams on a guess. */
    private fun currentlyMetered(): Boolean {
        val network = connectivityManager.activeNetwork ?: return true
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return true
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
        // An unlimited 5G plan marks itself temporarily unmetered (API 30+).
        return !(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        )
    }

    /**
     * The link playback runs over now, for adaptive Auto: Ethernet, metered,
     * or anything else (Wi-Fi). Read fresh on each call, since a phone moves
     * between Wi-Fi and mobile data mid-episode. Unknown counts as metered.
     */
    fun linkKind(): LinkKind {
        val caps = connectivityManager.activeNetwork?.let(connectivityManager::getNetworkCapabilities)
        return LinkKind.classify(
            isEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
            isMetered = currentlyMetered(),
        )
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
