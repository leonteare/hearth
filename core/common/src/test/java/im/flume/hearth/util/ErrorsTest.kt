package im.flume.hearth.util

import im.flume.hearth.api.AbsHttpException
import im.flume.hearth.api.SubsonicException
import im.flume.hearth.api.TidarrException
import im.flume.hearth.api.WrongPasswordException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorsTest {
    @Test
    fun `urls become the server`() {
        val text = "failed to connect to http://100.1.2.3:4533/rest/stream?u=leon&t=abc123&s=salt9&id=5 after 10s"
        assertEquals("failed to connect to the server after 10s", scrubSecrets(text))
    }

    @Test
    fun `stray login parameters are blanked`() {
        val out = scrubSecrets("GET /rest/ping?u=leon&t=deadbeef&s=1234&p=enc:6869 HTTP/1.1")
        assertFalse(out.contains("deadbeef"))
        assertFalse(out.contains("1234"))
        assertFalse(out.contains("6869"))
        assertEquals("GET /rest/ping?u=leon&t=***&s=***&p=*** HTTP/1.1", out)
    }

    @Test
    fun `stack traces keep their shape`() {
        val trace = "java.io.IOException: closed\n\tat okhttp3.Foo.bar(Foo.kt:12)\nCaused by: https://x.y/rest?t=1"
        assertEquals("java.io.IOException: closed\n\tat okhttp3.Foo.bar(Foo.kt:12)\nCaused by: the server", scrubSecrets(trace))
    }

    @Test
    fun `ordinary words are left alone`() {
        assertEquals("Sets the playlist status", scrubSecrets("Sets the playlist status"))
    }

    @Test
    fun `no connection says to check you're online`() {
        assertEquals(NO_CONNECTION_MESSAGE, userMessage(UnknownHostException("Unable to resolve host \"music.home\"")))
        assertEquals(NO_CONNECTION_MESSAGE, userMessage(ConnectException("Failed to connect to /100.1.2.3:4533")))
        assertEquals(NO_CONNECTION_MESSAGE, userMessage(IOException("wrapped", UnknownHostException("x"))))
    }

    @Test
    fun `timeouts say the server was too slow`() {
        assertEquals(TIMEOUT_MESSAGE, userMessage(SocketTimeoutException()))
        assertEquals(TIMEOUT_MESSAGE, userMessage(IOException("https://h/rest?t=1 timed out")))
    }

    @Test
    fun `a refused sign-in is called that`() {
        assertEquals(SIGN_IN_REFUSED_MESSAGE, userMessage(SubsonicException(401, "HTTP 401 from server")))
        assertEquals(SIGN_IN_REFUSED_MESSAGE, userMessage(SubsonicException(40, "Wrong username or password")))
        assertEquals(SIGN_IN_REFUSED_MESSAGE, userMessage(AbsHttpException(403, "")))
        assertEquals(SIGN_IN_REFUSED_MESSAGE, userMessage(WrongPasswordException()))
    }

    @Test
    fun `server errors say to try again in a bit`() {
        assertEquals(SERVER_PROBLEM_MESSAGE, userMessage(SubsonicException(502, "HTTP 502 from server")))
        assertEquals(SERVER_PROBLEM_MESSAGE, userMessage(IOException("HTTP 500 from server")))
    }

    @Test
    fun `hearth's own messages are kept as they are`() {
        assertEquals("Tidarr isn't set up", userMessage(TidarrException("Tidarr isn't set up")))
        assertEquals("Required parameter is missing", userMessage(SubsonicException(10, "Required parameter is missing")))
    }

    @Test
    fun `anything else is something went wrong with a short scrubbed detail`() {
        assertEquals("Something went wrong", userMessage(IllegalStateException()))
        assertEquals("Something went wrong (unexpected end of stream on the server)", userMessage(IOException("unexpected end of stream on https://h/rest?t=1")))
        val long = userMessage(IllegalStateException("x".repeat(500)))
        assertTrue(long.length < 120)
        assertTrue(long.endsWith("…)"))
    }

    @Test
    fun `the fallback replaces the generic sentence`() {
        assertEquals("Couldn't reach Tidarr", userMessage(IllegalStateException(), "Couldn't reach Tidarr"))
    }

    @Test
    fun `failed actions put the reason after what failed`() {
        assertEquals("Couldn't update Liked Songs. $NO_CONNECTION_MESSAGE", couldNot("Couldn't update Liked Songs", SocketTimeoutException(), offline = true))
        assertEquals("Couldn't update Liked Songs. $SERVER_PROBLEM_MESSAGE", couldNot("Couldn't update Liked Songs", IOException("HTTP 503")))
        assertEquals("Couldn't delete the playlist.", couldNot("Couldn't delete the playlist", null))
    }
}
