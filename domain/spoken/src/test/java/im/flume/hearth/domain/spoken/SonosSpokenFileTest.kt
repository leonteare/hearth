// The URL a speaker is given for an Audiobookshelf file: the /abs path on the plain-http host, token and all.
package im.flume.hearth.domain.spoken

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosSpokenFileTest {
    @Test
    fun `audiobookshelf sits under its own path on the speaker's host`() {
        val url = SonosSpokenFile.fileUrl("http://sonos.home", "li_abc", "9129312", "jwt-token")!!

        assertEquals("http://sonos.home/abs/api/items/li_abc/file/9129312?token=jwt-token", url)
    }

    @Test
    fun `the token goes in the query string, because a speaker can't send a header`() {
        val url = SonosSpokenFile.fileUrl("http://sonos.home/", "li_abc", "9129312", "a+b/c=")!!

        assertEquals("a+b/c=", url.toHttpUrl().queryParameter("token"))
    }

    @Test
    fun `signed out of audiobookshelf means no url at all`() {
        assertNull(SonosSpokenFile.fileUrl("http://sonos.home", "li_abc", "9129312", null))
        assertNull(SonosSpokenFile.fileUrl("http://sonos.home", "li_abc", "9129312", " "))
    }

    @Test
    fun `an item or file we don't know gets no url`() {
        assertNull(SonosSpokenFile.fileUrl("http://sonos.home", "", "9129312", "jwt"))
        assertNull(SonosSpokenFile.fileUrl("http://sonos.home", "li_abc", "", "jwt"))
    }

    @Test
    fun `a base address that isn't a url gets no url`() {
        assertNull(SonosSpokenFile.fileUrl("", "li_abc", "9129312", "jwt"))
        assertNull(SonosSpokenFile.fileUrl("sonos.home", "li_abc", "9129312", "jwt"))
    }

    @Test
    fun `the cover needs no token, because audiobookshelf serves covers to anyone`() {
        val url = SonosSpokenFile.coverUrl("http://sonos.home", "li_abc")!!

        assertTrue(url, url.startsWith("http://sonos.home/abs/api/items/li_abc/cover?"))
        assertNull(url.toHttpUrl().queryParameter("token"))
        assertEquals("600", url.toHttpUrl().queryParameter("width"))
    }
}
