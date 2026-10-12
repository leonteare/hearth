package im.flume.hearth.ui.components

import im.flume.hearth.data.DownloadCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadsBadgeTest {
    @Test
    fun `nothing downloading, waiting or failed hides the icon`() {
        assertNull(DownloadsBadge.of(DownloadCounts(), coming = 0, comingFailed = 0))
    }

    @Test
    fun `songs downloading and waiting count as in progress`() {
        val badge = DownloadsBadge.of(DownloadCounts(queued = 3, downloading = 2), coming = 0, comingFailed = 0)
        assertEquals(5, badge?.inProgress)
        assertEquals("Downloads, 5 in progress", badge?.description)
    }

    @Test
    fun `music coming to the server counts as waiting`() {
        val badge = DownloadsBadge.of(DownloadCounts(), coming = 2, comingFailed = 0)
        assertEquals(DownloadsBadge(downloading = 0, waiting = 2, failed = 0), badge)
    }

    @Test
    fun `failed downloads alone still show the icon and say so`() {
        val badge = DownloadsBadge.of(DownloadCounts(failed = 1), coming = 0, comingFailed = 1)
        assertNotNull(badge)
        assertEquals("Downloads, 0 in progress, 2 couldn't download", badge?.description)
    }
}
