package im.flume.hearth.api

import im.flume.hearth.spoken.toBook
import im.flume.hearth.spoken.toEntity
import im.flume.hearth.spoken.toPodcast
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

/** Parsing uses responses captured from the real server (v2.37.1, tokens replaced); auth and paths against MockWebServer. */
class AbsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var store: FakeStore
    private lateinit var client: AbsClient

    private fun fixture(name: String) = javaClass.getResource("/abs/$name")!!.readText()

    class FakeStore(var url: String?, var t: AbsTokens?) : AbsAuthStore {
        var lost = false
        override fun serverUrl() = url
        override fun tokens() = t
        override fun saveTokens(tokens: AbsTokens) { t = tokens }
        override fun onAuthLost() { lost = true }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = FakeStore(server.url("/").toString().trimEnd('/'), AbsTokens("access-1", "refresh-1"))
        client = AbsClient(OkHttpClient(), store)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `login asks for tokens and parses the real response`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("login.json")))
        val resp = client.login(server.url("/").toString(), " leon ", "pw")
        val req = server.takeRequest()
        assertEquals("/login", req.path)
        assertEquals("true", req.getHeader("x-return-tokens"))
        assertNull(req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("leon", body["username"]!!.jsonPrimitive.content)
        assertEquals("leon", resp.user.username)
        assertEquals("root", resp.user.type)
        assertTrue(resp.user.accessToken!!.count { it == '.' } == 2)
        assertEquals(1791733423L, Jwt.expiresAt(resp.user.accessToken!!))
        assertEquals("2.37.1", resp.serverSettings?.version)
    }

    @Test
    fun `wrong password is a plain message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        try {
            client.login(server.url("/").toString(), "leon", "nope"); fail()
        } catch (e: AbsAuthException) {
            assertEquals("Wrong username or password", e.message)
        }
    }

    @Test
    fun `requests carry the token as a header, never in the URL`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("libraries.json")))
        val libs = client.libraries()
        val req = server.takeRequest()
        assertEquals("Bearer access-1", req.getHeader("Authorization"))
        assertFalse(req.path!!.contains("access-1"))
        assertEquals(listOf("podcast", "book"), libs.map { it.mediaType })
        assertEquals("/podcasts", libs[0].folders.single().fullPath)
        assertFalse(client.fileUrl("item", "123").contains("token"))
    }

    @Test
    fun `a 401 renews the tokens once and retries`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"user":{"id":"u1","username":"leon","accessToken":"access-2","refreshToken":"refresh-2"}}"""))
        server.enqueue(MockResponse().setBody(fixture("me.json")))
        val me = client.me()
        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/auth/refresh", refresh.path)
        assertEquals("refresh-1", refresh.getHeader("x-refresh-token"))
        assertNull(refresh.getHeader("Authorization"))
        assertEquals("Bearer access-2", server.takeRequest().getHeader("Authorization"))
        assertEquals(AbsTokens("access-2", "refresh-2"), store.t) // rotated refresh token saved
        assertEquals(1, me.mediaProgress.size)
    }

    @Test
    fun `a refused refresh token means signing in again`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid refresh token"}"""))
        try {
            client.me(); fail()
        } catch (e: AbsAuthException) {
            assertTrue(store.lost)
        }
    }

    @Test
    fun `the token never goes to other hosts`() = runTest {
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(MockResponse().setBody("ok"))
            client.http.newCall(okhttp3.Request.Builder().url(other.url("/x")).build()).execute().close()
            assertNull(other.takeRequest().getHeader("Authorization"))
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `server clock offset comes from the Date header`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("libraries.json")).setHeader("Date", "Sun, 11 Oct 2026 14:46:55 GMT"))
        client.libraries()
        val serverMs = ClockSkew.parseHttpDate("Sun, 11 Oct 2026 14:46:55 GMT")!!
        assertEquals(1791730015000L, serverMs)
        assertTrue(kotlin.math.abs(client.clock.offsetMs - (serverMs + 500 - System.currentTimeMillis())) < 5_000)
    }

    @Test
    fun `clock skew smoothing`() {
        assertEquals(10_000L, ClockSkew.update(null, 10_000))
        assertEquals(10_250L, ClockSkew.update(10_000, 11_000)) // jitter is smoothed
        assertEquals(-60_000L, ClockSkew.update(10_000, -60_000)) // a clock change jumps
        assertEquals(1_500L, ClockSkew.sample(serverDateMs = 2_000, sentAtMs = 0, receivedAtMs = 2_000))
    }

    @Test
    fun `podcast list and expanded item parse`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }
        val page = json.decodeFromString(AbsItemsPage.serializer(), fixture("podcast_items.json"))
        val pod = page.results.single().toPodcast(null)
        assertEquals("Up First from NPR", pod.title)
        assertEquals("https://feeds.npr.org/510318/podcast.xml", pod.feedUrl)
        assertEquals(0L, pod.updatedAt) // episodes not fetched yet

        val item = json.decodeFromString(AbsLibraryItem.serializer(), fixture("podcast_expanded.json"))
        val eps = item.media.episodes!!.map { it.toEntity(item.id) }
        assertEquals(2, eps.size)
        val e = eps.first()
        assertEquals("135964", e.ino)
        assertEquals(1696.600816, e.durationSec, 0.001)
        assertEquals(1791702000000L, e.publishedAt)
        assertEquals("audio/mpeg", e.mimeType)
        assertEquals(item.id, e.libraryItemId)
    }

    @Test
    fun `book with several files parses into tracks and chapters`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }
        val item = json.decodeFromString(AbsLibraryItem.serializer(), fixture("book_expanded.json"))
        val book = item.toBook(null)
        assertEquals("Some Author", book.author)
        assertEquals("The Series #2", book.series)
        assertEquals(3, book.numTracks)
        assertEquals(listOf("300001", "300002", "300003"), item.media.tracks!!.map { it.ino })
        assertEquals(4, item.media.chapters!!.size)
    }

    @Test
    fun `iTunes directory results parse`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }
        val r = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AbsPodcastSearchResult.serializer()), fixture("itunes_search.json"))
        assertEquals("Up First from NPR", r.first().title)
        assertEquals("https://feeds.npr.org/510318/podcast.xml", r.first().feedUrl)
        assertEquals(1222114325L, r.first().id)
    }

    @Test
    fun `local sessions and progress go to the right routes`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[{"id":"s1","success":true,"progressSynced":true}]}"""))
        server.enqueue(MockResponse().setBody("OK"))
        val results = client.syncLocalSessions(listOf(buildJsonObject { put("id", "s1") }), buildJsonObject { put("deviceId", "d") })
        assertTrue(results.single().success)
        val sync = server.takeRequest()
        assertEquals("/api/session/local-all", sync.path)
        val body = Json.parseToJsonElement(sync.body.readUtf8()).jsonObject
        assertEquals("s1", body["sessions"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        client.patchProgress("item", "ep", buildJsonObject { put("isFinished", true) })
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/me/progress/item/ep", patch.path)
    }

    @Test
    fun `podcast creation body matches the server's expectations`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("podcast_items.json").let { Json.parseToJsonElement(it).jsonObject["results"]!!.jsonArray[0].toString() }))
        client.createPodcast(
            "lib", "folder", "/podcasts/", AbsFeedMetadata(title = "Up First: Daily", author = "NPR", image = "https://img"),
            "https://feeds.npr.org/510318/podcast.xml", null, listOf("hearth-follower:leon"), autoDownload = true,
        )
        val req = server.takeRequest()
        assertEquals("/api/podcasts", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("/podcasts/Up First - Daily", body["path"]!!.jsonPrimitive.content)
        assertEquals("folder", body["folderId"]!!.jsonPrimitive.content)
        val media = body["media"]!!.jsonObject
        assertEquals("hearth-follower:leon", media["tags"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("https://feeds.npr.org/510318/podcast.xml", media["metadata"]!!.jsonObject["feedUrl"]!!.jsonPrimitive.content)
        assertEquals("true", media["autoDownloadEpisodes"]!!.jsonPrimitive.content)
    }

    @Test
    fun `folder names are sanitised like the server does`() {
        assertEquals("Up First - Daily", PodcastPaths.sanitizeFilename("Up First: Daily"))
        assertEquals("What If", PodcastPaths.sanitizeFilename("What If?"))
        assertEquals("AB", PodcastPaths.sanitizeFilename("A/B"))
        assertEquals("Trailing", PodcastPaths.sanitizeFilename("Trailing. "))
    }
}
