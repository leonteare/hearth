package im.flume.hearth.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TidarrClientTest {
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("tidarr/$name")!!.readText()

    private lateinit var server: MockWebServer
    private var key: String? = null
    private lateinit var client: TidarrClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = TidarrClient(OkHttpClient()) { TidarrConfig(server.url("/").toString().trimEnd('/'), key) }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `search parses tracks albums and artists`() {
        val r = TidarrParsing.search(fixture("search.json"))
        assertEquals(3, r.tracks.size)
        val lucky = r.tracks[0]
        assertEquals("77646178", lucky.id)
        assertEquals("Get Lucky (feat. Pharrell Williams & Nile Rodgers)", lucky.displayTitle)
        assertEquals(369, lucky.durationSec)
        assertEquals("USQX91300108", lucky.isrc)
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), lucky.artists)
        assertEquals("Random Access Memories", lucky.albumTitle)
        assertEquals("69e3c5f7-d5d2-4e7a-a2c5-4b3a6e4f0c11", lucky.cover)
        // ISRCs are upper-cased; a version already in the title isn't repeated.
        assertEquals("GBDUW0000053", r.tracks[1].isrc)
        assertTrue(r.tracks[1].explicit)
        assertEquals("Harder, Better, Faster, Stronger (Remastered)", r.tracks[2].displayTitle)
        assertNull(r.tracks[2].isrc)

        assertEquals(3, r.albums.size)
        assertEquals("77646169", r.albums[0].id)
        assertEquals(13, r.albums[0].numberOfTracks)
        assertEquals("2013", r.albums[0].year)
        assertEquals("Daft Punk, Pharrell Williams", r.albums[2].artist)

        assertEquals(2, r.artists.size)
        assertEquals("Daft Punk", r.artists[0].name)
        assertNull(r.artists[1].picture)
    }

    @Test
    fun `queue parses statuses and errors, numeric ids included`() {
        val q = TidarrParsing.queue(fixture("queue.json"))
        assertEquals(listOf("77646178", "4140100", "3634161", "1234"), q.map { it.id })
        assertEquals("download", q[0].status)
        assertFalse(q[0].error)
        assertTrue(q[2].error)
        assertEquals("download", q[2].errorStage)
        assertEquals("finished", q[3].status)
    }

    @Test
    fun `country code comes from tiddl config`() {
        assertEquals("GB", TidarrParsing.countryCode(fixture("settings.json")))
        assertNull(TidarrParsing.countryCode("""{"tiddl_config":{}}"""))
    }

    @Test
    fun `save body has the shape Tidarr expects`() {
        val o = Json.parseToJsonElement(TidarrParsing.saveBody(TidalType.ALBUM, "4140100")).jsonObject["item"]!!.jsonObject
        assertEquals("4140100", o["id"]!!.jsonPrimitive.content)
        assertTrue(o["id"]!!.jsonPrimitive.isString)
        assertEquals("https://listen.tidal.com/album/4140100", o["url"]!!.jsonPrimitive.content)
        assertEquals("album", o["type"]!!.jsonPrimitive.content)
        assertEquals("queue_download", o["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cover urls use the public cdn`() {
        assertEquals(
            "https://resources.tidal.com/images/69e3c5f7/d5d2/4e7a/a2c5/4b3a6e4f0c11/320x320.jpg",
            tidalImageUrl("69e3c5f7-d5d2-4e7a-a2c5-4b3a6e4f0c11"),
        )
        assertNull(tidalImageUrl(null))
    }

    @Test
    fun `display title adds the version only when missing`() {
        assertEquals("Song (2001 Remaster)", displayTitle("Song", "2001 Remaster"))
        assertEquals("Song (2001 Remaster)", displayTitle("Song (2001 Remaster)", "2001 Remaster"))
        assertEquals("Song", displayTitle("Song", null))
        assertEquals("Song", displayTitle("Song", " "))
    }

    @Test
    fun `search uses the country code and sends the api key as a header`() = runTest {
        key = "secret-key"
        server.enqueue(MockResponse().setBody(fixture("settings.json")))
        server.enqueue(MockResponse().setBody(fixture("search.json")))
        server.enqueue(MockResponse().setBody(fixture("search.json")))
        client.search("daft punk", limit = 5)
        val settings = server.takeRequest()
        assertEquals("/api/settings", settings.requestUrl!!.encodedPath)
        assertEquals("secret-key", settings.getHeader("X-Api-Key"))
        val search = server.takeRequest().requestUrl!!
        assertEquals("/proxy/tidal/v2/search", search.encodedPath)
        assertEquals("daft punk", search.queryParameter("query"))
        assertEquals("GB", search.queryParameter("countryCode"))
        assertEquals("BROWSER", search.queryParameter("deviceType"))
        assertEquals("5", search.queryParameter("limit"))
        // The country code is cached: the second search goes straight to the proxy.
        client.search("discovery")
        assertEquals("/proxy/tidal/v2/search", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `no api key means no header`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("queue.json")))
        client.queue()
        assertNull(server.takeRequest().getHeader("X-Api-Key"))
    }

    @Test
    fun `save posts json and remove sends the id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(200))
        client.save(TidalType.TRACK, "77646178")
        val save = server.takeRequest()
        assertEquals("POST", save.method)
        assertEquals("/api/save", save.requestUrl!!.encodedPath)
        assertTrue(save.body.readUtf8().contains("\"url\":\"https://listen.tidal.com/track/77646178\""))
        client.remove("77646178")
        val remove = server.takeRequest()
        assertEquals("DELETE", remove.method)
        assertEquals("""{"id":"77646178"}""", remove.body.readUtf8())
    }

    @Test
    fun `errors never carry the address or the key`() = runTest {
        key = "secret-key"
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            client.queue()
            fail()
        } catch (e: TidarrException) {
            assertFalse(e.message!!.contains("secret-key"))
            assertFalse(e.message!!.contains(server.hostName))
            assertTrue(e.message!!.contains("API key"))
        }
    }

    @Test
    fun `artist page parts parse`() {
        val artist = TidarrParsing.artist(fixture("artist.json"))!!
        assertEquals("3634161", artist.id)
        assertEquals("Daft Punk", artist.name)
        assertEquals("2b5e1a7e-3e9b-4f9e-9b36-bbb1d0d3e7a1", artist.picture)

        val albums = TidarrParsing.albums(fixture("artist_albums.json"))
        assertEquals(listOf("Random Access Memories", "Discovery"), albums.map { it.title })
        assertEquals(4462, albums[0].durationSec)
        assertEquals("3634161", albums[0].artistId)
        assertEquals("2001", albums[1].year)
        val singles = TidarrParsing.albums(fixture("artist_singles.json"))
        assertEquals("SINGLE", singles.single().type)
        assertEquals("Daft Punk, Pharrell Williams", singles.single().artist)

        val top = TidarrParsing.tracks(fixture("toptracks.json"))
        assertEquals(2, top.size)
        assertEquals("Get Lucky (feat. Pharrell Williams & Nile Rodgers)", top[0].displayTitle)
        assertEquals("USQX91300108", top[0].isrc)
        assertEquals("3634161", top[0].artistId)
        assertEquals("77646169", top[0].albumId)
        assertEquals(8, top[0].trackNumber)
    }

    @Test
    fun `album and its tracks parse`() {
        val album = TidarrParsing.album(fixture("album.json"))!!
        assertEquals("9001", album.id)
        assertEquals("Alive 2007", album.title)
        assertEquals(4560, album.durationSec)
        assertEquals(3, album.numberOfTracks)
        assertEquals("2007", album.year)
        assertEquals("3634161", album.artistId)
        val tracks = TidarrParsing.tracks(fixture("album_tracks.json"))
        assertEquals(listOf(2, 1, 1), tracks.map { it.volumeNumber })
        assertEquals("Touch It / Technologic (Live)", tracks[2].displayTitle)
        assertTrue(tracks[2].explicit)
        assertNull(TidarrParsing.artist("[]"))
        assertTrue(TidarrParsing.tracks("{}").isEmpty())
    }

    @Test
    fun `album page sorts by disc and track, fills in the cover, and is cached`() = runTest {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/api/settings" -> MockResponse().setBody(fixture("settings.json"))
                    path.endsWith("/v1/albums/9001/tracks") -> MockResponse().setBody(fixture("album_tracks.json"))
                    path.endsWith("/v1/albums/9001") -> MockResponse().setBody(fixture("album.json"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val page = client.albumPage("9001")
        assertEquals(listOf("9101", "9102", "9103"), page.tracks.map { it.id })
        assertTrue(page.tracks.all { it.cover == "deadbeef-0000-1111-2222-333344445555" })
        val count = server.requestCount
        client.albumPage("9001")
        assertEquals(count, server.requestCount)
    }

    @Test
    fun `artist page asks tidal through the proxy with the country and filters`() = runTest {
        val seen = java.util.concurrent.CopyOnWriteArrayList<okhttp3.HttpUrl>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                seen += url
                return when {
                    url.encodedPath == "/api/settings" -> MockResponse().setBody(fixture("settings.json"))
                    url.encodedPath.endsWith("/toptracks") -> MockResponse().setBody(fixture("toptracks.json"))
                    url.encodedPath.endsWith("/albums") && url.queryParameter("filter") == "EPSANDSINGLES" -> MockResponse().setBody(fixture("artist_singles.json"))
                    url.encodedPath.endsWith("/albums") -> MockResponse().setBody(fixture("artist_albums.json"))
                    else -> MockResponse().setBody(fixture("artist.json"))
                }
            }
        }
        val page = client.artistPage("3634161")
        assertEquals("Daft Punk", page.artist.name)
        assertEquals(2, page.topTracks.size)
        assertEquals(2, page.albums.size)
        assertEquals(1, page.singles.size)
        val tidal = seen.filter { it.encodedPath.startsWith("/proxy/tidal/v1/artists/3634161") }
        assertEquals(4, tidal.size)
        assertTrue(tidal.all { it.queryParameter("countryCode") == "GB" && it.queryParameter("deviceType") == "BROWSER" })
        assertEquals("10", tidal.first { it.encodedPath.endsWith("/toptracks") }.queryParameter("limit"))
    }

    @Test
    fun `save body carries the title and artist for the tidarr queue`() {
        val o = Json.parseToJsonElement(TidarrParsing.saveBody(TidalType.TRACK, "1", "Get Lucky", "Daft Punk")).jsonObject["item"]!!.jsonObject
        assertEquals("Get Lucky", o["title"]!!.jsonPrimitive.content)
        assertEquals("Daft Punk", o["artist"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ttl cache expires entries and drops the least recently used`() {
        var now = 0L
        val cache = TtlCache<String, Int>(max = 2, ttlMs = 100) { now }
        cache.put("a", 1)
        cache.put("b", 2)
        assertEquals(1, cache.get("a"))
        cache.put("c", 3)
        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a"))
        now = 101
        assertNull(cache.get("a"))
        assertNull(cache.get("c"))
    }
}
