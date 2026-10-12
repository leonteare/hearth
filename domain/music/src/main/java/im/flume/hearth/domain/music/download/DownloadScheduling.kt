// Starting, stopping and waking the download worker, and the download-state flow the song rows read.
package im.flume.hearth.domain.music.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import im.flume.hearth.core.data.DownloadState
import im.flume.hearth.core.data.DownloadStateRow
import im.flume.hearth.core.data.NetworkMonitor
import im.flume.hearth.core.data.SessionStore
import im.flume.hearth.domain.music.download.DownloadRepository.Companion.WORK_NAME
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal suspend fun downloadWorkerRunning(context: Context): Boolean = withContext(Dispatchers.IO) {
    runCatching { WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrDefault(emptyList())
        .any { it.state == WorkInfo.State.RUNNING }
}

/** Asks WorkManager to stop the worker and waits for it to accept; the worker may still be winding down. */
internal suspend fun cancelDownloadWorker(context: Context) {
    withContext(Dispatchers.IO) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME).result.get() }
    }
}

/** The body of [DownloadRepository.schedule]; blocks on WorkManager, so call it off the main thread. */
internal fun enqueueDownloadWorker(context: Context) {
    val wm = WorkManager.getInstance(context)
    val infos = runCatching { wm.getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrDefault(emptyList())
    val running = infos.any { it.state == WorkInfo.State.RUNNING }
    val followUpWaiting = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    if (running && followUpWaiting) return
    val request = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
    wm.enqueueUniqueWork(WORK_NAME, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
}

/** The worker gives up rather than waiting hours for Wi-Fi; [resume] starts it again when Wi-Fi turns up. */
internal fun resumeWhenWifiArrives(context: Context, session: SessionStore, resume: () -> Unit) {
    runCatching {
        context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : ConnectivityManager.NetworkCallback() {
                // Capabilities change often (signal strength); only react the first time a network is Wi-Fi.
                private val wifiNetworks: MutableSet<Network> = ConcurrentHashMap.newKeySet()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (!NetworkMonitor.isWifi(caps)) wifiNetworks.remove(network)
                    else if (wifiNetworks.add(network) && session.settings.value.wifiOnlyDownloads) resume()
                }
                override fun onLost(network: Network) { wifiNetworks.remove(network) }
            },
        )
    }
}

/** Opening the app is a good moment to carry on with anything left over. */
internal fun resumeOnAppStart(scope: CoroutineScope, resume: () -> Unit) {
    scope.launch(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { resume() }
        })
    }
}

/**
 * Song id → download state, sent again only when a state changes. The table is written on every
 * bit of download progress, and each new map would otherwise redraw every song row on screen.
 */
internal fun Flow<List<DownloadStateRow>>.toStateMap(): Flow<Map<String, DownloadState>> =
    map { rows -> rows.associate { it.songId to it.state } }.distinctUntilChanged()
