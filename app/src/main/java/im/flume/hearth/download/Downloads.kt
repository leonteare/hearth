package im.flume.hearth.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import androidx.work.OutOfQuotaPolicy
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import im.flume.hearth.AppContainer
import im.flume.hearth.HearthApp
import im.flume.hearth.R
import im.flume.hearth.data.AppDatabase
import im.flume.hearth.data.DownloadEntity
import im.flume.hearth.data.DownloadState
import im.flume.hearth.data.PinnedEntity
import im.flume.hearth.data.SessionStore
import im.flume.hearth.data.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Songs kept on the phone for offline play. Files live in app-private storage; the playback data
 * source checks [localFile] before streaming, so a downloaded song never touches the network.
 */
class DownloadRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val session: SessionStore,
    private val scope: CoroutineScope,
) {
    private val dao = db.downloads()
    private val done = ConcurrentHashMap<String, String>()
    /** In-flight HTTP calls by song id, so removing a download stops it immediately. */
    internal val active = ConcurrentHashMap<String, Call>()

    /** Why the most recent download failed, in plain words, or null. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    internal fun reportError(message: String?) { _lastError.value = message }

    /** Tries per song this session, so a song that keeps failing doesn't loop forever. */
    internal val attempts = ConcurrentHashMap<String, Int>()

    /** Song currently downloading and how far along it is (0..1, or null if the size isn't known). */
    private val _progress = MutableStateFlow<Pair<String, Float?>?>(null)
    val progress: StateFlow<Pair<String, Float?>?> = _progress.asStateFlow()
    internal fun setProgress(value: Pair<String, Float?>?) { _progress.value = value }

    /** Try failed downloads again. */
    fun retryFailed() = scope.launch {
        attempts.clear()
        _lastError.value = null
        dao.requeueStale()
        schedule()
    }

    /** Drop everything that hasn't finished yet; finished downloads are kept. */
    fun cancelPending() = scope.launch {
        active.values.forEach { it.cancel() }
        dao.deletePending()
    }
    val dir: File = File(context.filesDir, "music").apply { mkdirs() }

    val states: Flow<Map<String, DownloadState>> = dao.all().map { list -> list.associate { it.songId to it.state } }
    val totalBytes: Flow<Long> = dao.totalBytes()
    val pinned: Flow<Set<String>> = dao.pinnedFlow().map { list -> list.map { "${it.kind}:${it.id}" }.toSet() }

    init {
        scope.launch {
            dao.allOnce().filter { it.state == DownloadState.DONE && it.path != null }.forEach { done[it.songId] = it.path!! }
            dao.requeueStale()
            schedule()
        }
    }

    fun localFile(songId: String): File? = done[songId]?.let(::File)?.takeIf { it.exists() }

    fun isDownloaded(songId: String) = done.containsKey(songId)

    internal fun markDone(songId: String, path: String) { done[songId] = path }

    fun download(songs: List<SongEntity>) = scope.launch {
        val now = System.currentTimeMillis()
        dao.insertIgnore(songs.mapIndexed { i, s -> DownloadEntity(s.id, DownloadState.QUEUED, null, 0, now + i) })
        schedule()
    }

    fun remove(songIds: List<String>) = scope.launch {
        songIds.forEach { id -> active.remove(id)?.cancel(); done.remove(id); File(dir, id).delete() }
        songIds.chunked(500).forEach { dao.delete(it) }
    }

    fun pinAndDownload(kind: String, id: String, songs: List<SongEntity>) = scope.launch {
        dao.pin(PinnedEntity(kind, id))
        download(songs)
    }

    fun unpinAndRemove(kind: String, id: String, songIds: List<String>) = scope.launch {
        dao.unpin(kind, id)
        remove(songIds)
    }

    /** After a library sync, fetch any new songs that appeared in pinned playlists. */
    suspend fun refreshPinned() {
        val lib = db.library()
        for (p in dao.pinned()) {
            val songs = when (p.kind) {
                KIND_PLAYLIST -> lib.playlistSongsOnce(p.id)
                KIND_ALBUM -> lib.albumSongsOnce(p.id)
                KIND_LIKED -> lib.starredSongsOnce()
                else -> emptyList()
            }
            if (songs.isNotEmpty()) download(songs)
        }
    }

    fun removeAll() = scope.launch {
        dao.clearPinned()
        val ids = dao.allOnce().map { it.songId }
        remove(ids)
    }

    /** True when the download job is waiting to start again (e.g. for Wi-Fi). */
    val pausedAfterErrors: Flow<Boolean> = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 } }

    /**
     * Starts the download worker. A running worker keeps going (it picks up newly queued songs);
     * one that's waiting to retry is replaced so it starts straight away. The Wi-Fi-only setting is
     * checked by the worker itself rather than Android's "unmetered" constraint, which a VPN like
     * Tailscale can switch on and off mid-download.
     */
    fun schedule() {
        scope.launch(Dispatchers.IO) {
            val wm = WorkManager.getInstance(context)
            val running = runCatching { wm.getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrDefault(emptyList())
                .any { it.state == WorkInfo.State.RUNNING }
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            wm.enqueueUniqueWork(WORK_NAME, if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        }
    }

    companion object {
        const val WORK_NAME = "downloads"
        const val KIND_ALBUM = "album"
        const val KIND_PLAYLIST = "playlist"
        const val KIND_LIKED = "liked"
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as HearthApp).container
        val dao = c.db.downloads()
        // The server may take a while to start sending a song it has to convert first.
        val http = c.http.newBuilder().readTimeout(2, TimeUnit.MINUTES).build()
        var completed = 0
        var failuresInRow = 0
        runCatching { setForeground(foregroundInfo(0)) }

        while (true) {
            if (c.session.settings.value.wifiOnlyDownloads && !c.network.onWifi()) {
                c.downloads.reportError("Waiting for Wi-Fi")
                return Result.retry()
            }
            val next = dao.nextQueued() ?: run {
                // Queue done: give songs that failed another go, a little later, up to 3 tries each.
                val retry = dao.failed().filter { (c.downloads.attempts[it.songId] ?: 0) < MAX_TRIES }
                if (retry.isEmpty()) return Result.success()
                delay(RETRY_DELAY_MS)
                retry.forEach { dao.upsert(it.copy(state = DownloadState.QUEUED)) }
                null
            } ?: continue

            dao.upsert(next.copy(state = DownloadState.DOWNLOADING))
            val file = try {
                fetch(c, http, next.songId, c.downloads.dir, c.session.settings.value.downloadBitrate)
            } catch (e: CancellationException) {
                // Android stopped the job (or the user cancelled): not a failure, carry on next time.
                if (dao.get(next.songId) != null) dao.upsert(next.copy(state = DownloadState.QUEUED))
                throw e
            } catch (e: Exception) {
                c.downloads.reportError(describe(e))
                null
            }

            when {
                dao.get(next.songId) == null -> file?.delete() // removed while downloading
                file != null -> {
                    dao.upsert(next.copy(state = DownloadState.DONE, path = file.absolutePath, bytes = file.length()))
                    c.downloads.markDone(next.songId, file.absolutePath)
                    runCatching { c.db.library().song(next.songId)?.let { c.lyrics.prefetch(it) } }
                    failuresInRow = 0
                    c.downloads.reportError(null)
                    runCatching { setForeground(foregroundInfo(++completed)) }
                }
                else -> {
                    c.downloads.attempts.merge(next.songId, 1, Int::plus)
                    dao.upsert(next.copy(state = DownloadState.FAILED))
                    // Several in a row usually means the server is briefly unreachable: pause, then carry on.
                    if (++failuresInRow >= 3) {
                        delay(RETRY_DELAY_MS)
                        failuresInRow = 0
                    }
                }
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "The server took too long to respond"
        is java.net.UnknownHostException -> "Couldn't find the server (is Tailscale connected?)"
        is java.net.ConnectException -> "Couldn't connect to the server"
        is javax.net.ssl.SSLException -> "Secure connection problem: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }.replace(Regex("https?://\S+"), "the server") // URLs carry the login token; never show them

    private suspend fun fetch(c: AppContainer, http: OkHttpClient, songId: String, dir: File, bitrate: Int): File =
        withContext(Dispatchers.IO) {
            val url = c.api.streamUrl(songId, bitrate) ?: error("Not logged in")
            val tmp = File(dir, "$songId.part")
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
                                    c.downloads.setProgress(songId to total?.let { (done.toFloat() / it).coerceAtMost(1f) })
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            } finally {
                c.downloads.active.remove(songId)
                c.downloads.setProgress(null)
            }
            File(dir, songId).also { dest -> dest.delete(); check(tmp.renameTo(dest)) { "Rename failed" } }
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
        private const val RETRY_DELAY_MS = 30_000L
    }
}
