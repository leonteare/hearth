// The URL a speaker is given for a song: the right host, the login on it, and mp3 asked for.
package im.flume.hearth.domain.music.playback

import im.flume.hearth.core.network.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosStreamTest {
    private val creds = Credentials("https://music.home", "leon", "abc123", "deadbeef")

    private fun query(url: String) = url.toHttpUrl().let { u -> u.queryParameterNames.associateWith { u.queryParameter(it) } }

    @Test
    fun `the speaker fetches from the plain http host, not the phone's own server`() {
        val url = SonosStream.songUrl("http://sonos.home", "song-7", creds)!!

        assertTrue(url, url.startsWith("http://sonos.home/rest/stream.view?"))
    }

    @Test
    fun `the login travels the same way it does for the phone`() {
        val q = query(SonosStream.songUrl("http://sonos.home", "song-7", creds)!!)

        assertEquals("leon", q["u"])
        assertEquals("deadbeef", q["t"])
        assertEquals("abc123", q["s"])
        assertEquals("Hearth", q["c"])
        assertEquals("song-7", q["id"])
    }

    @Test
    fun `music is always asked for as mp3, with a length the speaker can seek inside`() {
        val q = query(SonosStream.songUrl("http://sonos.home", "song-7", creds)!!)

        assertEquals("mp3", q["format"])
        assertEquals("true", q["estimateContentLength"])
        assertNull(q["maxBitRate"])
    }

    @Test
    fun `a bitrate is passed on when one is asked for`() {
        val q = query(SonosStream.songUrl("http://sonos.home", "song-7", creds, maxBitRate = 192)!!)

        assertEquals("192", q["maxBitRate"])
    }

    @Test
    fun `a trailing slash or a stray space in the address makes no difference`() {
        val tidy = SonosStream.songUrl("http://sonos.home", "song-7", creds)

        assertEquals(tidy, SonosStream.songUrl("  http://sonos.home/  ", "song-7", creds))
    }

    @Test
    fun `no login and no address mean no url, rather than a broken one`() {
        assertNull(SonosStream.songUrl("http://sonos.home", "song-7", null))
        assertNull(SonosStream.songUrl("", "song-7", creds))
        assertNull(SonosStream.songUrl("not a url", "song-7", creds))
    }

    @Test
    fun `the cover art the speaker shows comes off the same host`() {
        val url = SonosStream.coverUrl("http://sonos.home", "al-3", creds)!!

        assertTrue(url, url.startsWith("http://sonos.home/rest/getCoverArt.view?"))
        assertEquals("al-3", query(url)["id"])
        assertEquals("600", query(url)["size"])
    }

    @Test
    fun `a song with no artwork gets no cover url`() {
        assertNull(SonosStream.coverUrl("http://sonos.home", null, creds))
        assertNull(SonosStream.coverUrl("http://sonos.home", "", creds))
    }
}
