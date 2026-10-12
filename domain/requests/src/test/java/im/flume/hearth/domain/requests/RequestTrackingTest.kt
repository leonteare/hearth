// Tests for RequestTracking: how a request moves along as Tidarr's queue changes.
package im.flume.hearth.domain.requests

import im.flume.hearth.core.network.TidarrQueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import im.flume.hearth.core.data.RequestEntity
import im.flume.hearth.core.data.RequestStatus
import im.flume.hearth.domain.music.rows

class RequestTrackingTest {
    private fun req(status: RequestStatus, seen: Boolean = false, updatedAt: Long = 0, finishedAt: Long? = null, attempts: Int = 0) = RequestEntity(
        tidalId = "1", type = "track", title = "T", artist = "A", coverUuid = null, isrc = null, albumTitle = null,
        durationSec = null, requestedAt = 0, requestedBy = "leon", status = status, errorMessage = null, matchedIds = null,
        updatedAt = updatedAt, seenInQueue = seen, finishedAt = finishedAt, lookupAttempts = attempts,
    )

    private fun item(status: String, error: Boolean = false, stage: String? = null) = TidarrQueueItem("1", "track", "T", "A", status, error, stage)

    @Test
    fun `tidarr statuses map onto ours`() {
        assertEquals(RequestStatus.REQUESTED, RequestTracking.mapStatus("queue_download"))
        assertEquals(RequestStatus.DOWNLOADING, RequestTracking.mapStatus("download"))
        assertEquals(RequestStatus.DOWNLOADING, RequestTracking.mapStatus("queue_processing"))
        assertEquals(RequestStatus.DOWNLOADING, RequestTracking.mapStatus("processing"))
        assertEquals(RequestStatus.ADDING, RequestTracking.mapStatus("finished"))
        assertEquals(RequestStatus.FAILED, RequestTracking.mapStatus("error"))
        assertEquals(RequestStatus.FAILED, RequestTracking.mapStatus("download", error = true))
        assertNull(RequestTracking.mapStatus("something_new"))
    }

    @Test
    fun `queue moves a request along`() {
        val r = req(RequestStatus.REQUESTED)
        val downloading = RequestTracking.afterQueue(r, item("download"), now = 10)
        assertEquals(RequestStatus.DOWNLOADING, downloading.status)
        assertTrue(downloading.seenInQueue)
        val finished = RequestTracking.afterQueue(downloading, item("finished"), now = 20)
        assertEquals(RequestStatus.ADDING, finished.status)
        assertEquals(20L, finished.finishedAt)
        val failed = RequestTracking.afterQueue(downloading, item("error", error = true, stage = "download"), now = 30)
        assertEquals(RequestStatus.FAILED, failed.status)
        assertTrue(failed.errorMessage!!.startsWith(RequestTracking.FAILED_MESSAGE))
    }

    @Test
    fun `vanishing from the queue means finished once seen or after a grace period`() {
        assertEquals(RequestStatus.ADDING, RequestTracking.afterQueue(req(RequestStatus.DOWNLOADING, seen = true), null, now = 5).status)
        // Just requested and not listed yet: wait.
        val fresh = req(RequestStatus.REQUESTED, updatedAt = 1_000)
        assertEquals(fresh, RequestTracking.afterQueue(fresh, null, now = 2_000))
        // Never seen for over a minute: it finished and was cleared before we looked.
        assertEquals(RequestStatus.ADDING, RequestTracking.afterQueue(fresh, null, now = 1_000 + RequestTracking.QUEUE_GRACE_MS + 1).status)
        // Requests already past Tidarr aren't touched.
        val adding = req(RequestStatus.ADDING, finishedAt = 0)
        assertEquals(adding, RequestTracking.afterQueue(adding, item("download"), now = 9))
    }

    @Test
    fun `lookups follow a schedule then give up`() {
        val r = req(RequestStatus.ADDING, finishedAt = 0)
        assertTrue(RequestTracking.lookupDue(r, now = 0))
        assertFalse(RequestTracking.lookupDue(r.copy(lookupAttempts = 1), now = 30_000))
        assertTrue(RequestTracking.lookupDue(r.copy(lookupAttempts = 1), now = 60_000))
        assertTrue(RequestTracking.lookupDue(r.copy(lookupAttempts = 5), now = 15 * 60_000L))
        assertFalse(RequestTracking.lookupExhausted(r.copy(lookupAttempts = 5)))
        assertTrue(RequestTracking.lookupExhausted(r.copy(lookupAttempts = 6)))
        assertFalse(RequestTracking.lookupDue(r.copy(lookupAttempts = 6), now = Long.MAX_VALUE))
    }

    @Test
    fun `retry re-adds what tidarr calls finished, and retries what failed there`() {
        assertEquals(RequestTracking.RetryAction.SAVE, RequestTracking.retryAction(null))
        assertEquals(RequestTracking.RetryAction.RETRY_FAILED, RequestTracking.retryAction(item("error")))
        assertEquals(RequestTracking.RetryAction.RETRY_FAILED, RequestTracking.retryAction(item("download", error = true)))
        assertEquals(RequestTracking.RetryAction.REMOVE_AND_SAVE, RequestTracking.retryAction(item("finished")))
        assertEquals(RequestTracking.RetryAction.REMOVE_AND_SAVE, RequestTracking.retryAction(item("queue_download")))
    }

    @Test
    fun `a retried request starts over`() {
        val failed = req(RequestStatus.FAILED, seen = true, finishedAt = 5, attempts = 6).copy(errorMessage = RequestTracking.NOTHING_ARRIVED)
        val readded = RequestTracking.afterRetry(failed, RequestTracking.RetryAction.REMOVE_AND_SAVE, now = 99)
        assertEquals(RequestStatus.REQUESTED, readded.status)
        assertNull(readded.errorMessage)
        assertNull(readded.finishedAt)
        assertEquals(0, readded.lookupAttempts)
        assertEquals(99L, readded.updatedAt)
        // Re-added: it must show up in the queue again before vanishing means "finished".
        assertFalse(readded.seenInQueue)
        assertTrue(RequestTracking.afterRetry(failed, RequestTracking.RetryAction.RETRY_FAILED, now = 99).seenInQueue)
    }

    @Test
    fun `nothing arriving is a failure, and old rows saying otherwise are corrected`() {
        val given = RequestTracking.gaveUp(req(RequestStatus.ADDING, finishedAt = 0, attempts = 6), now = 7)
        assertEquals(RequestStatus.FAILED, given.status)
        assertEquals(RequestTracking.NOTHING_ARRIVED, given.errorMessage)
        assertNull(given.matchedIds)

        val old = req(RequestStatus.DONE).copy(errorMessage = RequestTracking.NOT_MATCHED)
        val fixed = RequestTracking.reclassified(old)!!
        assertEquals(RequestStatus.FAILED, fixed.status)
        assertEquals(RequestTracking.NOTHING_ARRIVED, fixed.errorMessage)
        assertNull(RequestTracking.reclassified(req(RequestStatus.DONE).copy(matchedIds = "s1")))
        assertNull(RequestTracking.reclassified(req(RequestStatus.FAILED).copy(errorMessage = RequestTracking.NOT_MATCHED)))
    }

    @Test
    fun `adding for over half an hour times out`() {
        val r = req(RequestStatus.ADDING, finishedAt = 0)
        assertFalse(RequestTracking.addingTimedOut(r, now = RequestTracking.ADDING_TIMEOUT_MS))
        assertTrue(RequestTracking.addingTimedOut(r, now = RequestTracking.ADDING_TIMEOUT_MS + 1))
        assertFalse(RequestTracking.addingTimedOut(req(RequestStatus.DOWNLOADING, finishedAt = 0), now = Long.MAX_VALUE / 2))
    }
}
