package im.flume.hearth.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverCacheKeyTest {
    @Test
    fun `cover keys leave out the token and salt`() {
        val url = "http://100.1.2.3:4533/rest/getCoverArt?u=leon&t=abc&s=def&v=1.16.1&c=Hearth&f=json&id=al-42&size=300"
        assertEquals("cover:100.1.2.3:4533:al-42:300", coverCacheKey(url))
    }

    @Test
    fun `same cover after signing in again keeps its key`() {
        val a = coverCacheKey("https://music.example/rest/getCoverArt?t=1&s=2&id=x&size=600")
        val b = coverCacheKey("https://music.example/rest/getCoverArt?t=3&s=4&id=x&size=600")
        assertEquals(a, b)
    }

    @Test
    fun `other urls are left alone`() {
        assertNull(coverCacheKey("https://example.com/image.png"))
        assertNull(coverCacheKey("http://h/rest/getCoverArt?t=1"))
    }
}
