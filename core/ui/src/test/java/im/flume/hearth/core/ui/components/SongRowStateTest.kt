package im.flume.hearth.core.ui.components

import im.flume.hearth.core.data.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongRowStateTest {
    private val downloads = mapOf("a" to DownloadState.DONE, "b" to DownloadState.DOWNLOADING)

    @Test
    fun `a row only sees its own song`() {
        assertEquals(SongRowState(DownloadState.DONE, isPlaying = true, online = true), SongRowState.of("a", "a", true, downloads))
        assertEquals(SongRowState(null, isPlaying = false, online = true), SongRowState.of("c", "a", true, downloads))
    }

    @Test
    fun `another song finishing leaves this row's state equal, so it isn't redrawn`() {
        val before = SongRowState.of("a", null, true, downloads)
        val after = SongRowState.of("a", null, true, downloads + ("b" to DownloadState.DONE))
        assertEquals(before, after)
    }

    @Test
    fun `offline, only downloaded songs can be played`() {
        assertTrue(SongRowState.of("a", null, false, downloads).playable)
        assertFalse(SongRowState.of("b", null, false, downloads).playable)
        assertTrue(SongRowState.of("b", null, true, downloads).playable)
    }
}
