// A hand-over against a stand-in speaker: the queue really is emptied, filled in order, started and seeked,
// and a speaker that goes silent half way through doesn't leave the app wedged.
package im.flume.hearth.core.playback

import im.flume.hearth.core.common.SonosException
import im.flume.hearth.core.network.SonosClient
import im.flume.hearth.core.network.SonosMime
import im.flume.hearth.core.network.SonosXml
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SonosSessionTest {
    private lateinit var server: MockWebServer
    private lateinit var session: SonosSession

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        // No retrying: a speaker that drops the connection should fail here and now, as it does on the phone.
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        session = SonosSession(SonosClient(http, server.url("/").toString()), "RINCON_1")
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun ok(body: String = "<s:Envelope><s:Body><u:Response /></s:Body></s:Envelope>") {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/xml").setBody(body))
    }

    /** The action name and the arguments of the next request the speaker was sent. */
    private fun next(): Pair<String, String> {
        val request = server.takeRequest()
        val action = request.getHeader("SOAPACTION")!!.substringAfter('#').trimEnd('"')
        return action to request.body.readUtf8()
    }

    private fun track(n: Int) = SonosTrack(
        url = "http://sonos.home/rest/stream.view?id=$n&u=leon&t=abc",
        mime = SonosMime.MP3,
        title = "Song $n",
        artist = "Artist",
    )

    @Test
    fun `the queue is emptied, filled in order, started and seeked to where the phone was`() = runTest {
        repeat(6) { ok() }

        session.handOver(SonosPlan.handover(listOf(track(1), track(2)), currentIndex = 0, positionMs = 65_000))

        assertEquals("RemoveAllTracksFromQueue", next().first)
        val (firstAction, firstBody) = next()
        assertEquals("AddURIToQueue", firstAction)
        assertTrue(enqueued(firstBody).endsWith("id=1&u=leon&t=abc"))
        // The metadata rides along with it, or the speaker answers 714 instead of playing.
        assertTrue(metadata(firstBody).contains("<dc:title>Song 1</dc:title>"))
        val (secondAction, secondBody) = next()
        assertEquals("AddURIToQueue", secondAction)
        assertTrue(enqueued(secondBody).endsWith("id=2&u=leon&t=abc"))
        val (startAction, startBody) = next()
        assertEquals("SetAVTransportURI", startAction)
        assertEquals("x-rincon-queue:RINCON_1#0", SonosXml.unescape(SonosXml.tagText(startBody, "CurrentURI")!!))
        assertEquals("Play", next().first)
        assertEquals("0:01:05", SonosXml.tagText(next().second, "Target"))
    }

    @Test
    fun `a speaker at the start of the queue is not told to seek at all`() = runTest {
        repeat(4) { ok() }

        session.handOver(SonosPlan.handover(listOf(track(1)), currentIndex = 0, positionMs = 0))

        assertEquals(listOf("RemoveAllTracksFromQueue", "AddURIToQueue", "SetAVTransportURI", "Play"), List(4) { next().first })
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `a speaker that goes quiet half way through the queue fails cleanly, and the sound comes home`() = runTest {
        ok()
        ok()
        server.enqueue(MockResponse().apply { socketPolicy = SocketPolicy.DISCONNECT_AT_START })

        val thrown = runCatching {
            session.handOver(SonosPlan.handover(List(3) { track(it + 1) }, currentIndex = 0, positionMs = 0))
        }.exceptionOrNull()

        assertTrue("Expected a SonosException, got $thrown", thrown is SonosException)
        // The queue was cleared and a track had gone over before it stopped answering.
        assertTrue(server.requestCount >= 2)
        assertEquals(
            SonosFailureAction.BACK_TO_PHONE,
            SonosFailures.action(thrown!!, consecutiveFailures = 1, handingOver = true),
        )
    }

    private fun enqueued(body: String) = SonosXml.unescape(SonosXml.tagText(body, "EnqueuedURI")!!)

    /** The DIDL document, which the SOAP envelope escaped on the way in. */
    private fun metadata(body: String) = SonosXml.unescape(SonosXml.tagText(body, "EnqueuedURIMetaData")!!)
}
