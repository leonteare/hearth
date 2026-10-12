package im.flume.hearth.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {
    @Test
    fun `on wifi three songs are saved ahead whatever the quality`() {
        assertEquals(3, PrefetchPolicy.count(metered = false, bitrateKbps = 0))
        assertEquals(3, PrefetchPolicy.count(metered = false, bitrateKbps = 192))
    }

    @Test
    fun `on mobile data only the next song is saved ahead`() {
        assertEquals(1, PrefetchPolicy.count(metered = true, bitrateKbps = 192))
    }

    @Test
    fun `on mobile data at original quality nothing is saved ahead`() {
        assertEquals(0, PrefetchPolicy.count(metered = true, bitrateKbps = 0))
    }

    @Test
    fun `the wifi lock is dropped when this song and the next two are on the phone`() {
        assertTrue(WakeModePolicy.localOnly(listOf(true, true, true)))
        assertTrue(WakeModePolicy.localOnly(listOf(true, true, true, false)))
    }

    @Test
    fun `the wifi lock is kept when any of the next few songs streams`() {
        assertFalse(WakeModePolicy.localOnly(listOf(false, true, true)))
        assertFalse(WakeModePolicy.localOnly(listOf(true, false, true)))
        assertFalse(WakeModePolicy.localOnly(listOf(true, true, false)))
    }

    @Test
    fun `near the end of the queue only the songs left count`() {
        assertTrue(WakeModePolicy.localOnly(listOf(true)))
        assertFalse(WakeModePolicy.localOnly(emptyList()))
    }
}
