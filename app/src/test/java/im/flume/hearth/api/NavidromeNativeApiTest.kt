package im.flume.hearth.api

import im.flume.hearth.data.PhotoPrep
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

class NavidromeNativeApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: NavidromeNativeApi
    private var password: String? = "sesame"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val creds = Credentials.fromPassword(server.url("/").toString(), "leon", "sesame")
        api = NavidromeNativeApi(OkHttpClient(), { creds }, { password })
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `upload logs in then sends the photo with the token`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"t1","username":"leon"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("x-nd-authorization", "Bearer t2"))
        server.enqueue(MockResponse().setResponseCode(200))
        api.uploadPlaylistImage("pl1", byteArrayOf(1, 2, 3))
        val login = server.takeRequest()
        assertEquals("/auth/login", login.path)
        assertTrue(login.body.readUtf8().contains("\"password\":\"sesame\""))
        val upload = server.takeRequest()
        assertEquals("POST", upload.method)
        assertEquals("/api/playlist/pl1/image", upload.path)
        assertEquals("Bearer t1", upload.getHeader("x-nd-authorization"))
        assertTrue(upload.body.readUtf8().contains("name=\"image\""))
        // The refreshed token from the last response is used next time, without logging in again.
        api.removePlaylistImage("pl1")
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("Bearer t2", delete.getHeader("x-nd-authorization"))
    }

    @Test
    fun `an expired session logs in again once`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"old"}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"token":"new"}"""))
        server.enqueue(MockResponse().setResponseCode(200))
        api.removePlaylistImage("pl1")
        server.takeRequest(); server.takeRequest(); server.takeRequest()
        assertEquals("Bearer new", server.takeRequest().getHeader("x-nd-authorization"))
    }

    @Test
    fun `wrong password and missing password are told apart`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        try { api.removePlaylistImage("pl1"); fail() } catch (_: WrongPasswordException) {}
        password = null
        try { api.removePlaylistImage("pl1"); fail() } catch (_: PasswordNeededException) {}
    }

    @Test
    fun `photos are shrunk to fit without upscaling`() {
        assertEquals(1200 to 900, PhotoPrep.fit(4000, 3000))
        assertEquals(675 to 1200, PhotoPrep.fit(2250, 4000))
        assertEquals(800 to 600, PhotoPrep.fit(800, 600))
    }
}
