package im.flume.hearth.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
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

    /** Song currently downloading and how far along it is (0..1, or null if the size isn't known). */
    private val _progress = MutableStateFlow<Pair<String, Float?>?>(null)
    val progress: StateFlow<Pair<String, Float?>?> = _progress.asStateFlow()
    internal fun setProgress(value: Pair<String, Float?>?) { _progress.value = value }

    /** Try failed downloads again. */
    fun retryFailed() = scope.launch {
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

    /** True when downloads are waiting to retry after connection problems. */
    val pausedAfterErrors: Flow<Boolean> = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 } }

    /**
     * Starts the download worker. A running worker keeps going (it picks up newly queued songs);
     * one that's waiting out a retry back-off is replaced so it starts straight away.
     */
    fun schedule() {
        scope.launch(Dispatchers.IO) {
            val wm = WorkManager.getInstance(context)
            val running = runCatching { wm.getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrDefault(emptyList())
                .any { it.state == WorkInfo.State.RUNNING }
            val network = if (session.settings.value.wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .build()
            wm.enqueueUniqueWork(WORK_NAME, if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request)
        }
    }

    companion object {
        const val WORK_NAME = "downloads"
        const val KIND_ALBUM = "album"
        const val KIND_PLAYLIST = "playlist"
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as HearthApp).container
        val dao = c.db.downloads()
        // The server may take a while to start sending a song it has to convert first.
        val http = c.http.newBuilder().readTimeout(2, TimeUnit.MINUTES).build()
        val claim = Mutex()
        val completed = AtomicInteger(0)
        val failuresInRow = AtomicInteger(0)
        val backOff = AtomicBoolean(false)
        runCatching { setForeground(foregroundInfo(0)) }

        coroutineScope {
            repeat(PARALLEL) {
                launch {
                    while (!backOff.get() && !isStopped) {
                        val next = claim.withLock {
                            dao.nextQueued()?.also { dao.upsert(it.copy(state = DownloadState.DOWNLOADING)) }
                        } ?: break
                        val file = runCatching {
                            fetch(c, http, next.songId, c.downloads.dir, c.session.settings.value.downloadBitrate)
                        }.getOrNull()
                        val stillWanted = dao.get(next.songId) != null
                        when {
                            !stillWanted -> file?.delete() // cancelled while downloading
                            file != null -> {
                                dao.upsert(next.copy(state = DownloadState.DONE, path = file.absolutePath, bytes = file.length()))
                                c.downloads.markDone(next.songId, file.absolutePath)
                                c.db.library().song(next.songId)?.let { c.lyrics.prefetch(it) }
                                failuresInRow.set(0)
                                runCatching { setForeground(foregroundInfo(completed.incrementAndGet())) }
                            }
                            else -> {
                                // Several failures in a row usually means the server is unreachable: back off and retry later.
                                val giveUp = isStopped || failuresInRow.incrementAndGet() >= 3
                                dao.upsert(next.copy(state = if (giveUp) DownloadState.QUEUED else DownloadState.FAILED))
                                if (giveUp) backOff.set(true)
                            }
                        }
                    }
                }
            }
        }
        return if (backOff.get()) Result.retry() else Result.success()
    }

    private suspend fun fetch(c: AppContainer, http: OkHttpClient, songId: String, dir: File, bitrate: Int): File =
        withContext(Dispatchers.IO) {
            val url = c.api.streamUrl(songId, bitrate) ?: error("Not logged in")
            val tmp = File(dir, "$songId.part")
            val call = http.newCall(Request.Builder().url(url).build())
            c.downloads.active[songId] = call
            try {
                call.execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    val type = resp.header("Content-Type").orEmpty()
                    check(!type.contains("json") && !type.contains("xml")) { "Server returned an error instead of audio" }
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
        private const val PARALLEL = 1
    }
}
