package im.flume.hearth.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
import im.flume.hearth.AppContainer
import im.flume.hearth.HearthApp
import im.flume.hearth.R
import androidx.room.withTransaction
import im.flume.hearth.data.AppDatabase
import im.flume.hearth.data.DownloadCounts
import im.flume.hearth.data.DownloadEntity
import im.flume.hearth.data.SongDownloadPrefEntity
import kotlinx.coroutines.flow.distinctUntilChanged
import im.flume.hearth.data.DownloadState
import im.flume.hearth.data.NetworkMonitor
import im.flume.hearth.data.PinnedEntity
import im.flume.hearth.data.SessionStore
import im.flume.hearth.data.SongEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Call
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * Songs the user removed or cancelled. Cancelling a call makes it fail like a dropped connection,
     * so the worker checks this to avoid queueing the song again or counting it as server trouble.
     */
    internal val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Why the most recent download failed, in plain words, or null. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    internal fun reportError(message: String?) { _lastError.value = message }

    /** Tries per song this session, so a song that keeps failing doesn't loop forever. */
    internal val attempts = ConcurrentHashMap<String, Int>()

    /** While the server is struggling every lane waits until this time. Shared so "Retry now" can clear it. */
    internal val backoffUntil = MutableStateFlow(0L)
    private val _backingOff = MutableStateFlow(false)
    /** True while downloads wait out a backoff after several connection drops in a row. */
    val backingOff: StateFlow<Boolean> = _backingOff.asStateFlow()
    internal fun setBackingOff(value: Boolean) { _backingOff.value = value }

    private val _storageFull = MutableStateFlow(false)
    /** True when downloads stopped because the phone ran out of space; they wait until the user acts. */
    val storageFull: StateFlow<Boolean> = _storageFull.asStateFlow()
    internal fun setStorageFull(value: Boolean) { _storageFull.value = value }

    /** Songs currently downloading and how far along each is (0..1, or null if the size isn't known). */
    private val _progress = MutableStateFlow<Map<String, Float?>>(emptyMap())
    val progress: StateFlow<Map<String, Float?>> = _progress.asStateFlow()
    internal fun setProgress(songId: String, value: Float?) = _progress.update { it + (songId to value) }
    internal fun clearProgress(songId: String) = _progress.update { it - songId }

    /** Stop downloading but keep the queue; songs part-way through start again on resume. */
    fun pause() {
        session.updateSettings { it.copy(downloadsPaused = true) }
        active.values.forEach { it.cancel() }
    }

    fun resume() {
        session.updateSettings { it.copy(downloadsPaused = false) }
        _storageFull.value = false
        schedule()
    }

    /** Try failed downloads again, and stop waiting out any backoff. */
    fun retryFailed() = scope.launch {
        attempts.clear()
        _lastError.value = null
        _storageFull.value = false
        backoffUntil.value = 0
        _backingOff.value = false
        requeueStale()
        schedule()
    }

    /**
     * Drop everything that hasn't finished yet; finished downloads are kept. The user chose to drop
     * these songs, so a later sync of a downloaded album or playlist doesn't queue them again.
     */
    fun cancelPending() = scope.launch {
        val ids = dao.pendingIds()
        cancelled.addAll(ids)
        setPrefs(ids, wanted = false)
        dao.deletePending()
        ids.forEach { active.remove(it)?.cancel() }
    }
    val dir: File = File(context.filesDir, "music").apply { mkdirs() }

    val states: Flow<Map<String, DownloadState>> = dao.all().map { list -> list.associate { it.songId to it.state } }
    val totalBytes: Flow<Long> = dao.totalBytes()
    val pinned: Flow<Set<String>> = dao.pinnedFlow().map { list -> list.map { "${it.kind}:${it.id}" }.toSet() }
    /** Waiting, downloading and failed counts; the Downloads page is only offered while something's going on. */
    val counts: Flow<DownloadCounts> = dao.counts().distinctUntilChanged()

    /** One refresh at a time, so two syncs finishing together can't queue the same songs twice. */
    private val refreshLock = Mutex()

    init {
        scope.launch {
            loadDone()
            requeueStale()
            schedule()
            // Signing in again (after an accidental sign-out) carries on with whatever was queued.
            session.credentials.map { it != null }.distinctUntilChanged().collect { signedIn -> if (signedIn) resumeIfWaiting() }
        }
        // The worker gives up rather than waiting hours for Wi-Fi; start it again when Wi-Fi turns up.
        runCatching {
            context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    // Capabilities change often (signal strength); only react the first time a network is Wi-Fi.
                    private val wifiNetworks: MutableSet<Network> = ConcurrentHashMap.newKeySet()
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        if (!NetworkMonitor.isWifi(caps)) wifiNetworks.remove(network)
                        else if (wifiNetworks.add(network) && session.settings.value.wifiOnlyDownloads) resumeIfWaiting()
                    }
                    override fun onLost(network: Network) { wifiNetworks.remove(network) }
                },
            )
        }
        // Opening the app is a good moment to carry on with anything left over.
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { resumeIfWaiting() }
            })
        }
    }

    /** Starts the worker if songs are queued and nothing is holding them back on purpose. */
    private fun resumeIfWaiting() {
        if (session.settings.value.downloadsPaused || _storageFull.value) return
        scope.launch { if (dao.hasQueued()) schedule() }
    }

    /**
     * Failed songs always go back in the queue; ones marked as downloading only when the worker isn't
     * running, otherwise a second lane could pick up a song that's still being fetched.
     */
    private suspend fun requeueStale() {
        dao.requeueFailed()
        if (!workerRunning()) dao.requeueInterrupted()
    }

    private suspend fun workerRunning(): Boolean = withContext(Dispatchers.IO) {
        runCatching { WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrDefault(emptyList())
            .any { it.state == WorkInfo.State.RUNNING }
    }

    fun localFile(songId: String): File? = done[songId]?.let(::File)?.takeIf { it.exists() }

    fun isDownloaded(songId: String) = done.containsKey(songId)

    internal fun markDone(songId: String, path: String) { done[songId] = path }

    private suspend fun loadDone() {
        dao.allOnce().filter { it.state == DownloadState.DONE && it.path != null }.forEach { done[it.songId] = it.path!! }
    }

    /**
     * Stops the worker and waits (up to 15s) for it to finish, so nothing is half-written when the
     * caller carries on. Queued songs stay queued.
     */
    suspend fun stopWorker() {
        withContext(Dispatchers.IO) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME).result.get() }
        }
        active.values.forEach { it.cancel() }
        withTimeoutOrNull(15_000) { while (workerRunning()) delay(200) }
    }

    /**
     * Forgets what this session learned about downloads, for signing out. Downloaded files, the
     * downloads table and pins are kept; which songs are on the phone is read back from the table.
     */
    suspend fun reset() {
        attempts.clear()
        cancelled.clear()
        active.clear()
        _progress.value = emptyMap()
        _lastError.value = null
        backoffUntil.value = 0
        _backingOff.value = false
        _storageFull.value = false
        done.clear()
        loadDone()
    }

    /** The user downloaded these songs themselves: that wins over any album or playlist being removed. */
    fun download(songs: List<SongEntity>) = scope.launch {
        setPrefs(songs.map { it.id }, wanted = true)
        _storageFull.value = false
        enqueue(songs)
    }

    /** Queues songs that have no download row yet. Leaves storageFull alone: only the user clears that. */
    private suspend fun enqueue(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        val now = System.currentTimeMillis()
        cancelled.removeAll(songs.mapTo(HashSet()) { it.id })
        songs.chunked(500).forEachIndexed { c, chunk ->
            dao.insertIgnore(chunk.mapIndexed { i, s -> DownloadEntity(s.id, DownloadState.QUEUED, null, 0, now + c * 500 + i) })
        }
        if (!_storageFull.value) schedule() // a full phone waits for the user to free space and retry
    }

    /** The user removed these songs' downloads: syncing a downloaded album or playlist won't bring them back. */
    fun remove(songIds: List<String>) = scope.launch {
        setPrefs(songIds, wanted = false)
        deleteDownloads(songIds)
    }

    /** Deletes the rows before cancelling, so a lane that sees its call fail knows the user removed it. */
    private suspend fun deleteDownloads(songIds: List<String>) {
        if (songIds.isEmpty()) return
        cancelled.addAll(songIds)
        songIds.chunked(500).forEach { dao.delete(it) }
        songIds.forEach { id -> active.remove(id)?.cancel(); done.remove(id); File(dir, id).delete() }
    }

    private suspend fun setPrefs(songIds: List<String>, wanted: Boolean) {
        val now = System.currentTimeMillis()
        songIds.distinct().chunked(500).forEach { chunk -> dao.upsertPrefs(chunk.map { SongDownloadPrefEntity(it, wanted, now) }) }
    }

    private suspend fun prefMap(): Map<String, Boolean> = dao.prefs().associate { it.songId to it.wanted }

    /** Downloading an album or playlist (again) brings back songs of it the user had removed one by one. */
    fun pinAndDownload(kind: String, id: String, songs: List<SongEntity>) = scope.launch {
        dao.pin(PinnedEntity(kind, id))
        songs.map { it.id }.chunked(500).forEach { dao.clearRemoved(it) }
        _storageFull.value = false
        enqueue(songs)
    }

    /** What removing a collection's download would delete: [songIds] that nothing else keeps. */
    data class RemovalPlan(val songIds: List<String>, val bytes: Long)

    /**
     * Songs of the collection [kind]/[id] that would go if its download were removed: ones other
     * downloaded albums, playlists or Liked Songs need, or that the user downloaded on their own, stay.
     */
    suspend fun removalPlan(kind: String?, id: String?, songIds: List<String>): RemovalPlan {
        val rows = dao.allOnce().mapTo(HashSet()) { it.songId }
        // No collection (a genre, a mix): the user is removing these songs themselves, so all of them go.
        val ids = if (kind == null) songIds.distinct().filter { it in rows } else {
            val others = dao.pinned().filterNot { it.kind == kind && it.id == id }
            val stillWanted = others.flatMapTo(HashSet()) { p -> songsFor(p).map { it.id } }
            DownloadPolicy.removeOnUnpin(songIds, stillWanted, prefMap()).filter { it in rows }
        }
        val bytes = ids.chunked(500).sumOf { dao.bytesOf(it) }
        return RemovalPlan(ids, bytes)
    }

    /** Songs still wanted by other downloaded albums, playlists or Liked Songs are kept. */
    fun unpinAndRemove(kind: String, id: String, songIds: List<String>) = scope.launch {
        val plan = removalPlan(kind, id, songIds)
        dao.unpin(kind, id)
        deleteDownloads(plan.songIds)
    }

    /** A song was liked: if Liked Songs is downloaded, that brings back a download the user had removed. */
    suspend fun onLiked(songId: String) {
        if (dao.pinned().any { it.kind == KIND_LIKED }) dao.clearRemoved(listOf(songId))
        refreshPinned()
    }

    /**
     * After a sync: downloaded playlists the server no longer has are un-downloaded ([removedPlaylists]
     * maps their ids to the songs they held). After a complete full sync ([complete]), downloads of
     * songs the server no longer has are deleted; they'd be invisible but still take up space.
     */
    suspend fun afterSync(removedPlaylists: Map<String, List<String>>, complete: Boolean) {
        val pinnedPlaylists = dao.pinned().filter { it.kind == KIND_PLAYLIST }.mapTo(HashSet()) { it.id }
        for ((id, songIds) in removedPlaylists) {
            if (id !in pinnedPlaylists) continue
            val plan = removalPlan(KIND_PLAYLIST, id, songIds)
            dao.unpin(KIND_PLAYLIST, id)
            deleteDownloads(plan.songIds)
        }
        if (complete) {
            val orphans = dao.orphanIds()
            deleteDownloads(orphans)
            orphans.chunked(500).forEach { dao.deletePrefs(it) }
        }
        refreshPinned()
    }

    private suspend fun songsFor(p: PinnedEntity): List<SongEntity> {
        val lib = db.library()
        return when (p.kind) {
            KIND_PLAYLIST -> lib.playlistSongsOnce(p.id)
            KIND_ALBUM -> lib.albumSongsOnce(p.id)
            KIND_LIKED -> lib.starredSongsOnce()
            else -> emptyList()
        }
    }

    /**
     * After a library sync or a like, fetch songs of downloaded albums, playlists and Liked Songs that
     * aren't on the phone or on their way yet. Songs the user removed one by one are skipped.
     */
    suspend fun refreshPinned() = refreshLock.withLock {
        val pins = dao.pinned()
        if (pins.isEmpty()) return@withLock
        val wanted = LinkedHashMap<String, SongEntity>()
        pins.forEach { p -> songsFor(p).forEach { wanted.putIfAbsent(it.id, it) } }
        val existing = dao.allOnce().mapTo(HashSet()) { it.songId }
        enqueue(DownloadPolicy.toQueue(wanted.keys, existing, prefMap()).map(wanted::getValue))
    }

    fun removeAll() = scope.launch {
        dao.clearPinned()
        dao.clearPrefs()
        deleteDownloads(dao.allOnce().map { it.songId })
    }

    /**
     * Starts the download worker. A running worker gets one follow-up run lined up behind it, so a
     * song queued just as it finishes isn't left waiting until the app is next opened; one that's
     * waiting to start is replaced so it starts straight away. The Wi-Fi-only setting is checked by
     * the worker itself rather than Android's "unmetered" constraint, which a VPN like Tailscale can
     * switch on and off mid-download.
     */
    fun schedule() {
        if (session.credentials.value == null) return // nothing can download while signed out
        scope.launch(Dispatchers.IO) {
            val wm = WorkManager.getInstance(context)
            val infos = runCatching { wm.getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrDefault(emptyList())
            val running = infos.any { it.state == WorkInfo.State.RUNNING }
            val followUpWaiting = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            if (running && followUpWaiting) return@launch
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            wm.enqueueUniqueWork(WORK_NAME, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
        }
    }

    companion object {
        const val WORK_NAME = "downloads"
        const val KIND_ALBUM = "album"
        const val KIND_PLAYLIST = "playlist"
        const val KIND_LIKED = "liked"
    }
}

/** The worker's decisions that don't need Android, kept apart so they can be unit tested. */
internal object DownloadPolicy {
    const val MAX_CONNECTION_TRIES = 6
    const val BACKOFF_MS = 10_000L
    const val MAX_BACKOFF_MS = 120_000L
    /** Below this much free space, don't start another song. */
    const val MIN_FREE_BYTES = 500L * 1024 * 1024

    enum class Failure {
        /** Dropped connection: back of the queue, try again soon. */
        Transient,
        /** Phone is out of space: stop everything until the user acts. */
        StorageFull,
        /** This song won't download: mark it failed. */
        SongFailed,
        /** The user removed it: forget it, not a failure. */
        Cancelled,
    }

    /** [connectionTries] counts dropped connections for this song, including this one. */
    fun classify(e: Throwable, removedByUser: Boolean, connectionTries: Int): Failure = when {
        removedByUser -> Failure.Cancelled
        isStorageFull(e) -> Failure.StorageFull
        e is IOException && connectionTries < MAX_CONNECTION_TRIES -> Failure.Transient
        else -> Failure.SongFailed
    }

    fun isStorageFull(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(8).any { t ->
            val msg = t.message.orEmpty()
            msg.contains("ENOSPC") || msg.contains("No space left", ignoreCase = true)
        }

    /** How long every lane waits after [failuresInRow] dropped connections: none for the first two, then 10s doubling to 2 min. */
    fun backoffMs(failuresInRow: Int): Long =
        if (failuresInRow < 3) 0 else (BACKOFF_MS shl (failuresInRow - 3).coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MS)

    /** What an idle lane (no queued song) should do: keep waiting while other lanes are busy, since they may requeue songs. */
    fun idleLaneShouldExit(busyLanes: Int): Boolean = busyLanes <= 0

    /** Unique per attempt, so two fetches of the same song can never write into one file. */
    fun partName(songId: String, attempt: Long): String = "$songId.$attempt.part"

    /**
     * Whether a song should be on the phone. The user's choice for the song itself ([songPref]: true =
     * downloaded it, false = removed it, null = never said) wins over any downloaded album, playlist or
     * Liked Songs that holds it ([inDownloadedCollection]). Re-downloading a collection, or liking a
     * song while Liked Songs is downloaded, clears a "removed" choice so the collection applies again.
     */
    fun shouldKeep(songPref: Boolean?, inDownloadedCollection: Boolean): Boolean = songPref ?: inDownloadedCollection

    /** Songs of downloaded collections to queue: not already on the phone or on the way, and not removed by the user. */
    fun toQueue(collectionSongs: Collection<String>, existing: Set<String>, prefs: Map<String, Boolean>): List<String> =
        collectionSongs.filter { it !in existing && shouldKeep(prefs[it], inDownloadedCollection = true) }

    /** Which of a collection's songs to delete when its download is removed; [stillWanted] = songs other downloads hold. */
    fun removeOnUnpin(songIds: List<String>, stillWanted: Set<String>, prefs: Map<String, Boolean>): List<String> =
        songIds.distinct().filter { !shouldKeep(prefs[it], it in stillWanted) }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private enum class Halt { WIFI, STORAGE }

    override suspend fun doWork(): Result {
        val c = (applicationContext as HearthApp).container
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
        is okhttp3.internal.http2.StreamResetException -> "The server cut the download off"
        is java.net.SocketTimeoutException -> "The server took too long to respond"
        is java.net.UnknownHostException -> "Couldn't find the server (is Tailscale connected?)"
        is java.net.ConnectException -> "Couldn't connect to the server"
        is javax.net.ssl.SSLException -> "Secure connection problem: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }.let { im.flume.hearth.util.scrubSecrets(it) } // URLs carry the login token; never show them

    private suspend fun fetch(c: AppContainer, http: OkHttpClient, songId: String, dir: File, bitrate: Int): File =
        withContext(Dispatchers.IO) {
            val url = c.api.streamUrl(songId, bitrate) ?: error("Not logged in")
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
