// What the media session reports and offers while a Sonos speaker has the sound, and that the sound on this
// phone is left exactly as it was.
package im.flume.hearth.core.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSessionTest {
    private val speaker = AudioOutput.Sonos("RINCON_1", "Kitchen", "http://10.0.0.5:1400")

    @Test
    fun `a playing speaker makes the session a ready, playing one`() {
        assertTrue(RemoteSession.onSpeaker(speaker))
        assertEquals(
            Player.STATE_READY,
            RemoteSession.state(onSpeaker = true, hasQueue = true, phoneState = Player.STATE_IDLE),
        )
        assertTrue(RemoteSession.playing(onSpeaker = true, speakerPlaying = true, phonePlaying = false))
        assertFalse(RemoteSession.playing(onSpeaker = true, speakerPlaying = false, phonePlaying = true))
    }

    @Test
    fun `a speaker counts from the moment it has the sound, before it has said how loud it is`() {
        // The volume waits for an answer so a slider can't start at silent; the state must not wait at all,
        // or the first thing Android hears is that nothing is playing.
        assertTrue(RemoteSession.onSpeaker(speaker))
        assertFalse(RemoteVolume.onSpeaker(speaker, volume = -1))
    }

    @Test
    fun `a speaker with nothing to play leaves the session idle`() {
        assertEquals(
            Player.STATE_IDLE,
            RemoteSession.state(onSpeaker = true, hasQueue = false, phoneState = Player.STATE_IDLE),
        )
    }

    @Test
    fun `the sound on this phone is reported by the phone's own player, as it always was`() {
        assertFalse(RemoteSession.onSpeaker(AudioOutput.Phone))
        assertEquals(emptyList<Int>(), RemoteSession.commands(onSpeaker = false))
        assertEquals(
            Player.STATE_BUFFERING,
            RemoteSession.state(onSpeaker = false, hasQueue = true, phoneState = Player.STATE_BUFFERING),
        )
        // A speaker that is still playing somebody else's queue says nothing about this phone.
        assertFalse(RemoteSession.playing(onSpeaker = false, speakerPlaying = true, phonePlaying = false))
        assertTrue(RemoteSession.playing(onSpeaker = false, speakerPlaying = false, phonePlaying = true))
    }

    @Test
    fun `a speaker offers the buttons the notification and a car press`() {
        val commands = RemoteSession.commands(onSpeaker = true)

        listOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        ).forEach { assertTrue("$it", commands.contains(it)) }
    }

    @Test
    fun `the position is the speaker's, and never before the start of the song`() {
        assertEquals(93_000L, RemoteSession.position(93_000))
        assertEquals(0L, RemoteSession.position(-1))
    }

    @Test
    fun `the length is the speaker's while it knows one, and the song's own until then`() {
        assertEquals(210_000L, RemoteSession.duration(speakerMs = 210_000, knownMs = 185_000))
        assertEquals(185_000L, RemoteSession.duration(speakerMs = 0, knownMs = 185_000))
        assertEquals(C.TIME_UNSET, RemoteSession.duration(speakerMs = 0, knownMs = C.TIME_UNSET))
    }

    @Test
    fun `the song is the one the speaker is on, while that is a song in our queue`() {
        assertEquals(3, RemoteSession.index(speakerIndex = 3, queueSize = 10, phoneIndex = 0))
        // The speaker is playing something we didn't send it, or hasn't answered: the phone's own song.
        assertEquals(2, RemoteSession.index(speakerIndex = -1, queueSize = 10, phoneIndex = 2))
        assertEquals(2, RemoteSession.index(speakerIndex = 12, queueSize = 10, phoneIndex = 2))
    }

    @Test
    fun `a second ticking by is not a seek, and a jump is`() {
        assertFalse(RemoteSession.seeked(60_000, 61_000))
        assertFalse(RemoteSession.seeked(60_000, 62_000))
        assertTrue(RemoteSession.seeked(60_000, 20_000))
        assertTrue(RemoteSession.seeked(60_000, 180_000))
    }
}
