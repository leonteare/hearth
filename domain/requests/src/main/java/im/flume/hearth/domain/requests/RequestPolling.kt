package im.flume.hearth.domain.requests

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import im.flume.hearth.core.data.RequestStatus
import im.flume.hearth.core.common.logHandledError
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * Following requests until they land: a 20-second loop while the app is open, a 15-minute
 * WorkManager job otherwise, and the round that reads Tidarr's queue and looks for finished music on
 * the server. Extensions on [RequestsRepository], which owns the state they use.
 */

private const val FOREGROUND_POLL_MS = 20_000L
private const val SCAN_WAIT_MS = 5 * 60_000L
private const val SCAN_POLL_MS = 10_000L

internal suspend fun RequestsRepository.hasActive() = dao.active().any(this::mine)

internal fun RequestsRepository.ensureTracking() {
    scheduleBackground()
    scope.launch(Dispatchers.Main) { if (foreground) startLoop() }
}

internal fun RequestsRepository.startLoop() {
    if (loop?.isActive == true) return
    loop = scope.launch {
        while (isActive) {
            val more = try { poll() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                logHandledError(context, "requests poll", e); true
            }
            if (!more) break
            delay(FOREGROUND_POLL_MS)
        }
    }
}

internal fun RequestsRepository.scheduleBackground() {
    if (session.credentials.value == null) return
    val request = PeriodicWorkRequestBuilder<RequestsWorker>(15, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(RequestsRepository.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
}

/** The body of [RequestsRepository.poll], run under its lock. */
internal suspend fun RequestsRepository.pollRound(): Boolean {
    if (me == null) return false
    val active = dao.active().filter(this::mine)
    if (active.isEmpty()) return false
    val now = System.currentTimeMillis()

    var justFinished = false
    if (config() != null && active.any { it.status == RequestStatus.REQUESTED || it.status == RequestStatus.DOWNLOADING }) {
        val queue = runCatching { tidarr.queue() }.getOrNull()
        if (queue != null) {
            val byId = queue.associateBy { it.id }
            active.forEach { r ->
                val next = RequestTracking.afterQueue(r, byId[r.tidalId], now)
                if (next != r) dao.upsert(next)
                if (next.status == RequestStatus.ADDING && r.status != RequestStatus.ADDING) justFinished = true
            }
        }
    }
    // Tidarr's own rescan can fail (e.g. a wrong Navidrome password in its settings), so ask the
    // server to scan too. Both users are admins; if this one isn't, the folder watcher still helps.
    if (justFinished) api.startScan()

    val adding = dao.active().filter { mine(it) && it.status == RequestStatus.ADDING }
    // Long overdue (e.g. scan status never answered): one last look, then it failed.
    val arrived = mutableListOf<RequestIntent.Arrival>()
    for (r in adding.filter { RequestTracking.addingTimedOut(it, now) }) {
        val result = runCatching { findOnServer(r) }
        if (result.isFailure) continue
        val found = result.getOrNull()
        if (found != null) arrived += complete(r, found) else giveUp(r)
    }
    val due = adding.filter { !RequestTracking.addingTimedOut(it, now) && RequestTracking.lookupDue(it, now) }
    if (due.isNotEmpty() && awaitScanIdle()) {
        for (r in due) {
            val result = runCatching { findOnServer(r) }
            if (result.isFailure) continue // server unreachable: not an attempt
            val found = result.getOrNull()
            if (found != null) { arrived += complete(r, found); continue }
            val next = r.copy(lookupAttempts = r.lookupAttempts + 1, updatedAt = System.currentTimeMillis())
            if (RequestTracking.lookupExhausted(next)) giveUp(next) else dao.upsert(next)
        }
    }
    if (arrived.isNotEmpty()) {
        // Downloaded albums, playlists and Liked Songs pick up what just arrived (e.g. the rest of an album).
        runCatching { downloads.refreshPinned() }
        RequestIntent.notifications(arrived).forEach { notifyArrival(it) }
    }
    return dao.active().any(this::mine)
}

/** Waits (up to 5 min) for Navidrome to finish scanning. False if it's still going or can't be asked. */
private suspend fun RequestsRepository.awaitScanIdle(): Boolean {
    val deadline = System.currentTimeMillis() + SCAN_WAIT_MS
    while (true) {
        val status = api.scanStatus() ?: return false
        if (!status.scanning) return true
        if (System.currentTimeMillis() > deadline) return false
        delay(SCAN_POLL_MS)
    }
}
