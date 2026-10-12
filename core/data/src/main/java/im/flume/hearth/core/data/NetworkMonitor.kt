package im.flume.hearth.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import im.flume.hearth.core.network.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    /** Null until the first ping answers: unknown counts as unreachable, so nothing streams on a guess. */
    private val serverReachable = MutableStateFlow<Boolean?>(null)
    private var checking: Job? = null

    /** Pings in a row that found the server unreachable; sets how long until the next try. */
    @Volatile private var failedPings = 0
    @Volatile private var foreground = false
    @Volatile private var playing = false
    /** Wakes the ping loop early: network back, app opened, playback started, offline mode switched off. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    val isOnline: StateFlow<Boolean> =
        combine(hasNetwork, serverReachable, session.settings) { net, server, s -> net && server == true && !s.offlineMode }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** The user switched on offline mode: nothing may stream, and there's no point pinging. */
    val offlineMode: Boolean get() = session.settings.value.offlineMode

    /** Offline mode, or no network at all: streaming can't work, so fail fast instead of waiting on timeouts. */
    val cannotStream: Boolean get() = offlineMode || !hasNetwork.value

    private val _wifi = MutableStateFlow(onWifi())

    /** Live version of [onWifi], for the UI. */
    val wifi: StateFlow<Boolean> = _wifi

    init {
        // Watches every network, not just the default one: with a VPN on top, Wi-Fi is never the default.
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _wifi.value = onWifi() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _wifi.value = onWifi() }
                override fun onLost(network: Network) { _wifi.value = onWifi(except = network) }
            },
        )
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                hasNetwork.value = true
                checkServer()
                resetBackoff()
            }

            override fun onLost(network: Network) {
                hasNetwork.value = cm.activeNetwork != null
            }
        })
        // Notice when Tailscale / the server comes back: quickly at first, then less and less often.
        scope.launch {
            while (true) {
                if (!PingSchedule.shouldPing(hasNetwork.value, offlineMode, foreground, playing)) {
                    // Nobody needs to know right now: sleep until something changes.
                    wake.receive()
                    continue
                }
                withTimeoutOrNull(PingSchedule.delayMs(serverReachable.value, failedPings)) { wake.receive() }
                if (PingSchedule.shouldPing(hasNetwork.value, offlineMode, foreground, playing)) {
                    checkServer()
                    checking?.join() // so the next wait already counts this ping
                }
            }
        }
        scope.launch {
            session.settings.map { it.offlineMode }.distinctUntilChanged().drop(1).collect { resetBackoff() }
        }
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { foreground = true; resetBackoff() }
                override fun onStop(owner: LifecycleOwner) { foreground = false }
            })
        }
    }

    /** Try again soon, from the shortest wait. */
    private fun resetBackoff() {
        failedPings = 0
        wake.trySend(Unit)
    }

    /**
     * The player says whether something is playing. In the background the server is only pinged
     * while it is, so a phone in a pocket with Tailscale off doesn't keep waking up to ask.
     */
    fun setPlaying(isPlaying: Boolean) {
        if (playing == isPlaying) return
        playing = isPlaying
        if (isPlaying) resetBackoff()
    }

    /**
     * True when connected through Wi-Fi (or Ethernet), including with a VPN such as Tailscale on top.
     * Android can report a VPN as "metered" even on Wi-Fi, so this looks at the actual transports.
     */
    fun onWifi(): Boolean = onWifi(except = null)

    private fun onWifi(except: Network?): Boolean = cm.allNetworks.any { n ->
        n != except && isWifi(cm.getNetworkCapabilities(n))
    }

    /** True when the current connection is metered (mobile data). */
    fun isMetered(): Boolean = !onWifi()

    fun checkServer() {
        val creds = session.credentials.value ?: return
        if (checking?.isActive == true) return
        checking = scope.launch {
            val ok = runCatching { api.ping(creds) }.isSuccess
            failedPings = if (ok) 0 else failedPings + 1
            serverReachable.value = ok
        }
    }

    /**
     * Waits (up to [timeoutMs]) for an answer when it isn't known yet whether the server is reachable,
     * e.g. right after start, so a shuffle isn't built from a guess. Returns [isOnline].
     */
    suspend fun awaitServerKnown(timeoutMs: Long = 5_000): Boolean {
        if (serverReachable.value == null && hasNetwork.value && !offlineMode) {
            checkServer()
            withTimeoutOrNull(timeoutMs) { serverReachable.first { it != null } }
        }
        return isOnline.value
    }

    fun reportServerFailure() { serverReachable.value = false }
    fun reportServerSuccess() { serverReachable.value = true }

    companion object {
        fun isWifi(caps: NetworkCapabilities?): Boolean = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
}

/** When to ask the server whether it's reachable. Pure, so the timings can be tested. */
internal object PingSchedule {
    /** While the server answers: just a check now and then that it still does. */
    const val REACHABLE_MS = 5 * 60_000L
    const val FIRST_RETRY_MS = 20_000L
    const val MAX_RETRY_MS = 10 * 60_000L

    /**
     * Wait before the next ping. While unreachable it doubles with each failed ping (20 s, 40 s, 80 s…)
     * up to 10 minutes, so a server that's off for the night isn't asked thousands of times.
     */
    fun delayMs(reachable: Boolean?, failedPings: Int): Long {
        if (reachable == true) return REACHABLE_MS
        val doublings = failedPings.coerceIn(0, 20)
        return minOf(MAX_RETRY_MS, FIRST_RETRY_MS shl doublings)
    }

    /** Only worth pinging with a network, out of offline mode, and when someone would notice: the app is open or music is playing. */
    fun shouldPing(hasNetwork: Boolean, offlineMode: Boolean, foreground: Boolean, playing: Boolean): Boolean =
        hasNetwork && !offlineMode && (foreground || playing)
}
