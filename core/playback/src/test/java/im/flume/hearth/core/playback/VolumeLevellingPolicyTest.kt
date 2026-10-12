package im.flume.hearth.core.playback

import im.flume.hearth.core.playback.VolumeLevellingPolicy.ALBUM
import im.flume.hearth.core.playback.VolumeLevellingPolicy.OFF
import im.flume.hearth.core.playback.VolumeLevellingPolicy.TRACK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeLevellingPolicyTest {
    private fun gain(mode: Int, track: Double?, album: Double?) = VolumeLevellingPolicy.gainDb(mode, { track }, { album })

    @Test
    fun `off ignores the tags`() {
        assertNull(gain(OFF, track = -6.0, album = -3.0))
    }

    @Test
    fun `per track uses the track's gain only`() {
        assertEquals(-6.0, gain(TRACK, track = -6.0, album = -3.0)!!, 0.0)
        assertNull(gain(TRACK, track = null, album = -3.0))
    }

    @Test
    fun `per album uses the album's gain, else the track's`() {
        assertEquals(-3.0, gain(ALBUM, track = -6.0, album = -3.0)!!, 0.0)
        assertEquals(-6.0, gain(ALBUM, track = -6.0, album = null)!!, 0.0)
        assertNull(gain(ALBUM, track = null, album = null))
    }

    @Test
    fun `an unknown setting plays without levelling`() {
        assertNull(gain(7, track = -6.0, album = -3.0))
    }

    @Test
    fun `tags are only read when the setting needs them`() {
        var reads = 0
        VolumeLevellingPolicy.gainDb(OFF, { reads++; -1.0 }, { reads++; -1.0 })
        VolumeLevellingPolicy.gainDb(TRACK, { -1.0 }, { reads++; -1.0 })
        assertEquals(0, reads)
    }

    @Test
    fun `untagged songs play at full volume`() {
        assertEquals(1f, VolumeLevellingPolicy.volume(null), 0f)
        assertEquals(1f, VolumeLevellingPolicy.volume(0.0), 0f)
    }

    @Test
    fun `loud songs are turned down by their gain`() {
        assertEquals(0.5012f, VolumeLevellingPolicy.volume(-6.0), 0.0001f)
        assertEquals(0.1f, VolumeLevellingPolicy.volume(-20.0), 0.0001f)
    }

    @Test
    fun `quiet songs are never turned up`() {
        assertEquals(1f, VolumeLevellingPolicy.volume(8.0), 0f)
    }

    @Test
    fun `a wildly wrong tag can't make a song silent`() {
        assertEquals(0.05f, VolumeLevellingPolicy.volume(-60.0), 0f)
    }
}
