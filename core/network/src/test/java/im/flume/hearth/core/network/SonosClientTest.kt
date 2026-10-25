package im.flume.hearth.core.network

import im.flume.hearth.core.common.SonosException
import im.flume.hearth.core.common.SonosUpnpException
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Every control call, checked against a server that answers like a speaker does. */
class SonosClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SonosClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = SonosClient(OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() {
        // One test shuts the server down on purpose; closing it twice is not a failure.
        runCatching { server.shutdown() }
    }

    private fun ok(body: String = "<s:Envelope><s:Body><u:Response /></s:Body></s:Envelope>") {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/xml").setBody(body))
    }

    /** The SOAP body of a request, parsed: anything malformed fails here rather than in a vague assertion. */
    private fun soap(request: RecordedRequest): Document {
        val text = request.body.readUtf8()
        assertTrue("No XML declaration: $text", text.startsWith("<?xml"))
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(text.toByteArray()))
    }

    private fun Document.action(): Element {
        val body = getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Body").item(0) as Element
        return (0 until body.childNodes.length).map { body.childNodes.item(it) }.filterIsInstance<Element>().single()
    }

    private fun Document.arg(name: String): String = getElementsByTagName(name).item(0).textContent

    private fun next(path: String, soapAction: String): Document {
        val request = server.takeRequest()
        assertEquals(path, request.path)
        assertEquals("POST", request.method)
        assertEquals("\"$soapAction\"", request.getHeader("SOAPACTION"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("text/xml"))
        return soap(request)
    }

    private fun transport(action: String): Document =
        next("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1#$action")

    private fun rendering(action: String): Document =
        next("/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1#$action")

    @Test
    fun `setting a track sends escaped didl with the right protocolinfo`() = runTest {
        ok()
        val url = "http://192.168.1.10:4533/rest/stream.view?id=tr-42&u=leon&t=9f8e&s=abc&c=hearth"
        val didl = SonosXml.didl(url, SonosMime.MP3, "Get Lucky", "Daft Punk", album = "Random Access Memories")
        client.setUri(url, didl)

        val doc = transport("SetAVTransportURI")
        assertEquals("SetAVTransportURI", doc.action().localName)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", doc.action().namespaceURI)
        assertEquals("0", doc.arg("InstanceID"))
        assertEquals(url, doc.arg("CurrentURI"))

        // The metadata has to come out of the envelope as a document in its own right: that's the 714 bug.
        val meta = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(ByteArrayInputStream(doc.arg("CurrentURIMetaData").toByteArray()))
        val res = meta.getElementsByTagName("res").item(0) as Element
        assertEquals("http-get:*:audio/mpeg:*", res.getAttribute("protocolInfo"))
        assertEquals(url, res.textContent)
        assertEquals("Get Lucky", meta.getElementsByTagName("dc:title").item(0).textContent)
        assertEquals("Daft Punk", meta.getElementsByTagName("dc:creator").item(0).textContent)
        assertEquals(
            "object.item.audioItem.musicTrack",
            meta.getElementsByTagName("upnp:class").item(0).textContent,
        )
    }

    @Test
    fun `play pause stop and the skips send the plain transport actions`() = runTest {
        repeat(5) { ok() }
        client.play()
        client.pause()
        client.stop()
        client.next()
        client.previous()

        assertEquals("1", transport("Play").arg("Speed"))
        assertEquals("0", transport("Pause").arg("InstanceID"))
        assertEquals("0", transport("Stop").arg("InstanceID"))
        assertEquals("Next", transport("Next").action().localName)
        assertEquals("Previous", transport("Previous").action().localName)
    }

    @Test
    fun `seeking sends the position as H MM SS`() = runTest {
        ok()
        client.seekTo(5_025_000L)
        val doc = transport("Seek")
        assertEquals("REL_TIME", doc.arg("Unit"))
        assertEquals("1:23:45", doc.arg("Target"))
    }

    @Test
    fun `the speaker's state and position are read back`() = runTest {
        ok(
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
                |<u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                |<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>
                |<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>
                |</u:GetTransportInfoResponse></s:Body></s:Envelope>
            """.trimMargin()
        )
        ok(
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
                |<u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                |<Track>2</Track><TrackDuration>0:04:08</TrackDuration><TrackMetaData>&lt;DIDL-Lite /&gt;</TrackMetaData>
                |<TrackURI>x-rincon-queue:RINCON_7828CA301BFC01400#0</TrackURI>
                |<RelTime>0:01:07</RelTime><AbsTime>NOT_IMPLEMENTED</AbsTime>
                |</u:GetPositionInfoResponse></s:Body></s:Envelope>
            """.trimMargin()
        )

        assertEquals(SonosTransportState.PAUSED, client.transportState())
        val position = client.position()
        assertEquals(67_000L, position.elapsedMs)
        assertEquals(248_000L, position.durationMs)
        assertEquals("x-rincon-queue:RINCON_7828CA301BFC01400#0", position.trackUri)

        transport("GetTransportInfo")
        transport("GetPositionInfo")
    }

    @Test
    fun `volume is read and set on the rendering service, master channel`() = runTest {
        ok(
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
                |<u:GetVolumeResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
                |<CurrentVolume>31</CurrentVolume></u:GetVolumeResponse></s:Body></s:Envelope>
            """.trimMargin()
        )
        repeat(2) { ok() }

        assertEquals(31, client.volume())
        client.setVolume(44)
        client.setVolume(140)

        assertEquals("Master", rendering("GetVolume").arg("Channel"))
        assertEquals("44", rendering("SetVolume").arg("DesiredVolume"))
        // Out of range is pulled back in rather than refused by the speaker.
        assertEquals("100", rendering("SetVolume").arg("DesiredVolume"))
    }

    @Test
    fun `the queue is filled, handed over and emptied`() = runTest {
        ok(
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
                |<u:AddURIToQueueResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                |<FirstTrackNumberEnqueued>1</FirstTrackNumberEnqueued><NumTracksAdded>1</NumTracksAdded>
                |<NewQueueLength>7</NewQueueLength></u:AddURIToQueueResponse></s:Body></s:Envelope>
            """.trimMargin()
        )
        repeat(3) { ok() }

        val url = "http://192.168.1.10:4533/rest/stream.view?id=tr-7&u=leon"
        val added = client.addToQueue(url, SonosXml.didl(url, SonosMime.MP3, "Instant Crush", "Daft Punk"), index = 3)
        assertEquals(7, added)
        client.playQueue("RINCON_7828CA301BFC01400")
        client.clearQueue()

        val add = transport("AddURIToQueue")
        assertEquals(url, add.arg("EnqueuedURI"))
        assertEquals("3", add.arg("DesiredFirstTrackNumberEnqueued"))
        assertEquals("0", add.arg("EnqueueAsNext"))
        assertTrue(add.arg("EnqueuedURIMetaData").contains("""protocolInfo="http-get:*:audio/mpeg:*""""))

        // Playing the queue is the queue's own URI, which needs no metadata, and then a Play.
        val hand = transport("SetAVTransportURI")
        assertEquals("x-rincon-queue:RINCON_7828CA301BFC01400#0", hand.arg("CurrentURI"))
        assertEquals("", hand.arg("CurrentURIMetaData"))
        transport("Play")
        assertEquals("RemoveAllTracksFromQueue", transport("RemoveAllTracksFromQueue").action().localName)
    }

    @Test
    fun `a refused track comes back as error 714, not as HTTP 500`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody(
                """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                    |<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
                    |<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode>
                    |<errorDescription>Illegal MIME-Type</errorDescription></UPnPError></detail>
                    |</s:Fault></s:Body></s:Envelope>
                """.trimMargin()
            )
        )
        try {
            client.setUri("http://192.168.1.10:4533/rest/stream.view?id=tr-42", "")
            fail("A speaker that refuses the track should throw")
        } catch (e: SonosUpnpException) {
            assertEquals(714, e.errorCode)
            assertTrue(e.message!!.contains("714"))
            assertTrue(!e.message!!.contains("HTTP"))
        }
        transport("SetAVTransportURI")
    }

    @Test
    fun `a failure with nothing to read still says the speaker refused it`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("not xml at all"))
        try {
            client.play()
            fail("An unreadable failure should still throw")
        } catch (e: SonosUpnpException) {
            fail("A reply with no error code isn't a UPnP fault: $e")
        } catch (e: SonosException) {
            assertTrue(e.message!!.contains("HTTP 500"))
        }
    }

    @Test
    fun `a speaker that isn't there is a plain failure, not a crash`() = runTest {
        server.shutdown()
        try {
            client.pause()
            fail("A speaker that has gone should throw")
        } catch (e: SonosException) {
            assertTrue(e.message!!.contains("Couldn't reach the speaker"))
        }
    }

    @Test
    fun `an address with no port goes to the speakers' own port`() {
        assertEquals("http://192.168.1.121:1400/", SonosClient(OkHttpClient(), "192.168.1.121").address)
        assertEquals("http://192.168.1.121:1400/", SonosClient(OkHttpClient(), "192.168.1.121:1400").address)
        assertEquals(
            "http://192.168.1.121:1400/",
            SonosClient(OkHttpClient(), "http://192.168.1.121:1400/xml/device_description.xml").address,
        )
        // A speaker found by discovery is taken whole.
        val speaker = SonosSpeaker("RINCON_1400", "Living Room", "Sonos Play:1", "http://192.168.1.121:1400", "192.168.1.121")
        assertEquals("http://192.168.1.121:1400/", SonosClient(OkHttpClient(), speaker).address)
        try {
            SonosClient(OkHttpClient(), " ")
            fail("An empty address should be refused")
        } catch (e: SonosException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}
