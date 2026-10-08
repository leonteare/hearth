package im.flume.hearth.api

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SubsonicClientTest {
    private lateinit var server: MockWebServer
    private lateinit var creds: Credentials
    private lateinit var client: SubsonicClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        creds = Credentials.fromPassword(server.url("/").toString(), "leon", "sesame")
        client = SubsonicClient(OkHttpClient()) { creds }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `token is md5 of password plus salt`() {
        // Example from the Subsonic API docs.
        assertEquals("26719a1196d2a940705a59634eb18eab", Credentials.md5Hex("sesame" + "c19b2d"))
        assertEquals(Credentials.md5Hex("sesame" + creds.salt), creds.token)
    }

    @Test
    fun `server address is normalised`() {
        assertEquals("http://100.64.0.1:4533", Credentials.normalizeUrl(" 100.64.0.1:4533/ "))
        assertEquals("https://music.example.com", Credentials.normalizeUrl("https://music.example.com/"))
    }

    @Test
    fun `requests carry token auth and json format`() = runTest {
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""))
        client.ping(creds)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/ping", url.encodedPath)
        assertEquals("leon", url.queryParameter("u"))
        assertEquals(creds.token, url.queryParameter("t"))
        assertEquals(creds.salt, url.queryParameter("s"))
        assertEquals("json", url.queryParameter("f"))
        assertEquals(null, url.queryParameter("p"))
    }

    @Test
    fun `wrong password surfaces the server message`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}"""
            )
        )
        try {
            client.ping(creds)
            fail("expected an exception")
        } catch (e: SubsonicException) {
            assertEquals(40, e.code)
            assertEquals("Wrong username or password", e.message)
        }
    }

    @Test
    fun `search3 songs parse with Navidrome fields`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","openSubsonic":true,
                   "searchResult3":{"song":[
                     {"id":"a1","title":"Song A","album":"Alb","albumId":"al1","artist":"Art","artistId":"ar1","track":3,
                      "discNumber":1,"year":2001,"genre":"Rock","duration":215,"coverArt":"al-al1","suffix":"flac",
                      "bitRate":900,"size":12345678,"starred":"2024-01-01T00:00:00Z","playCount":7,"created":"2024-01-01",
                      "isDir":false,"replayGain":{"trackGain":-6.1},"genres":[{"name":"Rock"}]},
                     {"id":"a2","title":"Song B"}
                   ]}}}"""
            )
        )
        val songs = client.searchSongs("", 500, 0)
        assertEquals(2, songs.size)
        assertEquals("Song A", songs[0].title)
        assertEquals(215, songs[0].duration)
        assertTrue(songs[0].starred != null)
        assertEquals(null, songs[1].albumId)
        val url = server.takeRequest().requestUrl!!
        assertEquals("", url.queryParameter("query"))
        assertEquals("500", url.queryParameter("songCount"))
    }

    @Test
    fun `albums and artists are starred with their own parameter`() = runTest {
        repeat(4) { server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""")) }
        client.starAlbum("al1")
        client.unstarAlbum("al1")
        client.starArtist("ar1")
        client.unstarArtist("ar1")
        val urls = List(4) { server.takeRequest().requestUrl!! }
        assertEquals(listOf("/rest/star", "/rest/unstar", "/rest/star", "/rest/unstar"), urls.map { it.encodedPath })
        assertEquals("al1", urls[0].queryParameter("albumId"))
        assertEquals("al1", urls[1].queryParameter("albumId"))
        assertEquals("ar1", urls[2].queryParameter("artistId"))
        assertEquals("ar1", urls[3].queryParameter("artistId"))
        urls.forEach { assertEquals(null, it.queryParameter("id")) }
    }

    @Test
    fun `getStarred2 parses albums and artists, and copes with nothing starred`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"subsonic-response":{"status":"ok","version":"1.16.1","starred2":{
                   "artist":[{"id":"ar1","name":"Art","albumCount":2,"starred":"2024-01-01T00:00:00Z"}],
                   "album":[{"id":"al1","name":"Alb","starred":"2024-01-01T00:00:00Z"}]}}}"""
            )
        )
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1","starred2":{}}}"""))
        val s = client.starred()
        assertEquals(listOf("ar1"), s.artist.map { it.id })
        assertEquals(listOf("al1"), s.album.map { it.id })
        assertEquals("/rest/getStarred2", server.takeRequest().requestUrl!!.encodedPath)
        val empty = client.starred()
        assertTrue(empty.album.isEmpty() && empty.artist.isEmpty())
    }

    @Test
    fun `stream url picks raw or transcoded`() {
        val raw = client.streamUrl("x", 0)!!
        assertTrue(raw.contains("format=raw"))
        val lo = client.streamUrl("x", 128)!!
        assertTrue(lo.contains("maxBitRate=128"))
    }
}
