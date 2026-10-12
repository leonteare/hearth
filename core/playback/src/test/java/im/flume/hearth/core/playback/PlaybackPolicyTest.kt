package im.flume.hearth.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `the wifi lock looks at the song playing and the two after it`() {
        assertEquals(4..6, WakeModePolicy.window(current = 4, size = 20))
    }

    @Test
    fun `the wifi lock window stops at the end of the queue`() {
        assertEquals(9..9, WakeModePolicy.window(current = 9, size = 10))
        assertEquals(8..9, WakeModePolicy.window(current = 8, size = 10))
    }

    @Test
    fun `with no queue or no current song the wake mode is left alone`() {
        assertNull(WakeModePolicy.window(current = 0, size = 0))
        assertNull(WakeModePolicy.window(current = -1, size = 5))
        assertNull(WakeModePolicy.window(current = 5, size = 5))
    }

    @Test
    fun `prefetch takes the songs after the current one`() {
        assertEquals(listOf(3, 4, 5), PrefetchPolicy.window(current = 2, size = 10, count = 3).toList())
        assertEquals(listOf(3), PrefetchPolicy.window(current = 2, size = 10, count = 1).toList())
    }

    @Test
    fun `prefetch stops at the end of the queue`() {
        assertEquals(listOf(9), PrefetchPolicy.window(current = 8, size = 10, count = 3).toList())
        assertTrue(PrefetchPolicy.window(current = 9, size = 10, count = 3).isEmpty())
        assertTrue(PrefetchPolicy.window(current = 2, size = 10, count = 0).isEmpty())
    }

    @Test
    fun `pinned bitrates are kept for the current song and every song that could be prefetched`() {
        assertEquals(listOf(2, 3, 4, 5), PrefetchPolicy.pinnedWindow(current = 2, size = 10, ahead = 3).toList())
        assertEquals(listOf(8, 9), PrefetchPolicy.pinnedWindow(current = 8, size = 10, ahead = 3).toList())
        assertTrue(PrefetchPolicy.pinnedWindow(current = 0, size = 0, ahead = 3).isEmpty())
    }

    @Test
    fun `a song fully cached at some bitrate keeps playing at it`() {
        assertEquals(128, StreamQuality.choose(cached = 128, metered = { true }, wifiKbps = 0, mobileKbps = 192))
    }

    @Test
    fun `an uncached song streams at the setting for this connection`() {
        assertEquals(192, StreamQuality.choose(cached = null, metered = { true }, wifiKbps = 0, mobileKbps = 192))
        assertEquals(0, StreamQuality.choose(cached = null, metered = { false }, wifiKbps = 0, mobileKbps = 192))
    }

    @Test
    fun `the connection is only checked when the song isn't cached`() {
        var asked = false
        StreamQuality.choose(cached = 320, metered = { asked = true; true }, wifiKbps = 0, mobileKbps = 192)
        assertFalse(asked)
    }

    @Test
    fun `the cache is searched at the pinned bitrate first then each setting once`() {
        assertEquals(listOf(96, 0, 192), StreamQuality.cacheCandidates(pinned = 96, wifiKbps = 0, mobileKbps = 192))
        assertEquals(listOf(0, 192), StreamQuality.cacheCandidates(pinned = null, wifiKbps = 0, mobileKbps = 192))
        assertEquals(listOf(192), StreamQuality.cacheCandidates(pinned = 192, wifiKbps = 192, mobileKbps = 192))
    }

    @Test
    fun `swiping the app away stops the service unless music is playing`() {
        assertTrue(ServiceLifetime.stopOnTaskRemoved(playWhenReady = false, itemCount = 5))
        assertTrue(ServiceLifetime.stopOnTaskRemoved(playWhenReady = true, itemCount = 0))
        assertFalse(ServiceLifetime.stopOnTaskRemoved(playWhenReady = true, itemCount = 5))
    }
}
