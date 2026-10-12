package im.flume.hearth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PingScheduleTest {
    @Test
    fun `an unreachable server is retried less and less often`() {
        assertEquals(20_000L, PingSchedule.delayMs(reachable = false, failedPings = 0))
        assertEquals(40_000L, PingSchedule.delayMs(reachable = false, failedPings = 1))
        assertEquals(80_000L, PingSchedule.delayMs(reachable = false, failedPings = 2))
        assertEquals(320_000L, PingSchedule.delayMs(reachable = null, failedPings = 4))
    }

    @Test
    fun `retries never wait longer than ten minutes`() {
        assertEquals(600_000L, PingSchedule.delayMs(reachable = false, failedPings = 5))
        assertEquals(600_000L, PingSchedule.delayMs(reachable = false, failedPings = 1_000))
    }

    @Test
    fun `a reachable server is checked every five minutes`() {
        assertEquals(300_000L, PingSchedule.delayMs(reachable = true, failedPings = 0))
    }

    @Test
    fun `pings happen while the app is open or something plays`() {
        assertTrue(PingSchedule.shouldPing(hasNetwork = true, offlineMode = false, foreground = true, playing = false))
        assertTrue(PingSchedule.shouldPing(hasNetwork = true, offlineMode = false, foreground = false, playing = true))
    }

    @Test
    fun `no pings in the background with nothing playing, offline mode or no network`() {
        assertFalse(PingSchedule.shouldPing(hasNetwork = true, offlineMode = false, foreground = false, playing = false))
        assertFalse(PingSchedule.shouldPing(hasNetwork = true, offlineMode = true, foreground = true, playing = true))
        assertFalse(PingSchedule.shouldPing(hasNetwork = false, offlineMode = false, foreground = true, playing = true))
    }
}
