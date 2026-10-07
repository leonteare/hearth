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
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import im.flume.hearth.HearthApp
import im.flume.hearth.R
import im.flume.hearth.api.SubsonicClient
import im.flume.hearth.data.AppDatabase
import im.flume.hearth.data.DownloadEntity
import im.flume.hearth.data.DownloadState
import im.flume.hearth.data.PinnedEntity
import im.flume.hearth.data.SessionStore
import im.flume.hearth.data.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
        songIds.forEach { id -> done.remove(id); File(dir, id).delete() }
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

    fun schedule() {
        val network = if (session.settings.value.wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
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
        var completed = 0
        var failuresInRow = 0
        runCatching { setForeground(foregroundInfo(0)) }
        while (true) {
            val next = dao.nextQueued() ?: break
            dao.upsert(next.copy(state = DownloadState.DOWNLOADING))
            val ok = runCatching { fetch(c.http, c.api, next.songId, c.downloads.dir, c.session.settings.value.downloadBitrate) }
            val file = ok.getOrNull()
            if (file != null && dao.get(next.songId) != null) {
                dao.upsert(next.copy(state = DownloadState.DONE, path = file.absolutePath, bytes = file.length()))
                c.downloads.markDone(next.songId, file.absolutePath)
                c.db.library().song(next.songId)?.let { c.lyrics.prefetch(it) }
                completed++
                failuresInRow = 0
                runCatching { setForeground(foregroundInfo(completed)) }
            } else {
                file?.delete()
                failuresInRow++
                // Several failures in a row usually means the server is unreachable: back off and retry later.
                val giveUp = isStopped || failuresInRow >= 3
                if (dao.get(next.songId) != null) {
                    dao.upsert(next.copy(state = if (giveUp) DownloadState.QUEUED else DownloadState.FAILED))
                }
                if (giveUp) return Result.retry()
            }
        }
        return Result.success()
    }

    private suspend fun fetch(http: OkHttpClient, api: SubsonicClient, songId: String, dir: File, bitrate: Int): File =
        withContext(Dispatchers.IO) {
            val url = api.streamUrl(songId, bitrate) ?: error("Not logged in")
            val tmp = File(dir, "$songId.part")
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val type = resp.header("Content-Type").orEmpty()
                check(!type.contains("json") && !type.contains("xml")) { "Server returned an error instead of audio" }
                resp.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
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
    }
}
