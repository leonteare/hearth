package im.flume.hearth.requests

import im.flume.hearth.api.TidarrQueueItem
import im.flume.hearth.data.RequestEntity
import im.flume.hearth.data.RequestStatus
import im.flume.hearth.data.RequestTracking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A request's whole trip, as RequestsRepository drives it: Tidarr's queue, the lookups on the server, giving up and retrying. */
class RequestJourneyTest {
    private val min = 60_000L

    private fun request(now: Long = 0) = RequestEntity(
        tidalId = "123", type = "track", title = "Northern Lights", artist = "The Quiet Hours", coverUuid = null, isrc = null,
        albumTitle = "Long Winter", durationSec = 214, requestedAt = now, requestedBy = "leon", status = RequestStatus.REQUESTED,
        errorMessage = null, matchedIds = null, updatedAt = now, seenInQueue = false, finishedAt = null, lookupAttempts = 0,
    )

    private fun item(status: String, error: Boolean = false, stage: String? = null) =
        TidarrQueueItem("123", "track", "Northern Lights", "The Quiet Hours", status, error, stage)

    @Test
    fun `a request that downloads, vanishes from the queue and is never found ends up failed, then retries cleanly`() {
        var r = request()
        r = RequestTracking.afterQueue(r, item("queue_download"), now = 1_000)
        assertEquals(RequestStatus.REQUESTED, r.status)
        assertTrue(r.seenInQueue)
        r = RequestTracking.afterQueue(r, item("download"), now = 2_000)
        assertEquals(RequestStatus.DOWNLOADING, r.status)

        // Tidarr clears it from its queue: it finished, so start looking on the server.
        r = RequestTracking.afterQueue(r, null, now = 3_000)
        assertEquals(RequestStatus.ADDING, r.status)
        assertEquals(3_000L, r.finishedAt)
        assertEquals(0, r.lookupAttempts)

        // Lookups follow the schedule (0, 1, 3, 6, 10, 15 minutes after finishing), then stop.
        val schedule = listOf(0L, 1, 3, 6, 10, 15)
        schedule.forEachIndexed { attempt, minutes ->
            assertFalse("attempt $attempt is early", attempt > 0 && RequestTracking.lookupDue(r, 3_000 + minutes * min - 1))
            assertTrue("attempt $attempt is due", RequestTracking.lookupDue(r, 3_000 + minutes * min))
            assertFalse(RequestTracking.lookupExhausted(r))
            r = r.copy(lookupAttempts = attempt + 1)
        }
        assertTrue(RequestTracking.lookupExhausted(r))
        assertFalse(RequestTracking.lookupDue(r, 3_000 + 60 * min))

        r = RequestTracking.gaveUp(r, now = 4_000)
        assertEquals(RequestStatus.FAILED, r.status)
        assertEquals(RequestTracking.NOTHING_ARRIVED, r.errorMessage)

        // Still listed as finished in Tidarr: retrying takes it out and adds it again, so it has to show up first.
        val action = RequestTracking.retryAction(item("finished"))
        assertEquals(RequestTracking.RetryAction.REMOVE_AND_SAVE, action)
        r = RequestTracking.afterRetry(r, action, now = 5_000)
        assertEquals(RequestStatus.REQUESTED, r.status)
        assertNull(r.errorMessage)
        assertNull(r.finishedAt)
        assertFalse(r.seenInQueue)
        assertEquals(0, r.lookupAttempts)
    }

    @Test
    fun `a failed download names the stage it failed at`() {
        val r = RequestTracking.afterQueue(request(), item("error", error = true, stage = "tagging"), now = 10)
        assertEquals(RequestStatus.FAILED, r.status)
        assertEquals(RequestTracking.FAILED_MESSAGE + " (failed while tagging)", r.errorMessage)
    }

    @Test
    fun `seeing the same queue status again changes nothing, not even the time`() {
        val downloading = RequestTracking.afterQueue(request(), item("download"), now = 10)
        assertSame(downloading, RequestTracking.afterQueue(downloading, item("download"), now = 99))
        // An unknown status word keeps the request where it is.
        assertSame(downloading, RequestTracking.afterQueue(downloading, item("something_new"), now = 99))
    }

    @Test
    fun `finished and failed requests ignore Tidarr's queue`() {
        for (status in listOf(RequestStatus.DONE, RequestStatus.FAILED, RequestStatus.ADDING)) {
            val r = request().copy(status = status)
            assertSame(r, RequestTracking.afterQueue(r, item("download"), now = 50))
            assertSame(r, RequestTracking.afterQueue(r, null, now = 50 + RequestTracking.QUEUE_GRACE_MS * 10))
        }
    }

    @Test
    fun `a lookup is due straight away when the finish time wasn't recorded`() {
        assertTrue(RequestTracking.lookupDue(request().copy(status = RequestStatus.ADDING, finishedAt = null, lookupAttempts = 3), now = 0))
    }
}
