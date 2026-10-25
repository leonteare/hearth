// When a speaker gets asked what it's doing, and what a speaker that fails means.
package im.flume.hearth.core.playback

import im.flume.hearth.core.common.SonosException
import im.flume.hearth.core.common.SonosUpnpException
import im.flume.hearth.core.network.SonosTransportState
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosPolicyTest {
    private val speaker = AudioOutput.Sonos("RINCON_1", "Kitchen", "http://10.0.0.5:1400")

    @Test
    fun `a speaker is only asked anything while a screen is watching`() {
        assertTrue(SonosPolling.shouldPoll(speaker, observers = 1))
        assertFalse(SonosPolling.shouldPoll(speaker, observers = 0))
    }

    @Test
    fun `nothing is asked when the sound is on this phone`() {
        assertFalse(SonosPolling.shouldPoll(AudioOutput.Phone, observers = 3))
    }

    @Test
    fun `a playing speaker is asked once a second and a stopped one far less often`() {
        assertEquals(SonosPolling.WATCHING_MS, SonosPolling.delayMs(SonosTransportState.PLAYING))
        assertEquals(SonosPolling.WATCHING_MS, SonosPolling.delayMs(SonosTransportState.TRANSITIONING))
        assertEquals(SonosPolling.PAUSED_MS, SonosPolling.delayMs(SonosTransportState.PAUSED))
        assertEquals(SonosPolling.IDLE_MS, SonosPolling.delayMs(SonosTransportState.STOPPED))
        assertEquals(SonosPolling.IDLE_MS, SonosPolling.delayMs(SonosTransportState.UNKNOWN))
        assertTrue(SonosPolling.IDLE_MS > SonosPolling.WATCHING_MS)
    }

    @Test
    fun `the volume is asked for on the first round and now and then after it`() {
        assertTrue(SonosPolling.volumeDue(0))
        assertFalse(SonosPolling.volumeDue(1))
        assertTrue(SonosPolling.volumeDue(SonosPolling.VOLUME_EVERY))
    }

    @Test
    fun `the position runs on between answers while the speaker is playing`() {
        val now = SonosPolling.positionNow(lastMs = 10_000, measuredAtMs = 1_000, nowMs = 1_750, playing = true)

        assertEquals(10_750L, now)
    }

    @Test
    fun `a paused speaker's position stays put`() {
        val now = SonosPolling.positionNow(lastMs = 10_000, measuredAtMs = 1_000, nowMs = 9_000, playing = false)

        assertEquals(10_000L, now)
    }

    @Test
    fun `the position never runs past the end of the song`() {
        val now = SonosPolling.positionNow(
            lastMs = 119_000,
            measuredAtMs = 0,
            nowMs = 60_000,
            playing = true,
            durationMs = 120_000,
        )

        assertEquals(120_000L, now)
    }

    @Test
    fun `a speaker that refused is still ours, so the message is just shown`() {
        val refused = SonosUpnpException(714, "Illegal MIME-Type")

        assertEquals(SonosFailureAction.SHOW, SonosFailures.action(refused, 1, handingOver = false))
        assertEquals(SonosFailureAction.SHOW, SonosFailures.action(refused, 9, handingOver = true))
    }

    @Test
    fun `a speaker that cannot be reached while handing over gives the sound straight back`() {
        val unreachable = SonosException("Couldn't reach the speaker", IOException("no route"))

        assertEquals(SonosFailureAction.BACK_TO_PHONE, SonosFailures.action(unreachable, 1, handingOver = true))
    }

    @Test
    fun `a missed answer is forgiven until it keeps happening`() {
        val unreachable = SonosException("Couldn't reach the speaker")

        assertEquals(SonosFailureAction.SHOW, SonosFailures.action(unreachable, 1, handingOver = false))
        assertEquals(SonosFailureAction.SHOW, SonosFailures.action(unreachable, SonosFailures.STRIKES - 1, false))
        assertEquals(SonosFailureAction.BACK_TO_PHONE, SonosFailures.action(unreachable, SonosFailures.STRIKES, false))
    }
}
