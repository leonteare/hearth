package im.flume.hearth.download

import im.flume.hearth.download.DownloadPolicy.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class DownloadPolicyTest {

    @Test
    fun `no backoff for the first two drops, then 10s doubling up to 2 minutes`() {
        assertEquals(0L, DownloadPolicy.backoffMs(0))
        assertEquals(0L, DownloadPolicy.backoffMs(2))
        assertEquals(10_000L, DownloadPolicy.backoffMs(3))
        assertEquals(20_000L, DownloadPolicy.backoffMs(4))
        assertEquals(40_000L, DownloadPolicy.backoffMs(5))
        assertEquals(80_000L, DownloadPolicy.backoffMs(6))
        assertEquals(120_000L, DownloadPolicy.backoffMs(7))
        assertEquals(120_000L, DownloadPolicy.backoffMs(50))
    }

    @Test
    fun `dropped connection is transient until the song has used up its tries`() {
        val e = SocketTimeoutException("timeout")
        assertEquals(Failure.Transient, DownloadPolicy.classify(e, removedByUser = false, connectionTries = 1))
        assertEquals(Failure.Transient, DownloadPolicy.classify(e, false, DownloadPolicy.MAX_CONNECTION_TRIES - 1))
        assertEquals(Failure.SongFailed, DownloadPolicy.classify(e, false, DownloadPolicy.MAX_CONNECTION_TRIES))
    }

    @Test
    fun `a song removed by the user is cancelled, not a failure`() {
        assertEquals(Failure.Cancelled, DownloadPolicy.classify(IOException("Canceled"), removedByUser = true, connectionTries = 1))
    }

    @Test
    fun `server errors fail the song`() {
        assertEquals(Failure.SongFailed, DownloadPolicy.classify(IllegalStateException("Server answered HTTP 404"), false, 0))
    }

    @Test
    fun `full storage is recognised, even when wrapped`() {
        val enospc = IOException("write failed: ENOSPC (No space left on device)")
        assertTrue(DownloadPolicy.isStorageFull(enospc))
        assertTrue(DownloadPolicy.isStorageFull(IOException("copy failed", enospc)))
        assertFalse(DownloadPolicy.isStorageFull(IOException("Connection reset")))
        assertEquals(Failure.StorageFull, DownloadPolicy.classify(enospc, removedByUser = false, connectionTries = 1))
    }

    @Test
    fun `idle lanes wait while others are busy`() {
        assertFalse(DownloadPolicy.idleLaneShouldExit(busyLanes = 1))
        assertTrue(DownloadPolicy.idleLaneShouldExit(busyLanes = 0))
    }

    @Test
    fun `each attempt writes to its own part file`() {
        assertNotEquals(DownloadPolicy.partName("abc", 1), DownloadPolicy.partName("abc", 2))
        assertTrue(DownloadPolicy.partName("abc", 1).endsWith(".part"))
    }
}
