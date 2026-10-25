// Which tracks a speaker is given and in what order, where the position lands either way, and how the
// speaker's answer is laid over the player state.
package im.flume.hearth.core.playback

import im.flume.hearth.core.data.Settings
import im.flume.hearth.core.network.SonosMime
import im.flume.hearth.core.network.SonosPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosPlanTest {
    private fun track(n: Int) = SonosTrack("http://sonos.home/rest/stream.view?id=$n", SonosMime.MP3, "Song $n", "Artist")

    private fun tracks(count: Int) = List(count) { track(it + 1) }

    @Test
    fun `the whole queue from the current song goes to the speaker, in order`() {
        val plan = SonosPlan.handover(tracks(5), currentIndex = 1, positionMs = 30_000)

        assertEquals(listOf("Song 2", "Song 3", "Song 4", "Song 5"), plan.tracks.map { it.title })
        assertEquals(listOf(1, 2, 3, 4), plan.queueIndexes)
        assertEquals(30_000L, plan.seekMs)
    }

    @Test
    fun `songs already played are left behind`() {
        val plan = SonosPlan.handover(tracks(3), currentIndex = 2, positionMs = 0)

        assertEquals(listOf("Song 3"), plan.tracks.map { it.title })
        assertEquals(listOf(2), plan.queueIndexes)
    }

    @Test
    fun `a track with no url is skipped and the rest keep their places in the queue`() {
        val queue = listOf(track(1), null, track(3), null, track(5))

        val plan = SonosPlan.handover(queue, currentIndex = 0, positionMs = 1_000)

        assertEquals(listOf("Song 1", "Song 3", "Song 5"), plan.tracks.map { it.title })
        assertEquals(listOf(0, 2, 4), plan.queueIndexes)
    }

    @Test
    fun `a current song that cannot be sent starts the speaker at the next one, from the beginning`() {
        val queue = listOf(track(1), null, track(3))

        val plan = SonosPlan.handover(queue, currentIndex = 1, positionMs = 45_000)

        assertEquals(listOf("Song 3"), plan.tracks.map { it.title })
        assertEquals(0L, plan.seekMs)
    }

    @Test
    fun `a very long queue is cut off rather than pushed track by track for minutes`() {
        val plan = SonosPlan.handover(tracks(500), currentIndex = 0, positionMs = 0, max = 3)

        assertEquals(3, plan.tracks.size)
        assertEquals(listOf(0, 1, 2), plan.queueIndexes)
    }

    @Test
    fun `an empty queue hands nothing over`() {
        val plan = SonosPlan.handover(emptyList(), currentIndex = 0, positionMs = 10_000)

        assertTrue(plan.isEmpty)
        assertEquals(0L, plan.seekMs)
    }

    @Test
    fun `an index past the end of the queue still sends the last song`() {
        val plan = SonosPlan.handover(tracks(2), currentIndex = 9, positionMs = 500)

        assertEquals(listOf("Song 2"), plan.tracks.map { it.title })
        assertEquals(listOf(1), plan.queueIndexes)
        assertEquals(500L, plan.seekMs)
    }

    @Test
    fun `handing back lands on the song the speaker is playing, where it has got to`() {
        val plan = SonosPlan.handover(tracks(5), currentIndex = 2, positionMs = 0)
        val playing = SonosPosition(elapsedMs = 61_000, durationMs = 180_000, trackUri = plan.tracks[1].url)

        val resume = SonosPlan.handBack(plan, playing, phoneIndex = 2, phonePositionMs = 0)

        assertEquals(SonosResume(3, 61_000), resume)
    }

    @Test
    fun `a speaker playing something we never sent leaves the phone where it was`() {
        val plan = SonosPlan.handover(tracks(3), currentIndex = 0, positionMs = 0)
        val elsewhere = SonosPosition(99_000, 120_000, "x-sonos-spotify:track")

        val resume = SonosPlan.handBack(plan, elsewhere, phoneIndex = 1, phonePositionMs = 12_000)

        assertEquals(SonosResume(1, 12_000), resume)
    }

    @Test
    fun `a speaker that never answered hands back where the phone gave the sound away`() {
        val resume = SonosPlan.handBack(sent = null, where = null, phoneIndex = 4, phonePositionMs = 7_500)

        assertEquals(SonosResume(4, 7_500), resume)
    }

    @Test
    fun `nonsense positions are pulled back to the start of the song`() {
        val resume = SonosPlan.handBack(null, null, phoneIndex = -3, phonePositionMs = -1)

        assertEquals(SonosResume(0, 0), resume)
    }

    @Test
    fun `the speaker's track is matched back to a song in the queue`() {
        val plan = SonosPlan.handover(tracks(4), currentIndex = 1, positionMs = 0)

        assertEquals(2, SonosPlan.trackIndex(plan, plan.tracks[1].url))
        assertEquals(-1, SonosPlan.trackIndex(plan, "http://sonos.home/rest/stream.view?id=99"))
        assertEquals(-1, SonosPlan.trackIndex(plan, ""))
        assertEquals(-1, SonosPlan.trackIndex(null, plan.tracks[0].url))
    }

    @Test
    fun `a saved speaker is still the chosen output next time the app runs`() {
        val saved = Settings(sonosUuid = "RINCON_1", sonosRoom = "Kitchen", sonosHost = "http://10.0.0.5:1400")

        assertEquals(AudioOutput.Sonos("RINCON_1", "Kitchen", "http://10.0.0.5:1400"), saved.chosenOutput())
        assertEquals("Kitchen", saved.chosenOutput().roomName)
    }

    @Test
    fun `no saved speaker, or one with no address, means this phone`() {
        assertSame(AudioOutput.Phone, Settings().chosenOutput())
        assertSame(AudioOutput.Phone, Settings(sonosUuid = "RINCON_1").chosenOutput())
        assertNull(Settings().chosenOutput().roomName)
    }

    @Test
    fun `the metadata a track travels with names what the speaker will be fetching`() {
        val didl = track(1).copy(album = "An Album", artUrl = "http://sonos.home/art?id=1").metadata()

        assertTrue(didl.contains("<dc:title>Song 1</dc:title>"))
        assertTrue(didl.contains("<upnp:album>An Album</upnp:album>"))
        assertTrue(didl.contains("""protocolInfo="http-get:*:audio/mpeg:*""""))
        // The URL's own & has to be an entity or the document the speaker gets is broken.
        assertTrue(didl.contains("stream.view?id=1"))
    }

    @Test
    fun `what the speaker is doing replaces what the phone was doing`() {
        val phone = PlayerUiState(isPlaying = false, durationMs = 1_000, error = "phone error", currentIndex = 7)
        val speaker = AudioOutput.Sonos("RINCON_1", "Kitchen", "http://10.0.0.5:1400")

        val shown = phone.withRemote(RemoteOutput(speaker, isPlaying = true, durationMs = 240_000, volume = 35))

        assertTrue(shown.isPlaying)
        assertEquals(240_000L, shown.durationMs)
        assertEquals(speaker, shown.output)
        assertEquals(35, shown.outputVolume)
        // Nothing the speaker said, so the phone's own index and error stand.
        assertEquals(7, shown.currentIndex)
        assertEquals("phone error", shown.error)
    }

    @Test
    fun `with the sound on the phone the state is left exactly as it was`() {
        val phone = PlayerUiState(isPlaying = true, outputVolume = -1)

        assertSame(phone, phone.withRemote(null))
        assertSame(AudioOutput.Phone, phone.output)
    }
}
