// Likes made with no connection: the queue of changes waiting for the server, pushed when it answers.
package im.flume.hearth.domain.music

import im.flume.hearth.core.common.SubsonicException
import im.flume.hearth.core.data.AppDatabase
import im.flume.hearth.core.data.LikeKind
import im.flume.hearth.core.data.LikeOutboxEntity
import im.flume.hearth.core.data.NetworkMonitor
import im.flume.hearth.core.data.SessionStore
import im.flume.hearth.core.network.SubsonicClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Liking a song, album or artist takes effect on the phone straight away and is queued here, so a
 * like made on a train with no signal is kept and told to the server later rather than thrown away.
 * The queue is pushed after each new like, when the app starts and whenever the server comes back.
 */
class LikeOutbox(
    db: AppDatabase,
    private val api: SubsonicClient,
    private val network: NetworkMonitor,
    private val session: SessionStore,
    private val scope: CoroutineScope,
) {
    private val dao = db.likeOutbox()
    private val lock = Mutex()

    /** Remembers a like or unlike, then pushes it (and anything already waiting) as soon as it can. */
    suspend fun queue(kind: LikeKind, id: String, liked: Boolean) {
        dao.put(LikeOutboxEntity(kind, id, liked, System.currentTimeMillis()))
        flushSoon()
    }

    /** Pushes on the app's own scope, so leaving the screen can't cancel it half way. */
    fun flushSoon() {
        scope.launch { flush() }
    }

    /** Pushes what's waiting now (the app has just started), and again each time the server comes back. */
    fun start() {
        flushSoon()
        scope.launch { network.isOnline.filter { it }.collect { flush() } }
    }

    /**
     * Sends the queued changes oldest first and says whether the queue emptied. The first change
     * that can't reach the server ends the run, so a newer like never overtakes an older one; a
     * change the server understood and refused is dropped, since pushing it again would never work.
     */
    suspend fun flush(): Boolean = lock.withLock {
        if (session.credentials.value == null || network.cannotStream) return@withLock false
        for (item in LikeOutboxPlan.pending(dao.all().map { it.toPending() })) {
            val next = try {
                push(item)
                LikeOutboxPlan.Next.SENT
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubsonicException) {
                LikeOutboxPlan.next(ok = false, code = e.code)
            } catch (e: IOException) {
                LikeOutboxPlan.Next.STOP
            }
            when (next) {
                LikeOutboxPlan.Next.SENT, LikeOutboxPlan.Next.DROP -> dao.delete(item.kind, item.id, item.changedAt)
                LikeOutboxPlan.Next.STOP -> return@withLock false
            }
        }
        true
    }

    private suspend fun push(item: PendingLike) = when (item.kind) {
        LikeKind.SONG -> if (item.liked) api.star(item.id) else api.unstar(item.id)
        LikeKind.ALBUM -> if (item.liked) api.starAlbum(item.id) else api.unstarAlbum(item.id)
        LikeKind.ARTIST -> if (item.liked) api.starArtist(item.id) else api.unstarArtist(item.id)
    }
}

/** A queued like without the Room bits, so the decisions about it are plain values. */
data class PendingLike(val kind: LikeKind, val id: String, val liked: Boolean, val changedAt: Long)

internal fun LikeOutboxEntity.toPending() = PendingLike(kind, id, liked, changedAt)

/** The decisions behind pushing queued likes, kept pure so they can be tested on the JVM. */
object LikeOutboxPlan {
    /**
     * The queue to push: one change per thing, the newest kept (unliking and liking again leaves a
     * single change, not two), oldest first, so the server ends up where the listener left off.
     */
    fun pending(items: List<PendingLike>): List<PendingLike> =
        items.groupBy { it.kind to it.id }
            .map { (_, same) -> same.reduce { kept, next -> if (next.changedAt >= kept.changedAt) next else kept } }
            .sortedWith(compareBy({ it.changedAt }, { it.kind.name }, { it.id }))

    /** What to do with a queued change after trying to push it. */
    enum class Next {
        /** The server took it: forget the row. */
        SENT,

        /** The server refused it for good: forget the row rather than retry for ever. */
        DROP,

        /** The server couldn't be reached: this change and everything behind it waits for next time. */
        STOP,
    }

    /**
     * [ok] is whether the server took the change; [code] is the status (or Subsonic error number) it
     * answered with, and null when it couldn't be reached at all.
     */
    fun next(ok: Boolean, code: Int?): Next = when {
        ok -> Next.SENT
        code != null && isPermanentRejection(code) -> Next.DROP
        else -> Next.STOP
    }

    /** Subsonic error 70: the song, album or artist isn't on the server any more. */
    const val NOT_FOUND = 70

    /** Statuses worth another try: the sign-in may be renewed, a timeout or a rate limit may pass. */
    private val RETRYABLE = setOf(401, 403, 408, 429)

    /**
     * True for a 4xx the server meant (not a sign-in, timeout or rate-limit one) and for Subsonic's
     * own "not found": the server understood the change and refused it, so it can be forgotten.
     */
    fun isPermanentRejection(code: Int): Boolean = code == NOT_FOUND || (code in 400..499 && code !in RETRYABLE)

    /** The server's starred ids for [kind], corrected by the changes still waiting to be pushed. */
    fun merge(serverIds: List<String>, items: List<PendingLike>, kind: LikeKind): List<String> {
        val mine = likedByIds(items, kind)
        if (mine.isEmpty()) return serverIds
        val kept = serverIds.filterTo(LinkedHashSet()) { mine[it] != false }
        return kept.toList() + mine.filterValues { it }.keys.filterNot { it in kept }
    }

    /** The pending changes for one [kind], as id to whether it ended up liked. */
    fun likedByIds(items: List<PendingLike>, kind: LikeKind): Map<String, Boolean> =
        pending(items).filter { it.kind == kind }.associate { it.id to it.liked }
}
