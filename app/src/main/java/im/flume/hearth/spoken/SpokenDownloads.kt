package im.flume.hearth.spoken

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import im.flume.hearth.HearthApp
import im.flume.hearth.R
import im.flume.hearth.api.AbsClient
import im.flume.hearth.data.AppDatabase
import im.flume.hearth.data.NetworkMonitor
import im.flume.hearth.data.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Identifies a download: an episode, or a whole book ([episodeId] ""). */
data class SpokenKey(val libraryItemId: String, val episodeId: String = "") {
    override fun toString() = if (episodeId.isEmpty()) libraryItemId else "$libraryItemId/$episodeId"
}

/**
 * Episodes and audiobooks kept on this phone. Files are `spoken/<item>/<audio file id>` in app storage;
 * the player checks [localFile] before streaming. Episodes this user finishes are deleted from this
 * phone automatically (other phones keep theirs), and copies of episodes the server deleted go too.
 */
class SpokenDownloads(
    private val context: Context,
    private val db: AppDatabase,
    private val api: AbsClient,
    private val session: SessionStore,
    private val spokenSettings: () -> SpokenSettings,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
) {
    private val dao = db.spoken()
    val dir: File = File(context.filesDir, "spoken").apply { mkdirs() }

    internal val active = ConcurrentHashMap<SpokenKey, Call>()

    /** Called before a book is downloaded, to make sure its track list is on the phone. */
    var ensureBookDetails: (suspend (String) -> Unit)? = null

    /** Which item is loaded in the player (its downloads are never auto-deleted mid-play). */
    @Volatile var current: SpokenKey? = null

    val rows: Flow<List<SpokenDownloadEntity>> = dao.downloads()
    val states: Flow<Map<SpokenKey, SpokenDownloadState>> = rows.map { list -> list.associate { SpokenKey(it.libraryItemId, it.episodeId) to it.state } }
    val totalBytes: Flow<Long> = dao.downloadedBytes()

    private val _progress = MutableStateFlow<Map<SpokenKey, Float?>>(emptyMap())
    /** Downloads in progress and how far along each is (0..1, null if the size isn't known). */
    val progress: StateFlow<Map<SpokenKey, Float?>> = _progress.asStateFlow()
    internal fun setProgress(key: SpokenKey, value: Float?) = _progress.update { it + (key to value) }
    internal fun clearProgress(key: SpokenKey) = _progress.update { it - key }

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    internal fun reportError(message: String?) { _lastError.value = message }

    init {
        // Downloads waiting for Wi-Fi start when it turns up.
        runCatching {
            context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    private val wifi: MutableSet<Network> = ConcurrentHashMap.newKeySet()
                    override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                        if (!NetworkMonitor.isWifi(caps)) wifi.remove(n) else if (wifi.add(n)) scope.launch { if (dao.hasQueuedDownloads()) schedule() }
                    }
                    override fun onLost(n: Network) { wifi.remove(n) }
                },
            )
        }
    }

    fun fileFor(libraryItemId: String, ino: String): File = File(File(dir, libraryItemId), ino)

    /** The downloaded copy of one audio file, if it's on the phone. Cheap: safe on the player's loading thread. */
    fun localFile(libraryItemId: String, ino: String): File? = fileFor(libraryItemId, ino).takeIf { it.isFile }

    fun downloadEpisode(libraryItemId: String, episodeId: String) = scope.launch { enqueue(SpokenKey(libraryItemId, episodeId), auto = false) }

    fun downloadBook(libraryItemId: String) = scope.launch { enqueue(SpokenKey(libraryItemId), auto = false) }

    private suspend fun enqueue(key: SpokenKey, auto: Boolean) {
        val existing = dao.download(key.libraryItemId, key.episodeId)
        if (existing != null && existing.state in setOf(SpokenDownloadState.DONE, SpokenDownloadState.QUEUED, SpokenDownloadState.DOWNLOADING)) return
        if (auto && existing != null) return // removed by the user (or failed): don't keep bringing it back
        dao.upsertDownload(SpokenDownloadEntity(key.libraryItemId, key.episodeId, SpokenDownloadState.QUEUED, 0, 0, auto, System.currentTimeMillis(), 0, null))
        schedule()
    }

    /** The user removed this download: files go, and an auto-download won't bring the episode back. */
    fun remove(libraryItemId: String, episodeId: String = "") = scope.launch { deleteLocal(SpokenKey(libraryItemId, episodeId), keepRemovedMarker = episodeId.isNotEmpty()) }

    fun retryFailed() = scope.launch { dao.requeueFailedDownloads(); _lastError.value = null; schedule() }

    /** Deletes the files (and stops a download in progress). [keepRemovedMarker] leaves a REMOVED row behind. */
    suspend fun deleteLocal(key: SpokenKey, keepRemovedMarker: Boolean) {
        val row = dao.download(key.libraryItemId, key.episodeId)
        if (keepRemovedMarker) {
            dao.upsertDownload(
                (row ?: SpokenDownloadEntity(key.libraryItemId, key.episodeId, SpokenDownloadState.REMOVED, 0, 0, false, System.currentTimeMillis(), 0, null))
                    .copy(state = SpokenDownloadState.REMOVED, bytes = 0, error = null)
            )
        } else {
            dao.deleteDownload(key.libraryItemId, key.episodeId)
        }
        active.remove(key)?.cancel()
        withContext(Dispatchers.IO) { filesOf(key).forEach { it.delete() } }
        if (row?.isBook == true || key.episodeId.isEmpty()) File(dir, key.libraryItemId).let { d -> if (d.list().isNullOrEmpty()) d.delete() }
    }

    /** Files belonging to [key]: the episode's audio file, or every file of a book. */
    private suspend fun filesOf(key: SpokenKey): List<File> = if (key.episodeId.isEmpty()) {
        File(dir, key.libraryItemId).listFiles()?.toList().orEmpty()
    } else {
        listOfNotNull(dao.episodeOnce(key.episodeId)?.episode?.ino?.let { fileFor(key.libraryItemId, it) })
    }

    /** Episodes the server deleted: remove this phone's copies and rows. */
    suspend fun onEpisodesRemoved(libraryItemId: String, episodeIds: Collection<String>, inos: Map<String, String?>) {
        for (id in episodeIds) {
            val key = SpokenKey(libraryItemId, id)
            active.remove(key)?.cancel()
            dao.deleteDownload(libraryItemId, id)
            inos[id]?.let { withContext(Dispatchers.IO) { fileFor(libraryItemId, it).delete() } }
        }
    }

    /** Podcasts or books the server no longer has. */
    suspend fun onItemsRemoved(libraryItemIds: Collection<String>) {
        val rows = dao.downloadsOnce().filter { it.libraryItemId in libraryItemIds }
        rows.forEach { dao.deleteDownload(it.libraryItemId, it.episodeId); active.remove(SpokenKey(it.libraryItemId, it.episodeId))?.cancel() }
        withContext(Dispatchers.IO) { libraryItemIds.forEach { File(dir, it).deleteRecursively() } }
    }

    /** This user finished an episode: delete this phone's copy (later, if it's the one playing). */
    suspend fun onFinished(libraryItemId: String, episodeId: String) {
        val row = dao.download(libraryItemId, episodeId) ?: return
        if (SpokenDownloadPolicy.shouldAutoDelete(episodeId.isNotEmpty(), finished = true, isCurrent = current == SpokenKey(libraryItemId, episodeId), state = row.state)) {
            deleteLocal(SpokenKey(libraryItemId, episodeId), keepRemovedMarker = true)
        }
    }

    /** Deletes downloaded episodes that are finished and not loaded in the player. Run after sync and when playback moves on. */
    suspend fun autoDeleteFinished() {
        val finished = dao.allProgress().filter { it.isFinished && it.episodeId.isNotEmpty() }.mapTo(HashSet()) { SpokenKey(it.libraryItemId, it.episodeId) }
        for (row in dao.downloadsOnce()) {
            val key = SpokenKey(row.libraryItemId, row.episodeId)
            if (SpokenDownloadPolicy.shouldAutoDelete(!row.isBook, key in finished, key == current, row.state)) deleteLocal(key, keepRemovedMarker = true)
        }
    }

    /** Queues the newest unplayed episodes of each followed show, and drops auto-downloads that fell out of that window. */
    suspend fun planAutoDownloads(followed: List<PodcastEntity>) {
        if (!spokenSettings().autoDownloadOnWifi) return
        val rows = dao.downloadsOnce().associateBy { SpokenKey(it.libraryItemId, it.episodeId) }
        for (p in followed) {
            val episodes = dao.episodesOnce(p.id).filter { it.episode.ino != null }
            val existing = episodes.mapNotNull { e -> rows[SpokenKey(p.id, e.episode.id)]?.let { e.episode.id to DownloadInfo(it.state, it.auto) } }.toMap()
            val plan = SpokenDownloadPolicy.autoDownloadPlan(
                episodes.map { EpisodeInfo(it.episode.id, it.episode.publishedAt, it.finished, it.started) }, existing,
            )
            plan.toQueue.forEach { enqueue(SpokenKey(p.id, it), auto = true) }
            plan.toRemove.forEach { deleteLocal(SpokenKey(p.id, it), keepRemovedMarker = false) }
        }
    }

    /** Removes not-yet-started auto-downloads of a show this user stopped following. */
    suspend fun onUnfollowed(podcastId: String) {
        val started = dao.allProgress().filter { it.libraryItemId == podcastId && it.currentTime > 0 }.mapTo(HashSet()) { it.episodeId }
        dao.downloadsOnce().filter { it.libraryItemId == podcastId && it.auto && it.episodeId !in started && it.state != SpokenDownloadState.REMOVED }
            .forEach { deleteLocal(SpokenKey(it.libraryItemId, it.episodeId), keepRemovedMarker = false) }
    }

    /** Whether a queued download may run on the current connection. Auto-downloads are Wi-Fi only. */
    internal fun allowedNow(row: SpokenDownloadEntity): Boolean =
        network.onWifi() || (!row.auto && !session.settings.value.wifiOnlyDownloads)

    fun schedule() {
        scope.launch(Dispatchers.IO) {
            val wm = WorkManager.getInstance(context)
            val infos = runCatching { wm.getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrDefault(emptyList())
            val running = infos.any { it.state == WorkInfo.State.RUNNING }
            val waiting = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            if (running && waiting) return@launch
            val request = OneTimeWorkRequestBuilder<SpokenDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            wm.enqueueUniqueWork(WORK_NAME, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
        }
    }

    /** Downloads one queued episode or book; true when it finished. */
    internal suspend fun fetch(row: SpokenDownloadEntity): Boolean {
        val key = SpokenKey(row.libraryItemId, row.episodeId)
        val files: List<Pair<String, Long>> = if (row.isBook) {
            if (dao.tracks(row.libraryItemId).isEmpty()) ensureBookDetails?.invoke(row.libraryItemId)
            dao.tracks(row.libraryItemId).map { it.ino to it.sizeBytes }
        } else {
            val ep = dao.episodeOnce(row.episodeId)?.episode ?: throw IOException("The episode is no longer on the server")
            listOf((ep.ino ?: throw IOException("The episode has no audio file")) to ep.sizeBytes)
        }
        if (files.isEmpty()) throw IOException("Nothing to download")
        val total = files.sumOf { it.second }.takeIf { it > 0 }
        var doneBytes = files.filter { localFile(row.libraryItemId, it.first) != null }.sumOf { localFile(row.libraryItemId, it.first)!!.length() }
        for ((ino, _) in files) {
            if (localFile(row.libraryItemId, ino) != null) continue
            val dest = fileFor(row.libraryItemId, ino).apply { parentFile?.mkdirs() }
            val tmp = File(dest.parentFile, "$ino.${System.nanoTime()}.part")
            val call = api.http.newCall(Request.Builder().url(api.fileUrl(row.libraryItemId, ino)).build())
            active[key] = call
            try {
                withContext(Dispatchers.IO) {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("The podcast server answered HTTP ${resp.code}")
                        resp.body!!.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var last = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    doneBytes += n
                                    if (doneBytes - last > 512 * 1024) {
                                        last = doneBytes
                                        setProgress(key, total?.let { (doneBytes.toFloat() / it).coerceAtMost(1f) })
                                    }
                                }
                            }
                        }
                    }
                    if (!tmp.renameTo(dest)) throw IOException("Couldn't save the file")
                }
            } finally {
                tmp.delete()
                active.remove(key)
            }
        }
        clearProgress(key)
        // Still wanted? (It may have been removed while downloading.)
        val now = dao.download(row.libraryItemId, row.episodeId)
        if (now == null || now.state == SpokenDownloadState.REMOVED) {
            deleteLocal(key, keepRemovedMarker = now != null)
            return false
        }
        val bytes = files.sumOf { fileFor(row.libraryItemId, it.first).length() }
        dao.upsertDownload(now.copy(state = SpokenDownloadState.DONE, bytes = bytes, totalBytes = bytes, completedAt = System.currentTimeMillis(), error = null))
        return true
    }

    companion object {
        const val WORK_NAME = "spoken-downloads"
    }
}

/** Downloads queued episodes and books one at a time. */
class SpokenDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as HearthApp).container
        val repo = c.spoken.downloads
        val dao = c.db.spoken()
        if (c.spoken.store.account.value == null) return Result.success()
        dao.requeueInterruptedDownloads()
        repo.dir.walkTopDown().filter { it.name.endsWith(".part") }.forEach { it.delete() }
        runCatching { setForeground(foregroundInfo(0)) }
        val tries = HashMap<SpokenKey, Int>()
        var completed = 0
        while (true) {
            val next = dao.downloadsOnce().filter { it.state == SpokenDownloadState.QUEUED }.sortedBy { it.addedAt }.firstOrNull(repo::allowedNow)
                ?: break
            val key = SpokenKey(next.libraryItemId, next.episodeId)
            dao.upsertDownload(next.copy(state = SpokenDownloadState.DOWNLOADING))
            try {
                if (repo.fetch(next)) completed++
                repo.reportError(null)
                runCatching { setForeground(foregroundInfo(completed)) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { dao.download(next.libraryItemId, next.episodeId)?.takeIf { it.state == SpokenDownloadState.DOWNLOADING }?.let { dao.upsertDownload(it.copy(state = SpokenDownloadState.QUEUED)) } }
                throw e
            } catch (e: Exception) {
                repo.clearProgress(key)
                val row = dao.download(next.libraryItemId, next.episodeId) ?: continue // removed meanwhile
                if (row.state == SpokenDownloadState.REMOVED) continue
                val n = (tries[key] ?: 0) + 1
                tries[key] = n
                val message = im.flume.hearth.util.userMessage(e)
                repo.reportError(message)
                dao.upsertDownload(row.copy(state = if (n < 3 && e is IOException) SpokenDownloadState.QUEUED else SpokenDownloadState.FAILED, addedAt = if (n < 3) System.currentTimeMillis() else row.addedAt, error = message))
                if (n >= 3) continue
                kotlinx.coroutines.delay(5_000L * n)
            }
        }
        return Result.success()
    }

    private fun foregroundInfo(done: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Downloading podcasts and audiobooks")
            .setContentText(if (done == 0) "Starting…" else "$done downloaded")
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0)

    private companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 43
    }
}
