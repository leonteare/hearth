package im.flume.hearth.download

import im.flume.hearth.data.DownloadState
import im.flume.hearth.data.DownloadStateRow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStatesTest {
    @Test
    fun `progress writes that change no state send nothing new`() = runTest {
        val downloading = listOf(DownloadStateRow("a", DownloadState.DONE), DownloadStateRow("b", DownloadState.DOWNLOADING))
        val finished = listOf(DownloadStateRow("a", DownloadState.DONE), DownloadStateRow("b", DownloadState.DONE))
        val sent = flowOf(downloading, downloading, downloading, finished, finished).toStateMap().toList()
        assertEquals(
            listOf(
                mapOf("a" to DownloadState.DONE, "b" to DownloadState.DOWNLOADING),
                mapOf("a" to DownloadState.DONE, "b" to DownloadState.DONE),
            ),
            sent,
        )
    }

    @Test
    fun `rows coming back in another order are the same states`() = runTest {
        val one = listOf(DownloadStateRow("a", DownloadState.QUEUED), DownloadStateRow("b", DownloadState.FAILED))
        assertEquals(1, flowOf(one, one.reversed()).toStateMap().toList().size)
    }
}
