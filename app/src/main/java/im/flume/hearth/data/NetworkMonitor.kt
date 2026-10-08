package im.flume.hearth.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import im.flume.hearth.api.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tracks whether the Navidrome server is usable. "Offline" when there is no network, the server
 * didn't answer the last ping (e.g. Tailscale is off), or the user switched on offline mode.
 */
class NetworkMonitor(
    context: Context,
    private val api: SubsonicClient,
    private val session: SessionStore,
    private val scope: CoroutineScope,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val hasNetwork = MutableStateFlow(cm.activeNetwork != null)
    private val serverReachable = MutableStateFlow(true)

    val isOnline: StateFlow<Boolean> =
        combine(hasNetwork, serverReachable, session.settings) { net, server, s -> net && server && !s.offlineMode }
            .stateIn(scope, SharingStarted.Eagerly, true)

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                hasNetwork.value = true
                checkServer()
            }

            override fun onLost(network: Network) {
                hasNetwork.value = cm.activeNetwork != null
            }
        })
        // Notice quickly when Tailscale / the server comes back.
        scope.launch {
            while (true) {
                delay(if (serverReachable.value) 5 * 60_000L else 20_000L)
                if (hasNetwork.value) checkServer()
            }
        }
    }

    /**
     * True when connected through Wi-Fi (or Ethernet), including with a VPN such as Tailscale on top.
     * Android can report a VPN as "metered" even on Wi-Fi, so this looks at the actual transports.
     */
    fun onWifi(): Boolean = cm.allNetworks.any { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@any false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    /** True when the current connection is metered (mobile data). */
    fun isMetered(): Boolean = !onWifi()

    fun checkServer() {
        val creds = session.credentials.value ?: return
        scope.launch {
            serverReachable.value = runCatching { api.ping(creds) }.isSuccess
        }
    }

    fun reportServerFailure() { serverReachable.value = false }
    fun reportServerSuccess() { serverReachable.value = true }
}
