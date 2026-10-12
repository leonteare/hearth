package im.flume.hearth.spoken

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import im.flume.hearth.BuildConfig
import im.flume.hearth.HearthApp
import im.flume.hearth.api.AbsAuthException
import im.flume.hearth.api.AbsClient
import im.flume.hearth.api.AbsHttpException
import im.flume.hearth.api.AbsMediaProgress
import im.flume.hearth.data.AppDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** Shown once by the UI: playback picked up from a position another device saved. */
data class ResumeNotice(
    val libraryItemId: String,
    val episodeId: String?,
    val title: String,
    val fromSec: Double,
    val toSec: Double,
) {
    val message: String get() = "Resumed from where you left off on another device"
}

/** Where to start an item, after reconciling this phone's progress with the server's. */
data class StartPoint(val positionSec: Double, val finished: Boolean, val notice: ResumeNotice?, val serverWon: Boolean = false)

/**
 * This user's listening progress. The phone is the source of truth for what was heard on it: every
 * change is saved locally first (with a server-clock timestamp) and queued in the outbox, which is
 * sent in order whenever the server is reachable, as Audiobookshelf "local sessions" (so its listening
 * stats stay right) or, for explicit changes like "mark finished", as progress updates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpokenProgress(
    private val context: Context,
    private val db: AppDatabase,
    private val store: AbsAccountStore,
    private val api: AbsClient,
    private val scope: CoroutineScope,
) {
    private val dao = db.spoken()
    /** Books from a folder on the phone: progress stays on the phone (see [LocalBookIds]). */
    private val localDao = db.localBooks()
    private val json = Json { ignoreUnknownKeys = true }
    private val flushLock = Mutex()
    private val writeLock = Mutex()

    private val _notices = MutableSharedFlow<ResumeNotice>(extraBufferCapacity = 4)
    /** "Resumed from where you left off on another device", for the UI to show as a snackbar. */
    val resumeNotices: SharedFlow<ResumeNotice> = _notices.asSharedFlow()

    /** Called when this user finishes an episode or book (here or, seen at sync, elsewhere). */
    var onFinished: (suspend (libraryItemId: String, episodeId: String) -> Unit)? = null

    /** Updates not yet sent to the server; 0 when everything is synced. */
    val pendingCount: Flow<Int> = store.account.flatMapLatest { a -> if (a == null) flowOf(0) else dao.outboxCount(a.userId) }

    fun serverNow(): Long = api.clock.serverNow()

    fun progressFlow(libraryItemId: String, episodeId: String?): Flow<SpokenProgressEntity?> =
        if (LocalBookIds.isLocal(libraryItemId)) localDao.progressFlow(libraryItemId).map { it?.asSpoken() }
        else dao.progressFlow(libraryItemId, episodeId.orEmpty())

    suspend fun local(libraryItemId: String, episodeId: String?): SpokenProgressEntity? =
        if (LocalBookIds.isLocal(libraryItemId)) localDao.progress(libraryItemId)?.asSpoken()
        else dao.progress(libraryItemId, episodeId.orEmpty())

    private fun LocalBookProgressEntity.asSpoken() =
        SpokenProgressEntity(bookId, "", positionSec, durationSec, finished, updatedAt, finishedAt, dirty = false, markedUnplayed = false)

    /** Progress on a phone book, for carrying over to the server when it's uploaded. */
    suspend fun localBookSnapshot(bookId: String): ProgressSnapshot? =
        localDao.progress(bookId)?.let { ProgressSnapshot(it.positionSec, it.durationSec, it.finished, it.updatedAt, it.finishedAt) }

    /**
     * Saves [p] as this user's progress on a server item and queues it for the server. Used when a
     * phone book was uploaded and its listening carries over to the server's copy.
     */
    suspend fun adopt(libraryItemId: String, p: ProgressSnapshot) {
        writeLock.withLock {
            dao.upsertProgress(SpokenProgressEntity(libraryItemId, "", p.currentTime, p.duration, p.isFinished, p.lastUpdate, p.finishedAt, dirty = true, markedUnplayed = false))
            enqueueProgress(libraryItemId, "", p)
        }
        flushSoon()
    }

    /** Saves progress on a phone book (no server involved). */
    private suspend fun recordLocal(bookId: String, positionSec: Double, durationSec: Double, finished: Boolean?) = writeLock.withLock {
        val old = localDao.progress(bookId)
        val now = serverNow()
        val isFinished = finished ?: (old?.finished == true || ProgressMerge.reachedEnd(positionSec, durationSec))
        localDao.upsertProgress(
            LocalBookProgressEntity(
                bookId, positionSec = positionSec, durationSec = durationSec.takeIf { it > 0 } ?: old?.durationSec ?: 0.0,
                finished = isFinished, finishedAt = if (isFinished) old?.finishedAt ?: now else null, updatedAt = now,
            )
        )
    }

    private fun SpokenProgressEntity.snapshot() = ProgressSnapshot(currentTime, duration, isFinished, lastUpdate, finishedAt)

    private fun AbsMediaProgress.snapshot() = ProgressSnapshot(currentTime, duration, isFinished, lastUpdate, finishedAt)

    /**
     * Where to start playing: the newer of this phone's and the server's position (the server is only
     * asked briefly; offline, the phone's is used). A finished item starts from the beginning and stays
     * finished. Emits a [ResumeNotice] when the server's position is newer and far from ours.
     */
    suspend fun resolveStart(libraryItemId: String, episodeId: String?, title: String, online: Boolean): StartPoint {
        if (LocalBookIds.isLocal(libraryItemId)) {
            val p = localDao.progress(libraryItemId) ?: return StartPoint(0.0, false, null)
            return StartPoint(if (p.finished) 0.0 else p.positionSec, p.finished, null)
        }
        val ep = episodeId.orEmpty()
        val server: AbsMediaProgress? = if (online && store.account.value?.needsSignIn == false) {
            withTimeoutOrNull(3_000) { runCatching { api.progress(libraryItemId, episodeId) }.getOrNull() }
        } else null
        val result = writeLock.withLock {
            val local = dao.progress(libraryItemId, ep)
            val merge = ProgressMerge.merge(local?.snapshot(), server?.snapshot(), local?.markedUnplayed == true)
                ?: return@withLock StartPoint(0.0, false, null)
            if (server != null || local == null) applyMerge(libraryItemId, ep, local, merge, queuePush = merge.pushToServer && server != null)
            val notice = if (merge.resumedElsewhere && local != null) {
                ResumeNotice(libraryItemId, episodeId, title, local.currentTime, merge.merged.currentTime)
            } else null
            StartPoint(if (merge.merged.isFinished) 0.0 else merge.merged.currentTime, merge.merged.isFinished, notice,
                serverWon = server != null && merge.winner == ProgressSource.SERVER)
        }
        result.notice?.let { _notices.tryEmit(it) }
        return result
    }

    private suspend fun applyMerge(id: String, ep: String, local: SpokenProgressEntity?, merge: MergeResult, queuePush: Boolean) {
        val m = merge.merged
        val unplayedPending = local?.markedUnplayed == true && merge.winner == ProgressSource.LOCAL
        dao.upsertProgress(
            SpokenProgressEntity(id, ep, m.currentTime, m.duration, m.isFinished, m.lastUpdate, m.finishedAt,
                dirty = merge.pushToServer, markedUnplayed = unplayedPending)
        )
        if (queuePush && dao.outboxCountFor(id, ep) == 0) enqueueProgress(id, ep, m)
        if (m.isFinished && local?.isFinished != true) onFinished?.invoke(id, ep)
    }

    /** After a sync: reconciles every progress row the server knows about with this phone's. */
    suspend fun applyServer(list: List<AbsMediaProgress>) = writeLock.withLock {
        for (p in list) {
            val ep = p.episodeId.orEmpty()
            val local = dao.progress(p.libraryItemId, ep)
            val merge = ProgressMerge.merge(local?.snapshot(), p.snapshot(), local?.markedUnplayed == true) ?: continue
            // Sessions still waiting in the outbox will bring the server up to date on their own.
            applyMerge(p.libraryItemId, ep, local, merge, queuePush = merge.pushToServer)
        }
    }

    /**
     * Saves the position heard on this phone (every few seconds while playing, and on pause/stop).
     * Reaching the end marks it finished, which is sent to the server straight away.
     */
    suspend fun record(libraryItemId: String, episodeId: String?, positionSec: Double, durationSec: Double) {
        if (LocalBookIds.isLocal(libraryItemId)) recordLocal(libraryItemId, positionSec, durationSec, null) else recordServer(libraryItemId, episodeId, positionSec, durationSec)
    }

    private suspend fun recordServer(libraryItemId: String, episodeId: String?, positionSec: Double, durationSec: Double) = writeLock.withLock {
        val ep = episodeId.orEmpty()
        val old = dao.progress(libraryItemId, ep)
        val now = serverNow()
        val justFinished = old?.isFinished != true && ProgressMerge.reachedEnd(positionSec, durationSec)
        val finished = old?.isFinished == true || justFinished
        val row = SpokenProgressEntity(
            libraryItemId, ep,
            currentTime = positionSec, duration = durationSec.takeIf { it > 0 } ?: old?.duration ?: 0.0,
            isFinished = finished, lastUpdate = now,
            finishedAt = if (justFinished) now else old?.finishedAt,
            dirty = true, markedUnplayed = old?.markedUnplayed == true && !justFinished,
        )
        dao.upsertProgress(row)
        if (justFinished) {
            enqueueProgress(libraryItemId, ep, row.snapshot())
            onFinished?.invoke(libraryItemId, ep)
            scheduleFlush()
        }
    }

    /** Explicit "mark as finished" / "mark as unplayed" from the UI. */
    suspend fun setFinished(libraryItemId: String, episodeId: String?, finished: Boolean, durationSec: Double) {
        if (LocalBookIds.isLocal(libraryItemId)) {
            val old = localDao.progress(libraryItemId)
            recordLocal(libraryItemId, if (finished) old?.positionSec ?: 0.0 else 0.0, durationSec, finished)
            return
        }
        writeLock.withLock {
            val ep = episodeId.orEmpty()
            val old = dao.progress(libraryItemId, ep)
            val now = serverNow()
            val row = SpokenProgressEntity(
                libraryItemId, ep,
                currentTime = if (finished) old?.currentTime ?: 0.0 else 0.0,
                duration = durationSec.takeIf { it > 0 } ?: old?.duration ?: 0.0,
                isFinished = finished, lastUpdate = now, finishedAt = if (finished) old?.finishedAt ?: now else null,
                dirty = true, markedUnplayed = !finished,
            )
            dao.upsertProgress(row)
            enqueueProgress(libraryItemId, ep, row.snapshot())
            if (finished && old?.isFinished != true) onFinished?.invoke(libraryItemId, ep)
        }
        flushSoon()
    }

    /** Queues (or updates) the listening session being recorded on this phone. */
    suspend fun recordSession(session: LocalSessionPayload.Session, finished: Boolean) {
        if (LocalBookIds.isLocal(session.libraryItemId)) return // the server doesn't know this book
        val user = store.account.value?.userId ?: return
        if (session.timeListening < 1.0) return // nothing heard yet: nothing for the stats
        val payload = LocalSessionPayload.build(session, finished, deviceInfo())
        dao.enqueueOutbox(
            OutboxEntity("session:${session.id}", 0, user, OutboxKind.SESSION.name, session.libraryItemId, session.episodeId.orEmpty(),
                payload.toString(), 0, System.currentTimeMillis())
        )
    }

    private suspend fun enqueueProgress(id: String, ep: String, p: ProgressSnapshot) {
        val user = store.account.value?.userId ?: return
        dao.enqueueOutbox(
            OutboxEntity("progress:$id/$ep", 0, user, OutboxKind.PROGRESS.name, id, ep,
                LocalSessionPayload.progressBody(p).toString(), 0, System.currentTimeMillis())
        )
    }

    fun deviceInfo(): JsonObject = buildJsonObject {
        put("deviceId", store.deviceId)
        put("clientName", "Hearth")
        put("clientVersion", BuildConfig.VERSION_NAME)
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("sdkVersion", Build.VERSION.SDK_INT)
    }

    /**
     * Sends the outbox in order. Stops at the first network failure (so nothing overtakes an older
     * update) and returns false; the rest goes next time. Updates the server rejects for good (e.g.
     * the episode was deleted) are dropped after a few tries.
     */
    suspend fun flush(): Boolean = flushLock.withLock {
        val account = store.account.value ?: return@withLock false
        if (account.needsSignIn) return@withLock false
        val rows = dao.outbox(account.userId)
        if (rows.isEmpty()) return@withLock true
        val byKey = rows.associateBy { it.key }
        for (batch in OutboxPlan.batches(rows.map { OutboxItem(it.key, it.seq, OutboxKind.valueOf(it.kind)) })) {
            val batchRows = batch.mapNotNull { byKey[it.key] }
            try {
                if (batch.first().kind == OutboxKind.SESSION) {
                    val results = api.syncLocalSessions(batchRows.map { json.parseToJsonElement(it.payload).jsonObject }, deviceInfo()).associateBy { it.id }
                    for (row in batchRows) {
                        val r = results[row.key.removePrefix("session:")]
                        when {
                            r?.success == true -> sent(row)
                            // "Media item not found": the episode or book is gone from the server.
                            r?.error?.contains("not found", ignoreCase = true) == true -> sent(row)
                            row.attempts + 1 >= OutboxPlan.MAX_REJECTED_ATTEMPTS -> sent(row)
                            else -> dao.bumpOutboxAttempts(row.key)
                        }
                    }
                } else {
                    val row = batchRows.single()
                    api.patchProgress(row.libraryItemId, row.episodeId.ifEmpty { null }, json.parseToJsonElement(row.payload).jsonObject)
                    sent(row)
                }
            } catch (e: AbsHttpException) {
                if (!OutboxPlan.isPermanentRejection(e.code)) return@withLock false
                batchRows.forEach { row ->
                    if (row.attempts + 1 >= OutboxPlan.MAX_REJECTED_ATTEMPTS || e.code == 404) sent(row) else dao.bumpOutboxAttempts(row.key)
                }
            } catch (e: AbsAuthException) {
                return@withLock false
            } catch (e: IOException) {
                return@withLock false
            }
        }
        dao.outbox(account.userId).isEmpty()
    }

    /** Removes a sent row (unless it was replaced meanwhile) and clears the progress's "unsent" flags once nothing's left for it. */
    private suspend fun sent(row: OutboxEntity) {
        dao.deleteOutbox(row.key, row.seq)
        if (dao.outboxCountFor(row.libraryItemId, row.episodeId) == 0) dao.markProgressSynced(row.libraryItemId, row.episodeId)
    }

    /** Sends the outbox in the background; if that fails, leaves it to [OutboxWorker]. */
    fun flushSoon() {
        scope.launch { if (!flush()) scheduleFlush() }
    }

    /** Makes sure the outbox is sent once there's a connection, even if the app is closed meanwhile. */
    fun scheduleFlush() {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                OUTBOX_WORK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<OutboxWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }

    /** Signing out or switching account: this phone's copy of the old account's progress goes (its unsent updates stay, tagged with that user). */
    suspend fun clearLocal() = dao.clearProgress()

    companion object {
        const val OUTBOX_WORK = "abs-outbox"
    }
}

/** Sends queued progress once there's a connection; retried with backoff while the server can't be reached. */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val spoken = (applicationContext as HearthApp).container.spoken
        if (spoken.store.account.value == null) return Result.success()
        return if (spoken.progress.flush()) Result.success() else if (runAttemptCount < 20) Result.retry() else Result.success()
    }
}
