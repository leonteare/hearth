// How a request moves along as Tidarr's queue changes (pure, unit tested).
package im.flume.hearth.data

import im.flume.hearth.api.TidarrQueueItem

/** How a request moves along as Tidarr's queue changes. Pure, for testing. */
object RequestTracking {
    /** Tidarr's queue status → ours. Unknown words leave the request where it is (null). */
    fun mapStatus(tidarrStatus: String?, error: Boolean = false): RequestStatus? = when {
        error -> RequestStatus.FAILED
        else -> when (tidarrStatus?.lowercase()) {
            "queue_download" -> RequestStatus.REQUESTED
            "download", "queue_processing", "processing" -> RequestStatus.DOWNLOADING
            "finished" -> RequestStatus.ADDING
            "error" -> RequestStatus.FAILED
            else -> null
        }
    }

    /** A request that just went in is given this long to show up in the queue before its absence means "done". */
    const val QUEUE_GRACE_MS = 60_000L

    const val FAILED_MESSAGE = "Tidarr couldn't download this — is the Tidal subscription active?"

    /** What requests that never turned up on the server used to be marked with (as done). */
    const val NOT_MATCHED = "Added to the server — couldn't match it automatically"

    /** Tidarr says "finished" even when tiddl fetched nothing (e.g. a free Tidal account), so this is a failure. */
    const val NOTHING_ARRIVED = "Tidarr finished, but nothing arrived on the server. Retry, or check Tidarr's page."

    /** Waiting this long after Tidarr finished, with nothing found, means nothing is coming. */
    const val ADDING_TIMEOUT_MS = 30 * 60_000L

    /** How a retry gets Tidarr to really download again. */
    enum class RetryAction {
        /** Not in Tidarr's queue: add it. */
        SAVE,
        /** Failed there: Tidarr retries its failed items. */
        RETRY_FAILED,
        /** Listed with any other status (usually "finished" with nothing downloaded): take it out and add it again. */
        REMOVE_AND_SAVE,
    }

    fun retryAction(item: TidarrQueueItem?): RetryAction = when {
        item == null -> RetryAction.SAVE
        item.error || item.status.equals("error", ignoreCase = true) -> RetryAction.RETRY_FAILED
        else -> RetryAction.REMOVE_AND_SAVE
    }

    /** The request once it has been sent again: back to the start of its journey. */
    fun afterRetry(r: RequestEntity, action: RetryAction, now: Long): RequestEntity = r.copy(
        status = RequestStatus.REQUESTED, errorMessage = null, matchedIds = null, updatedAt = now,
        // A retried failed item is still listed; a re-added one has to show up first.
        seenInQueue = action == RetryAction.RETRY_FAILED, finishedAt = null, lookupAttempts = 0,
    )

    /** Tidarr finished but nothing turned up on the server. */
    fun gaveUp(r: RequestEntity, now: Long): RequestEntity =
        r.copy(status = RequestStatus.FAILED, errorMessage = NOTHING_ARRIVED, updatedAt = now)

    /** Rows from before this was treated as a failure (done, "couldn't match"), as they should be; null if fine. */
    fun reclassified(r: RequestEntity): RequestEntity? =
        if (r.status == RequestStatus.DONE && r.errorMessage == NOT_MATCHED) r.copy(status = RequestStatus.FAILED, errorMessage = NOTHING_ARRIVED) else null

    /** True when Tidarr finished over [ADDING_TIMEOUT_MS] ago and the request is still waiting for the server. */
    fun addingTimedOut(r: RequestEntity, now: Long): Boolean =
        r.status == RequestStatus.ADDING && now - (r.finishedAt ?: r.updatedAt) > ADDING_TIMEOUT_MS

    /**
     * The request after looking at Tidarr's queue ([item] is its row there, or null if it isn't
     * listed). Only REQUESTED/DOWNLOADING requests change here; ADDING is handled by the lookup.
     * An item that was seen and then vanished finished (or was cleared), so it's looked for on the server.
     */
    fun afterQueue(r: RequestEntity, item: TidarrQueueItem?, now: Long): RequestEntity {
        if (r.status != RequestStatus.REQUESTED && r.status != RequestStatus.DOWNLOADING) return r
        val next = if (item != null) {
            mapStatus(item.status, item.error) ?: r.status
        } else if (r.seenInQueue || now - r.updatedAt > QUEUE_GRACE_MS) {
            RequestStatus.ADDING
        } else r.status
        val seen = r.seenInQueue || item != null
        if (next == r.status && seen == r.seenInQueue) return r
        return r.copy(
            status = next,
            seenInQueue = seen,
            updatedAt = now,
            finishedAt = if (next == RequestStatus.ADDING) now else r.finishedAt,
            lookupAttempts = if (next == RequestStatus.ADDING) 0 else r.lookupAttempts,
            errorMessage = if (next == RequestStatus.FAILED) FAILED_MESSAGE + (item?.errorStage?.let { " (failed while $it)" } ?: "") else null,
        )
    }

    /** Minutes after Tidarr finished at which the server is searched again; the last one gives up. */
    private val LOOKUP_SCHEDULE_MS = listOf(0L, 1, 3, 6, 10, 15).map { it * 60_000L }

    /** True when it's time for another look on the server. */
    fun lookupDue(r: RequestEntity, now: Long): Boolean {
        val finished = r.finishedAt ?: return true
        val wait = LOOKUP_SCHEDULE_MS.getOrElse(r.lookupAttempts) { return false }
        return now - finished >= wait
    }

    /** True when the last scheduled look has been made without finding it. */
    fun lookupExhausted(r: RequestEntity): Boolean = r.lookupAttempts >= LOOKUP_SCHEDULE_MS.size
}
