package im.flume.hearth.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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
    fun `user message falls back to the exception type`() {
        assertEquals("IllegalStateException", userMessage(IllegalStateException()))
        assertEquals("the server timed out", userMessage(java.io.IOException("https://h/rest?t=1 timed out")))
    }
}
