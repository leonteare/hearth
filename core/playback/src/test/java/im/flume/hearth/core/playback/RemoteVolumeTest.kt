// What the media session says about its volume in each output, and where a volume key press lands on a speaker.
package im.flume.hearth.core.playback

import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVolumeTest {
    private val speaker = AudioOutput.Sonos("RINCON_1", "Kitchen", "http://10.0.0.5:1400")

    @Test
    fun `a speaker's volume is somewhere else, so Android owns the keys and the slider`() {
        assertTrue(RemoteVolume.onSpeaker(speaker, volume = 35))
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, RemoteVolume.playbackType(onSpeaker = true))

        val info = RemoteVolume.deviceInfo(onSpeaker = true)

        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, info?.playbackType)
        assertEquals(0, info?.minVolume)
        assertEquals(RemoteVolume.MAX, info?.maxVolume)
    }

    @Test
    fun `the sound on this phone is the phone's own volume, as it always was`() {
        assertFalse(RemoteVolume.onSpeaker(AudioOutput.Phone, volume = 35))
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, RemoteVolume.playbackType(onSpeaker = false))
        assertNull(RemoteVolume.deviceInfo(onSpeaker = false))
        assertEquals(emptyList<Int>(), RemoteVolume.commands(onSpeaker = false))
    }

    @Test
    fun `a speaker that hasn't said how loud it is leaves the volume with the phone for now`() {
        assertFalse(RemoteVolume.onSpeaker(speaker, volume = -1))
        assertTrue(RemoteVolume.onSpeaker(speaker, volume = 0))
    }

    @Test
    fun `a speaker adds the volume commands a controller looks for`() {
        val commands = RemoteVolume.commands(onSpeaker = true)

        assertTrue(commands.contains(Player.COMMAND_GET_DEVICE_VOLUME))
        assertTrue(commands.contains(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS))
        assertTrue(commands.contains(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
        @Suppress("DEPRECATION")
        val older = listOf(Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME)
        assertTrue(commands.containsAll(older))
    }

    @Test
    fun `a volume key press moves the speaker one step`() {
        assertEquals(44, RemoteVolume.stepped(40, RemoteVolume.STEP))
        assertEquals(36, RemoteVolume.stepped(40, -RemoteVolume.STEP))
    }

    @Test
    fun `volume keys stop at silent and at full instead of running past them`() {
        assertEquals(RemoteVolume.MAX, RemoteVolume.stepped(99, RemoteVolume.STEP))
        assertEquals(0, RemoteVolume.stepped(2, -RemoteVolume.STEP))
        assertEquals(RemoteVolume.MAX, RemoteVolume.shown(140))
        assertEquals(0, RemoteVolume.shown(-1))
    }

    @Test
    fun `it takes a couple of dozen presses to go from silent to full`() {
        var level = 0
        var presses = 0
        while (level < RemoteVolume.MAX && presses < 1_000) {
            level = RemoteVolume.stepped(level, RemoteVolume.STEP)
            presses++
        }

        assertEquals(RemoteVolume.MAX, level)
        assertTrue("$presses presses", presses in 10..40)
    }
}
