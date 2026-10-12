// Asking Tidarr for music and changing a request afterwards: request, retry, cancel, drop part of it.
package im.flume.hearth.domain.requests

import im.flume.hearth.core.data.RequestEntity
import im.flume.hearth.core.data.RequestStatus
import im.flume.hearth.core.network.TidalType
import im.flume.hearth.domain.requests.RequestsRepository.Outcome

/*
 * The bodies of [RequestsRepository]'s request methods. Extensions on it, since it owns the state they use.
 */

/** A new request row for this user, not sent anywhere yet. */
internal fun RequestsRepository.draftRequest(
    type: TidalType, id: String, title: String, artist: String, cover: String?, isrc: String?,
    albumTitle: String?, durationSec: Int?, now: Long,
) = RequestEntity(
    tidalId = id, type = type.api, title = title, artist = artist, coverUuid = cover, isrc = isrc,
    albumTitle = albumTitle, durationSec = durationSec, requestedAt = now, requestedBy = me.orEmpty(),
    status = RequestStatus.REQUESTED, errorMessage = null, matchedIds = null, updatedAt = now,
    seenInQueue = false, finishedAt = null, lookupAttempts = 0,
)

/** The body of [RequestsRepository.request]. */
internal suspend fun RequestsRepository.submit(draft: RequestEntity, like: Boolean, toPhone: Boolean): Outcome {
    val existing = dao.get(draft.type, draft.tidalId)?.takeIf(::mine)
    if (existing != null && existing.status.active) {
        val merged = RequestIntent.merge(existing, like, toPhone)
        if (merged == existing) return Outcome.ALREADY_REQUESTED
        dao.upsert(merged.copy(updatedAt = System.currentTimeMillis()))
        return Outcome.UPDATED
    }
    if (existing != null && existing.status == RequestStatus.FAILED) {
        retry(RequestIntent.merge(existing, like, toPhone))
        return Outcome.REQUESTED
    }
    @Suppress("NAME_SHADOWING") val draft = draft.copy(like = like, toPhone = toPhone)
    if (draft.type != TidalType.ARTIST.api) {
        val found = runCatching { findOnServer(draft) }.getOrNull()
        if (found != null) {
            complete(draft.copy(status = RequestStatus.ADDING), found)
            runCatching { downloads.refreshPinned() }
            return Outcome.ALREADY_ON_SERVER
        }
    }
    // The other phone (or Tidarr's own page) may have asked for it already; ids must be unique there.
    val inQueue = runCatching { tidarr.queue() }.getOrNull()?.firstOrNull { it.id == draft.tidalId }
    val failedThere = inQueue != null && (inQueue.error || inQueue.status == "error")
    when {
        inQueue == null -> tidarr.save(TidalType.of(draft.type), draft.tidalId, draft.title, draft.artist)
        failedThere -> tidarr.retryFailed()
    }
    val now = System.currentTimeMillis()
    var row = draft.copy(requestedBy = me.orEmpty(), requestedAt = now, updatedAt = now, seenInQueue = inQueue != null)
    // Already in Tidarr's queue (and not failed, or just retried): pick up where it is.
    if (inQueue != null && !failedThere) row = RequestTracking.afterQueue(row, inQueue, now)
    dao.upsert(row)
    ensureTracking()
    return Outcome.REQUESTED
}

/** The body of [RequestsRepository.retry]. */
internal suspend fun RequestsRepository.resend(r: RequestEntity) {
    val inQueue = tidarr.queue().firstOrNull { it.id == r.tidalId }
    val action = RequestTracking.retryAction(inQueue)
    when (action) {
        RequestTracking.RetryAction.RETRY_FAILED -> tidarr.retryFailed()
        RequestTracking.RetryAction.REMOVE_AND_SAVE -> {
            tidarr.remove(r.tidalId)
            tidarr.save(TidalType.of(r.type), r.tidalId, r.title, r.artist)
        }
        RequestTracking.RetryAction.SAVE -> tidarr.save(TidalType.of(r.type), r.tidalId, r.title, r.artist)
    }
    dao.upsert(RequestTracking.afterRetry(r, action, System.currentTimeMillis()))
    ensureTracking()
}

/** The body of [RequestsRepository.cancel]. */
internal suspend fun RequestsRepository.stop(r: RequestEntity) {
    if (r.status == RequestStatus.REQUESTED || r.status == RequestStatus.DOWNLOADING || r.status == RequestStatus.FAILED) {
        runCatching { tidarr.remove(r.tidalId) }.onFailure { if (r.status != RequestStatus.FAILED) throw it }
    }
    dao.delete(r.type, r.tidalId)
}

/** The body of [RequestsRepository.dropIntent]. */
internal suspend fun RequestsRepository.dropPart(r: RequestEntity, like: Boolean, toPhone: Boolean): Boolean {
    val current = dao.get(r.type, r.tidalId) ?: r
    val next = RequestIntent.without(current, dropLike = like, dropToPhone = toPhone)
    if (next == null) { cancel(current); return true }
    dao.upsert(next.copy(updatedAt = System.currentTimeMillis()))
    return false
}
