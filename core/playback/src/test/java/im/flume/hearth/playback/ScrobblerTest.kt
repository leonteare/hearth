package im.flume.hearth.playback

import im.flume.hearth.api.SubsonicException
import im.flume.hearth.playback.Scrobbler.Companion.isRejected
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ScrobblerTest {
    @Test
    fun `songs the server doesn't know are dropped`() {
        assertTrue(isRejected(SubsonicException(70, "Song not found")))
        assertTrue(isRejected(SubsonicException(10, "Missing parameter")))
        assertTrue(isRejected(SubsonicException(404, "HTTP 404 from server")))
    }

    @Test
    fun `network and server problems are kept for later`() {
        assertFalse(isRejected(IOException("Unable to resolve host")))
        assertFalse(isRejected(SocketTimeoutException()))
        assertFalse(isRejected(SubsonicException(502, "HTTP 502 from server")))
        assertFalse(isRejected(SubsonicException(-1, "Not logged in")))
    }

    @Test
    fun `login problems are kept for later`() {
        assertFalse(isRejected(SubsonicException(40, "Wrong username or password")))
        assertFalse(isRejected(SubsonicException(50, "Not authorized")))
    }
}
