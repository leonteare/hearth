// Saving a downloaded song's cover to the phone, finding it again, and clearing up after a removal.
package im.flume.hearth.domain.music

import im.flume.hearth.core.network.Credentials
import im.flume.hearth.core.network.SubsonicClient
import im.flume.hearth.domain.music.download.CoverStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverSavingTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: SubsonicClient
    private var onWifi = true

    private fun store() = CoverStore(folder.root.resolve("covers"), api, OkHttpClient(), TestScope()) { onWifi }

    private fun servePicture() =
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64) { 7 })).setHeader("Content-Type", "image/jpeg"))

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val creds = Credentials.fromPassword(server.url("/").toString(), "leon", "sesame")
        api = SubsonicClient(OkHttpClient()) { creds }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a saved cover is found again without asking the server`() = runTest {
        servePicture()
        val covers = store()
        covers.save("al-1")
        assertEquals(1, server.requestCount)

        assertTrue(covers.fileFor("al-1")!!.exists())
        covers.save("al-1")
        assertEquals("already here, so no second request", 1, server.requestCount)
    }

    @Test
    fun `a cover that was never saved is not claimed to be on the phone`() {
        assertNull(store().fileFor("al-nope"))
        assertNull(store().fileFor(null))
    }

    @Test
    fun `a server error leaves nothing behind`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val covers = store()
        covers.save("al-1")
        assertNull(covers.fileFor("al-1"))
        assertEquals("no half-written file is kept", 0, covers.dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `removing the music clears its art away`() = runTest {
        servePicture()
        servePicture()
        val covers = store()
        covers.save("al-1")
        covers.save("al-2")

        covers.sweep(listOf("al-2"))

        assertNull("the removed album's art is gone", covers.fileFor("al-1"))
        assertTrue("the kept album still has its art", covers.fileFor("al-2")!!.exists())
    }

    @Test
    fun `art is not fetched over mobile data when downloads are Wi-Fi only`() = runTest {
        onWifi = false
        val covers = store()
        covers.backfill(listOf("al-1", "al-2"))
        assertEquals(0, server.requestCount)

        onWifi = true
        servePicture()
        servePicture()
        covers.backfill(listOf("al-1", "al-2"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `backfill only fetches what is missing`() = runTest {
        servePicture()
        val covers = store()
        covers.save("al-1")
        servePicture()
        covers.backfill(listOf("al-1", "al-2"))
        assertEquals("al-1 was already here", 2, server.requestCount)
    }
}
