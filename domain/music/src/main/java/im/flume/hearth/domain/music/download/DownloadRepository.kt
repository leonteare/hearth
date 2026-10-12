package im.flume.hearth.domain.music.download

import android.content.Context
import im.flume.hearth.core.data.AppDatabase
import im.flume.hearth.core.data.DownloadCounts
import im.flume.hearth.core.data.DownloadEntity
import im.flume.hearth.core.data.DownloadState
import im.flume.hearth.core.data.PinnedEntity
import im.flume.hearth.core.data.SessionStore
import im.flume.hearth.core.data.SongEntity
import im.flume.hearth.core.common.logHandledError
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call

/**
 * Songs kept on the phone for offline play. Files live in app-private storage; the playback data
 * source checks [localFile] before streaming, so a downloaded song never touches the network.
 * Starting and waking the worker lives in DownloadScheduling.kt, reading pins in DownloadPins.kt.
 */
class DownloadRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val session: SessionStore,
    private val scope: CoroutineScope,
) {
    private val dao = db.downloads()
    private val pins = DownloadPins(db)
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
        pins.setPrefs(ids, wanted = false)
        dao.deletePending()
        ids.forEach { active.remove(it)?.cancel() }
    }
    val dir: File = File(context.filesDir, "music").apply { mkdirs() }

    /** Every song's download state; quiet while a download only makes progress (see [toStateMap]). */
    val states: Flow<Map<String, DownloadState>> = dao.states().toStateMap().flowOn(Dispatchers.Default)
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
        resumeWhenWifiArrives(context, session, ::resumeIfWaiting)
        resumeOnAppStart(scope, ::resumeIfWaiting)
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
        if (!downloadWorkerRunning(context)) dao.requeueInterrupted()
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
        cancelDownloadWorker(context)
        active.values.forEach { it.cancel() }
        withTimeoutOrNull(15_000) { while (downloadWorkerRunning(context)) delay(200) }
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

    /** Told about every download the user asks for (mix songs they download are kept on the server). */
    var onUserDownload: (suspend (List<String>) -> Unit)? = null

    private suspend fun tellUserDownload(songIds: List<String>) {
        try { onUserDownload?.invoke(songIds) } catch (e: Exception) { logHandledError(context, "keep mix songs", e) }
    }

    /** The user downloaded these songs themselves: that wins over any album or playlist being removed. */
    fun download(songs: List<SongEntity>) = scope.launch {
        tellUserDownload(songs.map { it.id })
        pins.setPrefs(songs.map { it.id }, wanted = true)
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
        pins.setPrefs(songIds, wanted = false)
        deleteDownloads(songIds)
    }

    /** Deletes the rows before cancelling, so a lane that sees its call fail knows the user removed it. */
    private suspend fun deleteDownloads(songIds: List<String>) {
        if (songIds.isEmpty()) return
        cancelled.addAll(songIds)
        songIds.chunked(500).forEach { dao.delete(it) }
        songIds.forEach { id -> active.remove(id)?.cancel(); done.remove(id); File(dir, id).delete() }
    }

    /** Downloading an album or playlist (again) brings back songs of it the user had removed one by one. */
    fun pinAndDownload(kind: String, id: String, songs: List<SongEntity>) = scope.launch {
        tellUserDownload(songs.map { it.id })
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
    suspend fun removalPlan(kind: String?, id: String?, songIds: List<String>): RemovalPlan = pins.removalPlan(kind, id, songIds)

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

    /**
     * After a library sync or a like, fetch songs of downloaded albums, playlists and Liked Songs that
     * aren't on the phone or on their way yet. Songs the user removed one by one are skipped.
     */
    suspend fun refreshPinned() = refreshLock.withLock { enqueue(pins.missingSongs()) }

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
        scope.launch(Dispatchers.IO) { enqueueDownloadWorker(context) }
    }

    companion object {
        const val WORK_NAME = "downloads"
        const val KIND_ALBUM = "album"
        const val KIND_PLAYLIST = "playlist"
        const val KIND_LIKED = "liked"
    }
}
