package im.flume.hearth.domain.music.download

import im.flume.hearth.domain.music.musicContainer
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import im.flume.hearth.domain.music.MusicContainer
import im.flume.hearth.core.common.R
import im.flume.hearth.core.data.DownloadEntity
import im.flume.hearth.core.data.DownloadState
import im.flume.hearth.core.common.scrubSecrets
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.internal.http2.StreamResetException

/**
 * The background job that fetches queued songs onto the phone. Several lanes download side by side,
 * dropped connections send a song to the back of the queue, and the job stops cleanly for a full
 * phone, a missing Wi-Fi network or the user pausing. The decisions it makes live in [DownloadPolicy].
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private enum class Halt { WIFI, STORAGE }

    override suspend fun doWork(): Result {
        val c = applicationContext.musicContainer
        val dao = c.db.downloads()
        val repo = c.downloads
        fun paused() = c.session.settings.value.downloadsPaused
        fun parallel() = c.session.settings.value.parallelDownloads.coerceIn(1, MAX_PARALLEL)
        fun waitingForWifi() = c.session.settings.value.wifiOnlyDownloads && !c.network.onWifi()
        fun lowOnSpace() = runCatching { StatFs(repo.dir.path).availableBytes < DownloadPolicy.MIN_FREE_BYTES }.getOrDefault(false)

        // Only one worker runs at a time and no lane has started, so anything marked as downloading
        // (or a half-written file) was left behind by a worker that was stopped.
        dao.requeueInterrupted()
        repo.dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        repo.setStorageFull(false)

        fun stopForStorage(): Result {
            repo.setStorageFull(true)
            repo.reportError("Phone storage is full")
            return Result.success() // songs stay queued until the user frees space and taps Retry
        }
        if (paused()) return Result.success()
        if (c.session.credentials.value == null) return Result.success() // signed out: songs wait until sign-in
        if (waitingForWifi()) { repo.reportError("Waiting for Wi-Fi"); return Result.success() }
        if (lowOnSpace()) return stopForStorage()

        // The server may take a while to start sending a song it has to convert first.
        // One connection per song (HTTP/1.1): with HTTP/2 every download shares a single connection,
        // and one hiccup on the server resets all of them at once.
        val http = c.http.newBuilder().readTimeout(2, TimeUnit.MINUTES).protocols(listOf(Protocol.HTTP_1_1)).build()
        val claim = Mutex()
        val completed = AtomicInteger()
        val failuresInRow = AtomicInteger()
        val busy = AtomicInteger()
        val halt = AtomicReference<Halt?>(null)
        runCatching { setForeground(foregroundInfo(0)) }

        /** Puts a song back in the queue, unless the user removed it meanwhile. */
        suspend fun requeue(entry: DownloadEntity, toBack: Boolean = false) {
            if (dao.get(entry.songId) != null) {
                dao.upsert(entry.copy(state = DownloadState.QUEUED, addedAt = if (toBack) System.currentTimeMillis() else entry.addedAt))
            }
        }

        /** Downloads one claimed song. Returns false when the lane should stop. */
        suspend fun process(next: DownloadEntity, bitrate: Int): Boolean {
            val file = try {
                fetch(c, http, next.songId, repo.dir, bitrate)
            } catch (e: CancellationException) {
                // Android stopped the job: not a failure, carry on next time. Room needs a live coroutine.
                withContext(NonCancellable) { requeue(next) }
                throw e
            } catch (e: Exception) {
                if (paused()) { requeue(next); return false }
                val removed = next.songId in repo.cancelled || dao.get(next.songId) == null
                // Counted once per failed attempt, whatever the cause; the user removing it or a full phone don't count.
                val tries = if (!removed && !DownloadPolicy.isStorageFull(e)) {
                    repo.attempts.merge(next.songId, 1, Int::plus) ?: 1
                } else 0
                when (DownloadPolicy.classify(e, removed, tries)) {
                    DownloadPolicy.Failure.Cancelled -> {
                        repo.cancelled.remove(next.songId)
                        return true
                    }
                    DownloadPolicy.Failure.StorageFull -> {
                        requeue(next)
                        halt.set(Halt.STORAGE)
                        return false
                    }
                    DownloadPolicy.Failure.Transient -> {
                        // The connection dropped: send the song to the back of the queue and move straight on.
                        // Only if several in a row drop is the server struggling: then every lane waits
                        // briefly (10s, 20s, 40s… up to 2 min).
                        requeue(next, toBack = true)
                        val pause = DownloadPolicy.backoffMs(failuresInRow.incrementAndGet())
                        if (pause > 0) {
                            repo.backoffUntil.value = System.currentTimeMillis() + pause
                            repo.setBackingOff(true)
                            repo.reportError("${describe(e)}. Server busy, trying again in ${pause / 1000}s")
                        } else {
                            repo.reportError("${describe(e)}. Will try that song again later")
                        }
                        return true
                    }
                    DownloadPolicy.Failure.SongFailed -> {
                        repo.reportError(describe(e))
                        null
                    }
                }
            }

            // Checked and marked in one transaction: a removal can't slip in between and leave a
            // finished row with no file behind it.
            val kept = file != null && c.db.withTransaction {
                val stillWanted = next.songId !in repo.cancelled && dao.get(next.songId) != null && file.exists()
                if (stillWanted) {
                    dao.upsert(next.copy(state = DownloadState.DONE, path = file.absolutePath, bytes = file.length(), completedAt = System.currentTimeMillis()))
                    repo.markDone(next.songId, file.absolutePath)
                }
                stillWanted
            }
            when {
                file == null && dao.get(next.songId) == null -> {} // removed while downloading
                file != null && !kept -> {
                    // Removed while downloading (or the file vanished): forget it, or try again if still wanted.
                    if (next.songId !in repo.cancelled && dao.get(next.songId) != null) requeue(next)
                    else { file.delete(); repo.cancelled.remove(next.songId) }
                }
                file != null -> {
                    runCatching { c.db.library().song(next.songId)?.let { c.lyrics.prefetch(it) } }
                    failuresInRow.set(0)
                    repo.reportError(null)
                    runCatching { setForeground(foregroundInfo(completed.incrementAndGet())) }
                }
                else -> {
                    dao.upsert(next.copy(state = DownloadState.FAILED))
                    // Several in a row usually means the server is briefly unreachable: pause, then carry on.
                    if (failuresInRow.incrementAndGet() >= 3) {
                        delay(DownloadPolicy.BACKOFF_MS)
                        failuresInRow.set(0)
                    }
                }
            }
            return true
        }

        /**
         * One download lane: takes the next queued song until the queue is empty. Several run side by
         * side. An idle lane waits while others are busy, since they may send songs back to the queue.
         */
        suspend fun lane(index: Int) {
            while (true) {
                val settings = c.session.settings.value
                if (halt.get() != null || paused() || index >= parallel()) return
                if (waitingForWifi()) { halt.compareAndSet(null, Halt.WIFI); return }
                val wait = repo.backoffUntil.value - System.currentTimeMillis()
                if (wait > 0) { delay(wait.coerceAtMost(RECHECK_MS)); continue } // short steps, so "Retry now" takes effect
                if (repo.backingOff.value) repo.setBackingOff(false)
                if (lowOnSpace()) { halt.compareAndSet(null, Halt.STORAGE); return }
                val next = claim.withLock {
                    dao.nextQueued()?.copy(state = DownloadState.DOWNLOADING)?.also { dao.upsert(it); busy.incrementAndGet() }
                }
                if (next == null) {
                    if (DownloadPolicy.idleLaneShouldExit(busy.get())) return
                    delay(RECHECK_MS)
                    continue
                }
                val carryOn = try { process(next, settings.downloadBitrate) } finally { busy.decrementAndGet() }
                if (!carryOn) return
            }
        }

        /** Keeps the right number of lanes going, starting more if "Downloads at once" is raised mid-run. */
        suspend fun runLanes() = coroutineScope {
            val lanes = arrayOfNulls<Job>(MAX_PARALLEL)
            while (true) {
                if (halt.get() == null && !paused() && dao.hasQueued()) {
                    for (i in 0 until parallel()) if (lanes[i]?.isActive != true) lanes[i] = launch { lane(i) }
                }
                if (lanes.none { it?.isActive == true }) break
                delay(SUPERVISE_MS)
            }
        }

        try {
            while (true) {
                if (paused()) return Result.success()
                runLanes()
                if (paused()) return Result.success()
                when (halt.get()) {
                    Halt.WIFI -> { repo.reportError("Waiting for Wi-Fi"); return Result.success() } // restarted when Wi-Fi returns
                    Halt.STORAGE -> return stopForStorage()
                    null -> {}
                }
                if (dao.hasQueued()) continue // queued while the last lane was finishing
                // Queue done: give songs that failed another go, a little later, up to 3 tries each.
                val retry = dao.failed().filter { (repo.attempts[it.songId] ?: 0) < MAX_TRIES }.map { it.songId }
                if (retry.isEmpty()) return Result.success()
                // Wait, but start straight away if new songs are queued meanwhile.
                withTimeoutOrNull(RETRY_DELAY_MS) { while (!dao.hasQueued()) delay(RECHECK_MS) }
                retry.chunked(500).forEach { dao.requeueFailed(it) }
            }
        } finally {
            repo.setBackingOff(false)
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is StreamResetException -> "The server cut the download off"
        is SocketTimeoutException -> "The server took too long to respond"
        is UnknownHostException -> "Couldn't find the server (is Tailscale connected?)"
        is ConnectException -> "Couldn't connect to the server"
        is SSLException -> "Secure connection problem: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }.let { scrubSecrets(it) } // URLs carry the login token; never show them

    private suspend fun fetch(c: MusicContainer, http: OkHttpClient, songId: String, dir: File, bitrate: Int): File =
        withContext(Dispatchers.IO) {
            val url = c.api.streamUrl(songId, bitrate, estimateLength = false) ?: error("Not logged in")
            val tmp = File(dir, DownloadPolicy.partName(songId, System.nanoTime()))
            val call = http.newCall(Request.Builder().url(url).build())
            c.downloads.active[songId] = call
            try {
                call.execute().use { resp ->
                    check(resp.isSuccessful) { "Server answered HTTP ${resp.code}" }
                    val type = resp.header("Content-Type").orEmpty()
                    if (type.contains("json") || type.contains("xml")) {
                        val body = resp.body?.string().orEmpty()
                        val msg = Regex("\"message\"\\s*:\\s*\"([^\"]+)").find(body)?.groupValues?.get(1)
                        error("Navidrome said: ${msg ?: body.take(120)}")
                    }
                    val body = resp.body!!
                    val total = body.contentLength().takeIf { it > 0 }
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done - lastReport > 256 * 1024) {
                                    lastReport = done
                                    c.downloads.setProgress(songId, total?.let { (done.toFloat() / it).coerceAtMost(1f) })
                                }
                            }
                        }
                    }
                }
                File(dir, songId).also { dest -> dest.delete(); check(tmp.renameTo(dest)) { "Rename failed" } }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            } finally {
                c.downloads.active.remove(songId)
                c.downloads.clearProgress(songId)
            }
        }

    private fun foregroundInfo(done: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Downloading music")
            .setContentText(if (done == 0) "Starting…" else "$done songs downloaded")
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0)

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 42
        private const val MAX_TRIES = 3
        const val MAX_PARALLEL = 4
        private const val RETRY_DELAY_MS = 30_000L
        /** How often an idle or backing-off lane looks again. */
        private const val RECHECK_MS = 2_000L
        /** How often the worker checks whether more lanes should be running. */
        private const val SUPERVISE_MS = 3_000L
    }
}
